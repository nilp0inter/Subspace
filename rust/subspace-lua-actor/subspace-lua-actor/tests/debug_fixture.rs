use std::sync::Arc;

use parking_lot::Mutex;
use serde_json::{json, Value};
use subspace_lua_actor::{Outcome, OutcomeKind, SpawnAdmission, SpawnAdmitter, StateEngine};

const MEMORY_LIMIT: u64 = 4 * 1024 * 1024;
const HOOK_INTERVAL: u32 = 100;
const INSTRUCTION_BUDGET: u64 = 50_000;
const DEBUG_ARCHIVE: &[u8] =
    include_bytes!("../../../../app/src/test/resources/debug-channel/subspace-channel.zip");
const MODES: [&str; 5] = ["ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS"];
const CAPABILITIES: [&str; 3] = ["audio.transcription", "audio.synthesis", "audio.playback"];

fn source_from_exact_archive() -> String {
    let mut offset = 0usize;
    while offset + 30 <= DEBUG_ARCHIVE.len() {
        if &DEBUG_ARCHIVE[offset..offset + 4] != b"PK\x03\x04" {
            offset += 1;
            continue;
        }
        let name_len =
            u16::from_le_bytes([DEBUG_ARCHIVE[offset + 26], DEBUG_ARCHIVE[offset + 27]]) as usize;
        let extra_len =
            u16::from_le_bytes([DEBUG_ARCHIVE[offset + 28], DEBUG_ARCHIVE[offset + 29]]) as usize;
        let size = u32::from_le_bytes([
            DEBUG_ARCHIVE[offset + 18],
            DEBUG_ARCHIVE[offset + 19],
            DEBUG_ARCHIVE[offset + 20],
            DEBUG_ARCHIVE[offset + 21],
        ]) as usize;
        let name_start = offset + 30;
        let data_start = name_start + name_len + extra_len;
        let data_end = data_start + size;
        assert!(
            data_end <= DEBUG_ARCHIVE.len(),
            "fixture local entry exceeds archive"
        );
        if &DEBUG_ARCHIVE[name_start..data_start - extra_len] == b"lua/plugin.lua" {
            return String::from_utf8(DEBUG_ARCHIVE[data_start..data_end].to_vec())
                .expect("Debug fixture Lua source is canonical UTF-8");
        }
        offset = data_end;
    }
    panic!("exact Debug fixture omitted lua/plugin.lua");
}

#[derive(Default)]
struct RecordingAdmitter {
    ops: Mutex<Vec<Value>>,
}

impl SpawnAdmitter for RecordingAdmitter {
    fn admit(&self, _: i64) -> SpawnAdmission {
        SpawnAdmission::Accepted
    }
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
fn admitter() -> Arc<RecordingAdmitter> {
    Arc::new(RecordingAdmitter::default())
}
fn invoke_input(
    state: &StateEngine,
    generation: i64,
    token: &str,
    host: &Arc<RecordingAdmitter>,
) -> Outcome {
    state.invoke_input_callback_with_spawn_admitter(
        generation,
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":1,"sample_rate":16000,"channels":1,"pcm_bytes":32}}"#,
        token,
        host.clone() as Arc<dyn SpawnAdmitter>,
    )
}

/// Claim the yielded request, record its typed payload, and return the kind so
/// the driver can resume with an appropriate value.
fn claim_and_record(
    state: &StateEngine,
    generation: i64,
    outcome: &Outcome,
    host: &Arc<RecordingAdmitter>,
) -> String {
    let request_id = outcome.to_json()["value"]
        .as_str()
        .expect("opaque request identity")
        .parse::<i64>()
        .expect("numeric request id");
    let claim = state.claim_host_operation(generation, request_id);
    assert_eq!(
        kind(&claim),
        OutcomeKind::Completed,
        "claim rejected: {:?}",
        claim.to_json()
    );
    let claim_json = claim.to_json();
    let op_kind = claim_json["hostOperationKind"]
        .as_str()
        .expect("host operation kind")
        .to_string();
    host.ops.lock().push(claim_json);
    op_kind
}

/// Drive a Debug input to completion: while the package yields host operations,
/// claim each one and resume by kind (transcript text for TRANSCRIBE, a synth
/// token for SYNTHESIZE, anything for PLAYBACK). Chains (TTS, STT_TTS) yield
/// several operations in sequence; the loop handles each in turn.
fn drive_input(
    state: &StateEngine,
    generation: i64,
    mut outcome: Outcome,
    host: &Arc<RecordingAdmitter>,
    transcript: &str,
    synth_token: &str,
) -> Outcome {
    for _ in 0..8 {
        if kind(&outcome) == OutcomeKind::Completed {
            return outcome;
        }
        assert_eq!(
            kind(&outcome),
            OutcomeKind::Yielded,
            "unexpected Debug outcome: {:?}",
            outcome.to_json()
        );
        let encoded = outcome.to_json();
        let op_kind = claim_and_record(state, generation, &outcome, host);
        let resume_value = match op_kind.as_str() {
            "TRANSCRIBE" => transcript,
            "SYNTHESIZE" => synth_token,
            "PLAYBACK" => "ignored",
            other => panic!("unexpected host operation kind: {other}"),
        };
        outcome = state.resume_coroutine_with_spawn_admitter(
            generation,
            encoded["coroutineId"]
                .as_i64()
                .expect("yielded coroutine id"),
            encoded["operationId"]
                .as_i64()
                .expect("yielded operation id"),
            true,
            resume_value,
            host.clone() as Arc<dyn SpawnAdmitter>,
        );
    }
    panic!(
        "Debug fixture operation did not terminate: {:?}",
        outcome.to_json()
    );
}
fn startup(state: &StateEngine, generation: i64, mode: &str) {
    let out = state.invoke_callback(
        generation,
        "startup",
        &serde_json::to_string(&json!({"schema_version": 1, "values": {"mode": mode}})).unwrap(),
    );
    assert_eq!(
        kind(&out),
        OutcomeKind::Completed,
        "startup {mode}: {:?}",
        out.to_json()
    );
}
fn readiness(state: &StateEngine, generation: i64, available: &[&str], mode: &str) -> bool {
    let mut capabilities = serde_json::Map::new();
    for capability in CAPABILITIES {
        capabilities.insert(
            capability.to_string(),
            json!(if available.contains(&capability) {
                "available"
            } else {
                "unavailable"
            }),
        );
    }
    let out = state.invoke_callback(
        generation,
        "handle_readiness",
        &serde_json::to_string(&json!({"capabilities": capabilities})).unwrap(),
    );
    assert_eq!(
        kind(&out),
        OutcomeKind::Completed,
        "readiness {mode}: {:?}",
        out.to_json()
    );
    value(&out)["ready"].as_bool().expect("readiness boolean")
}

#[test]
fn debug_fixture_real_state_engine_covers_dependency_sets_sequences_constants_and_errors() {
    let source = source_from_exact_archive();
    let state = engine();
    let generation = state.handle().generation;
    let image = serde_json::to_string(&json!({"plugin": source})).unwrap();
    assert_eq!(
        kind(&state.load_program_image(generation, &image, "plugin")),
        OutcomeKind::Completed
    );

    // Every startup mode is checked against every subset of the three declared capabilities.
    for mode in MODES {
        startup(&state, generation, mode);
        for mask in 0..(1usize << CAPABILITIES.len()) {
            let available: Vec<&str> = CAPABILITIES
                .iter()
                .enumerate()
                .filter_map(|(index, capability)| (mask & (1 << index) != 0).then_some(*capability))
                .collect();
            let expected = match mode {
                "ECHO" | "DELAYED_ECHO" => available.contains(&"audio.playback"),
                "STT" => available.contains(&"audio.transcription"),
                "TTS" => {
                    available.contains(&"audio.synthesis") && available.contains(&"audio.playback")
                }
                "STT_TTS" => available.len() == 3,
                _ => false,
            };
            assert_eq!(
                readiness(&state, generation, &available, mode),
                expected,
                "mode={mode}, available={available:?}"
            );
            assert_eq!(
                value(&state.invoke_callback(
                    generation,
                    "handle_readiness",
                    "{\"capabilities\":{}}"
                ))["status"],
                json!(mode)
            );
        }
    }

    // Invalid capture events are rejected before any host operation is claimed.
    startup(&state, generation, "ECHO");
    let host = admitter();
    let invalid = state.invoke_callback(generation, "handle_input", r#"{"event":"wrong"}"#);
    assert_eq!(kind(&invalid), OutcomeKind::Completed);
    assert_eq!(
        value(&invalid)["error"]["code"],
        json!("E_INVALID_ARGUMENT")
    );
    assert!(host.ops.lock().is_empty());

    // ECHO schedules captured audio immediately (one PLAYBACK claim, zero delay).
    startup(&state, generation, "ECHO");
    let before = host.ops.lock().len();
    let echo = invoke_input(&state, generation, "echo-token", &host);
    assert_eq!(kind(&echo), OutcomeKind::Yielded);
    let echo_done = drive_input(&state, generation, echo, &host, "", "synth");
    assert_eq!(value(&echo_done), json!({"ok": true}));
    let echo_ops = host.ops.lock().clone();
    let echo_ops = &echo_ops[before..];
    assert_eq!(
        echo_ops.len(),
        1,
        "ECHO must claim exactly one host operation: {echo_ops:?}"
    );
    assert_eq!(echo_ops[0]["hostOperationKind"], json!("PLAYBACK"));
    assert_eq!(echo_ops[0]["audioToken"], json!("echo-token"));
    assert_eq!(echo_ops[0]["delaySeconds"].as_f64(), Some(0.0));

    // DELAYED_ECHO retains both captures and claims them FIFO at exactly five seconds.
    startup(&state, generation, "DELAYED_ECHO");
    let before = host.ops.lock().len();
    let first = invoke_input(&state, generation, "first-token", &host);
    let _ = drive_input(&state, generation, first, &host, "", "synth");
    let second = invoke_input(&state, generation, "second-token", &host);
    let _ = drive_input(&state, generation, second, &host, "", "synth");
    let delayed = host.ops.lock().clone();
    let delayed = &delayed[before..];
    assert_eq!(
        delayed.len(),
        2,
        "DELAYED_ECHO must claim two playback operations: {delayed:?}"
    );
    assert!(delayed
        .iter()
        .all(|op| op["hostOperationKind"] == json!("PLAYBACK")));
    assert!(delayed
        .iter()
        .all(|op| op["delaySeconds"].as_f64() == Some(5.0)));
    assert_eq!(delayed[0]["audioToken"], json!("first-token"));
    assert_eq!(delayed[1]["audioToken"], json!("second-token"));

    // STT claims one transcription (no playback) and logs the exact transcript.
    startup(&state, generation, "STT");
    let before = host.ops.lock().len();
    let stt = drive_input(
        &state,
        generation,
        invoke_input(&state, generation, "stt-token", &host),
        &host,
        "exact transcript",
        "synth",
    );
    assert_eq!(value(&stt), json!({"ok": true}));
    let stt_ops = host.ops.lock().clone();
    let stt_ops = &stt_ops[before..];
    assert_eq!(
        stt_ops.len(),
        1,
        "STT must claim exactly one host operation: {stt_ops:?}"
    );
    assert_eq!(stt_ops[0]["hostOperationKind"], json!("TRANSCRIBE"));
    assert_eq!(stt_ops[0]["audioToken"], json!("stt-token"));
    assert!(!stt_ops
        .iter()
        .any(|op| op["hostOperationKind"] == json!("PLAYBACK")));
    let stt_json = stt.to_json();
    let logs = stt_json["logs"].as_array().expect("STT transcript log");
    assert!(logs.iter().any(|entry| entry
        .as_str()
        .unwrap_or_default()
        .contains("exact transcript")));

    // TTS uses exact synthesis constants, then schedules the synthesized audio.
    startup(&state, generation, "TTS");
    let before = host.ops.lock().len();
    let tts = drive_input(
        &state,
        generation,
        invoke_input(&state, generation, "tts-token", &host),
        &host,
        "",
        "synth",
    );
    assert_eq!(value(&tts), json!({"ok": true}));
    let tts_ops = host.ops.lock().clone();
    let tts_ops = &tts_ops[before..];
    assert_eq!(
        tts_ops.len(),
        2,
        "TTS must claim synthesis then playback: {tts_ops:?}"
    );
    assert_eq!(tts_ops[0]["hostOperationKind"], json!("SYNTHESIZE"));
    assert_eq!(tts_ops[0]["text"], json!("Debug synthesis test"));
    assert_eq!(tts_ops[0]["language"], json!("en"));
    assert_eq!(tts_ops[0]["voice"], json!("default"));
    assert_eq!(tts_ops[0]["speed"].as_f64(), Some(1.0));
    assert_eq!(tts_ops[1]["hostOperationKind"], json!("PLAYBACK"));
    assert_eq!(tts_ops[1]["delaySeconds"].as_f64(), Some(0.0));

    // STT_TTS chains the exact transcript into synthesis and then zero-delay playback.
    startup(&state, generation, "STT_TTS");
    let before = host.ops.lock().len();
    let stt_tts = drive_input(
        &state,
        generation,
        invoke_input(&state, generation, "stt-tts-token", &host),
        &host,
        "chained transcript",
        "synth",
    );
    assert_eq!(value(&stt_tts), json!({"ok": true}));
    let chain_ops = host.ops.lock().clone();
    let chain_ops = &chain_ops[before..];
    assert_eq!(
        chain_ops.len(),
        3,
        "STT_TTS must claim transcribe, synthesize, playback: {chain_ops:?}"
    );
    assert_eq!(chain_ops[0]["hostOperationKind"], json!("TRANSCRIBE"));
    assert_eq!(chain_ops[1]["hostOperationKind"], json!("SYNTHESIZE"));
    assert_eq!(chain_ops[1]["text"], json!("chained transcript"));
    assert_eq!(chain_ops[2]["hostOperationKind"], json!("PLAYBACK"));
    assert_eq!(chain_ops[2]["delaySeconds"].as_f64(), Some(0.0));

    // Each mode normalizes a host failure without fallback or substitution.
    for mode in MODES {
        startup(&state, generation, mode);
        let failed = invoke_input(&state, generation, "failure-token", &host);
        assert_eq!(
            kind(&failed),
            OutcomeKind::Yielded,
            "mode={mode} should yield its first operation"
        );
        let encoded = failed.to_json();
        let failed = state.resume_coroutine_with_spawn_admitter(
            generation,
            encoded["coroutineId"].as_i64().unwrap(),
            encoded["operationId"].as_i64().unwrap(),
            false,
            "E_BUSY",
            host.clone() as Arc<dyn SpawnAdmitter>,
        );
        assert_eq!(
            kind(&failed),
            OutcomeKind::Completed,
            "mode={mode} failure: {:?}",
            failed.to_json()
        );
        assert_eq!(value(&failed)["error"]["code"], json!("E_BUSY"));
        assert!(
            value(&failed)["error"]["detail"]
                .as_str()
                .unwrap_or_default()
                .len()
                > 0
        );
    }

    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}
