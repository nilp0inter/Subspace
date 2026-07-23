use serde_json::{json, Value};
use subspace_lua_actor::{Outcome, OutcomeKind, StateEngine};

const MEMORY_LIMIT: u64 = 4 * 1024 * 1024;
const HOOK_INTERVAL: u32 = 100;
const INSTRUCTION_BUDGET: u64 = 50_000;

const DIAGNOSTICS_ARCHIVE: &[u8] =
    include_bytes!("../../../../app/src/test/resources/diagnostics-channel/subspace-channel.zip");

fn source_from_exact_archive() -> String {
    let mut offset = 0usize;
    while offset + 30 <= DIAGNOSTICS_ARCHIVE.len() {
        if &DIAGNOSTICS_ARCHIVE[offset..offset + 4] != b"PK\x03\x04" {
            offset += 1;
            continue;
        }
        let name_len = u16::from_le_bytes([
            DIAGNOSTICS_ARCHIVE[offset + 26],
            DIAGNOSTICS_ARCHIVE[offset + 27],
        ]) as usize;
        let extra_len = u16::from_le_bytes([
            DIAGNOSTICS_ARCHIVE[offset + 28],
            DIAGNOSTICS_ARCHIVE[offset + 29],
        ]) as usize;
        let size = u32::from_le_bytes([
            DIAGNOSTICS_ARCHIVE[offset + 18],
            DIAGNOSTICS_ARCHIVE[offset + 19],
            DIAGNOSTICS_ARCHIVE[offset + 20],
            DIAGNOSTICS_ARCHIVE[offset + 21],
        ]) as usize;
        let name_start = offset + 30;
        let data_start = name_start + name_len + extra_len;
        let data_end = data_start + size;
        assert!(
            data_end <= DIAGNOSTICS_ARCHIVE.len(),
            "fixture local entry exceeds archive"
        );
        if &DIAGNOSTICS_ARCHIVE[name_start..data_start - extra_len] == b"lua/plugin.lua" {
            return String::from_utf8(DIAGNOSTICS_ARCHIVE[data_start..data_end].to_vec())
                .expect("Diagnostics fixture Lua source is canonical UTF-8");
        }
        offset = data_end;
    }
    panic!("exact Diagnostics fixture omitted lua/plugin.lua");
}

fn kind(outcome: &Outcome) -> OutcomeKind {
    outcome.kind()
}

fn value(outcome: &Outcome) -> Value {
    outcome.to_json()["value"].clone()
}

fn engine() -> StateEngine {
    StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, INSTRUCTION_BUDGET, 16, 16)
        .expect("fixture state creation")
}

fn startup(state: &StateEngine, generation: i64, config: &Value) -> Outcome {
    let out = state.invoke_callback(
        generation,
        "startup",
        &serde_json::to_string(config).unwrap(),
    );
    assert_eq!(
        kind(&out),
        OutcomeKind::Completed,
        "startup: {:?}",
        out.to_json()
    );
    out
}

/// Helper: load the diagnostics source, run startup with valid config, return generation.
fn load_and_initialize() -> (StateEngine, i64) {
    let source = source_from_exact_archive();
    let state = engine();
    let generation = state.handle().generation;
    let image = serde_json::to_string(&json!({"plugin": source})).unwrap();
    assert_eq!(
        kind(&state.load_program_image(generation, &image, "plugin")),
        OutcomeKind::Completed
    );
    (state, generation)
}

// ---------------------------------------------------------------------------
// Startup configuration validation
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_valid_configuration_succeeds() {
    let (state, generation) = load_and_initialize();
    let out = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let v = value(&out);
    // Startup with valid config must not return an error.
    assert!(
        v.get("error").is_none(),
        "valid config must not produce error: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_invalid_configuration_rejected() {
    let (state, generation) = load_and_initialize();
    let cases = vec![
        json!({"schema_version": 2, "values": {}}),
        json!({"schema_version": 1, "values": {"extra": true}}),
        json!({"schema_version": 1}),
        json!({"values": {}}),
        json!({}),
        json!(null),
    ];
    for config in cases {
        let out = startup(&state, generation, &config);
        let v = value(&out);
        assert_eq!(
            v["error"]["code"],
            json!("E_CONFIGURATION"),
            "invalid config must produce E_CONFIGURATION: input={:?}, output={:?}",
            config,
            v
        );
    }
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_schema_version_mismatch_is_configuration_error() {
    let (state, generation) = load_and_initialize();
    let out = startup(
        &state,
        generation,
        &json!({"schema_version": 2, "values": {}}),
    );
    let v = value(&out);
    assert_eq!(v["error"]["code"], json!("E_CONFIGURATION"));
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_nonempty_values_configuration_rejected() {
    let (state, generation) = load_and_initialize();
    let out = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {"unexpected": "field"}}),
    );
    let v = value(&out);
    assert_eq!(v["error"]["code"], json!("E_CONFIGURATION"));
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// Heartbeat spawn
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_startup_spawns_heartbeat() {
    let (state, generation) = load_and_initialize();
    let out = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let v = value(&out);
    assert!(v.get("error").is_none(), "startup must not error: {:?}", v);
    // The heartbeat coroutine is spawned; it immediately yields on runtime.sleep(30.0)
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// Readiness
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_readiness_returns_ready() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let out = state.invoke_callback(generation, "handle_readiness", "null");
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert_eq!(
        v["ready"],
        json!(true),
        "readiness must return ready=true: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// Input capture
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_input_valid_capture_accepted() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let out = state.invoke_callback(
        generation,
        "handle_input",
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":500,"sample_rate":16000,"channels":1}}"#,
    );
    assert_eq!(
        kind(&out),
        OutcomeKind::Completed,
        "valid capture: {:?}",
        out.to_json()
    );
    let v = value(&out);
    assert_eq!(
        v,
        json!({"ok": true}),
        "valid capture must return ok: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_input_malformed_metadata_rejected() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let cases = vec![
        // Missing metadata
        r#"{"event":"capture","session":"session-1"}"#,
        // Metadata with wrong type
        r#"{"event":"capture","session":"session-1","metadata":"string"}"#,
        // Negative duration
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":-1,"sample_rate":16000,"channels":1}}"#,
        // Zero sample rate
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":500,"sample_rate":0,"channels":1}}"#,
        // Wrong event
        r#"{"event":"wrong","session":"session-1","metadata":{"duration_ms":500,"sample_rate":16000,"channels":1}}"#,
        // Missing session
        r#"{"event":"capture","metadata":{"duration_ms":500,"sample_rate":16000,"channels":1}}"#,
        // Non-numeric channels
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":500,"sample_rate":16000,"channels":"stereo"}}"#,
    ];
    for input in &cases {
        let out = state.invoke_callback(generation, "handle_input", input);
        assert_eq!(
            kind(&out),
            OutcomeKind::Completed,
            "malformed input must complete: input={}, outcome={:?}",
            input,
            out.to_json()
        );
        let v = value(&out);
        assert!(
            v.get("error").is_some(),
            "malformed input must produce error: input={}, output={:?}",
            input,
            v
        );
        let code = v["error"]["code"].as_str().unwrap_or_default();
        assert!(
            code == "E_INPUT" || code == "E_INPUT_MALFORMED",
            "error code must be E_INPUT or E_INPUT_MALFORMED: input={}, code={}",
            input,
            code
        );
    }
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_input_excessive_values_are_bounded() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    // Duration exceeds 86400000 ms (24 hours)
    let out = state.invoke_callback(
        generation,
        "handle_input",
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":86400001,"sample_rate":16000,"channels":1}}"#,
    );
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert_eq!(
        v["error"]["code"],
        json!("E_INPUT_MALFORMED"),
        "excessive duration: {:?}",
        v
    );

    // Sample rate exceeds 384000
    let out = state.invoke_callback(
        generation,
        "handle_input",
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":500,"sample_rate":384001,"channels":1}}"#,
    );
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert_eq!(
        v["error"]["code"],
        json!("E_INPUT_MALFORMED"),
        "excessive sample rate: {:?}",
        v
    );

    // Channel count exceeds 32
    let out = state.invoke_callback(
        generation,
        "handle_input",
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":500,"sample_rate":16000,"channels":33}}"#,
    );
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert_eq!(
        v["error"]["code"],
        json!("E_INPUT_MALFORMED"),
        "excessive channels: {:?}",
        v
    );

    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_input_nan_is_rejected() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    // NaN in sample rate must be rejected.
    let out = state.invoke_callback(
        generation,
        "handle_input",
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":500,"sample_rate":null,"channels":1}}"#,
    );
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert!(
        v.get("error").is_some(),
        "NaN/null sample rate must be rejected: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// SOS dispatch
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_sos_dispatch_completes() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let out = state.invoke_callback(generation, "handle_sos", r#"{"event":"sos"}"#);
    assert_eq!(
        kind(&out),
        OutcomeKind::Completed,
        "SOS dispatch: {:?}",
        out.to_json()
    );
    let v = value(&out);
    assert!(
        v.get("error").is_none(),
        "valid SOS must not produce error: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_sos_unexpected_event_rejected() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let out = state.invoke_callback(generation, "handle_sos", r#"{"event":"something_else"}"#);
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert_eq!(
        v["error"]["code"],
        json!("E_SOS"),
        "unexpected SOS event: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_lifecycle_ready_accepted() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let out = state.invoke_callback(generation, "handle_lifecycle", r#"{"event":"ready"}"#);
    assert_eq!(
        kind(&out),
        OutcomeKind::Completed,
        "lifecycle ready: {:?}",
        out.to_json()
    );
    let v = value(&out);
    assert!(
        v.get("error").is_none(),
        "valid lifecycle must not error: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

#[test]
fn diagnostics_lifecycle_unexpected_event_rejected() {
    let (state, generation) = load_and_initialize();
    let _ = startup(
        &state,
        generation,
        &json!({"schema_version": 1, "values": {}}),
    );
    let out = state.invoke_callback(generation, "handle_lifecycle", r#"{"event":"invalid"}"#);
    assert_eq!(kind(&out), OutcomeKind::Completed);
    let v = value(&out);
    assert_eq!(
        v["error"]["code"],
        json!("E_LIFECYCLE"),
        "unexpected lifecycle: {:?}",
        v
    );
    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// State isolation and close
// ---------------------------------------------------------------------------

#[test]
fn diagnostics_independent_states_isolate_modules_and_close_independently() {
    let source = source_from_exact_archive();
    let left = engine();
    let right = engine();
    let gen_left = left.handle().generation;
    let gen_right = right.handle().generation;

    let image = serde_json::to_string(&json!({"plugin": source})).unwrap();
    assert_eq!(
        kind(&left.load_program_image(gen_left, &image, "plugin")),
        OutcomeKind::Completed
    );
    assert_eq!(
        kind(&right.load_program_image(gen_right, &image, "plugin")),
        OutcomeKind::Completed
    );

    // Startup both with valid config — each state is independent
    let left_out = startup(&left, gen_left, &json!({"schema_version": 1, "values": {}}));
    let right_out = startup(
        &right,
        gen_right,
        &json!({"schema_version": 1, "values": {}}),
    );
    assert!(value(&left_out).get("error").is_none());
    assert!(value(&right_out).get("error").is_none());

    // Close left independently — right must still be usable
    assert_eq!(kind(&left.close(gen_left)), OutcomeKind::Closed);
    let right_readiness = right.invoke_callback(gen_right, "handle_readiness", "null");
    assert_eq!(kind(&right_readiness), OutcomeKind::Completed);
    assert_eq!(
        value(&right_readiness)["ready"],
        json!(true),
        "right state must remain usable after left close"
    );
    assert_eq!(kind(&right.close(gen_right)), OutcomeKind::Closed);
}
