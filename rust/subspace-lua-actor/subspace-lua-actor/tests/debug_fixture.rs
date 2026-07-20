use std::sync::Arc;

use parking_lot::Mutex;
use serde_json::{json, Value};
use subspace_lua_actor::{Outcome, OutcomeKind, SpawnAdmission, SpawnAdmitter, StateEngine};

const MEMORY_LIMIT: u64 = 4 * 1024 * 1024;
const HOOK_INTERVAL: u32 = 100;
const INSTRUCTION_BUDGET: u64 = 50_000;
const DEBUG_ARCHIVE: &[u8] = include_bytes!("../../../../app/src/test/resources/debug-channel/subspace-channel.zip");
const MODES: [&str; 5] = ["ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS"];
const CAPABILITIES: [&str; 3] = ["audio.transcription", "audio.synthesis", "audio.playback"];

fn source_from_exact_archive() -> String {
    let mut offset = 0usize;
    while offset + 30 <= DEBUG_ARCHIVE.len() {
        if &DEBUG_ARCHIVE[offset..offset + 4] != b"PK\x03\x04" {
            offset += 1;
            continue;
        }
        let name_len = u16::from_le_bytes([DEBUG_ARCHIVE[offset + 26], DEBUG_ARCHIVE[offset + 27]]) as usize;
        let extra_len = u16::from_le_bytes([DEBUG_ARCHIVE[offset + 28], DEBUG_ARCHIVE[offset + 29]]) as usize;
        let size = u32::from_le_bytes([
            DEBUG_ARCHIVE[offset + 18],
            DEBUG_ARCHIVE[offset + 19],
            DEBUG_ARCHIVE[offset + 20],
            DEBUG_ARCHIVE[offset + 21],
        ]) as usize;
        let name_start = offset + 30;
        let data_start = name_start + name_len + extra_len;
        let data_end = data_start + size;
        assert!(data_end <= DEBUG_ARCHIVE.len(), "fixture local entry exceeds archive");
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
    calls: Mutex<Vec<String>>,
}

impl SpawnAdmitter for RecordingAdmitter {
    fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }

    fn admit_transcription(&self, operation: i64, token: String) -> i32 {
        self.calls.lock().push(format!("transcription:{operation}:{token}"));
        0
    }

    fn admit_synthesis(&self, operation: i64, params: String) -> i32 {
        self.calls.lock().push(format!("synthesis:{operation}:{params}"));
        0
    }

    fn admit_playback(&self, operation: i64, token: String, delay: f64) -> i32 {
        self.calls.lock().push(format!("playback:{operation}:{token}:{delay}"));
        0
    }
}

fn kind(outcome: &Outcome) -> OutcomeKind { outcome.kind() }
fn value(outcome: &Outcome) -> Value { outcome.to_json()["value"].clone() }
fn engine() -> StateEngine {
    StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, INSTRUCTION_BUDGET, 16, 16)
        .expect("fixture state creation")
}
fn admitter() -> Arc<RecordingAdmitter> { Arc::new(RecordingAdmitter::default()) }
fn invoke_input(state: &StateEngine, generation: i64, token: &str, host: &Arc<RecordingAdmitter>) -> Outcome {
    state.invoke_input_callback_with_spawn_admitter(
        generation,
        r#"{"event":"capture","session":"session-1","metadata":{"duration_ms":1,"sample_rate":16000,"channels":1}}"#,
        token,
        host.clone() as Arc<dyn SpawnAdmitter>,
    )
}
fn resume_success(state: &StateEngine, generation: i64, mut outcome: Outcome, host: &Arc<RecordingAdmitter>, value: &str) -> Outcome {
    for _ in 0..8 {
        if kind(&outcome) == OutcomeKind::Completed { return outcome; }
        assert_eq!(kind(&outcome), OutcomeKind::Yielded, "unexpected Debug outcome: {:?}", outcome.to_json());
        let encoded = outcome.to_json();
        let label = encoded["label"].as_str().unwrap_or_default();
        assert!(!label.to_ascii_lowercase().contains("sleep"), "Debug fixture must not sleep: {label}");
        outcome = state.resume_coroutine_with_spawn_admitter(
            generation,
            encoded["coroutineId"].as_i64().expect("yielded coroutine id"),
            encoded["operationId"].as_i64().expect("yielded operation id"),
            true,
            value,
            host.clone() as Arc<dyn SpawnAdmitter>,
        );
    }
    panic!("Debug fixture operation did not terminate: {:?}", outcome.to_json());
}
fn startup(state: &StateEngine, generation: i64, mode: &str) {
    let out = state.invoke_callback(
        generation,
        "startup",
        &serde_json::to_string(&json!({"schema_version": 1, "values": {"mode": mode}})).unwrap(),
    );
    assert_eq!(kind(&out), OutcomeKind::Completed, "startup {mode}: {:?}", out.to_json());
}
fn readiness(state: &StateEngine, generation: i64, available: &[&str], mode: &str) -> bool {
    let mut capabilities = serde_json::Map::new();
    for capability in CAPABILITIES {
        capabilities.insert(capability.to_string(), json!(if available.contains(&capability) { "available" } else { "unavailable" }));
    }
    let out = state.invoke_callback(
        generation,
        "handle_readiness",
        &serde_json::to_string(&json!({"capabilities": capabilities})).unwrap(),
    );
    assert_eq!(kind(&out), OutcomeKind::Completed, "readiness {mode}: {:?}", out.to_json());
    value(&out)["ready"].as_bool().expect("readiness boolean")
}

#[test]
fn debug_fixture_real_state_engine_covers_dependency_sets_sequences_constants_and_errors() {
    let source = source_from_exact_archive();
    let state = engine();
    let generation = state.handle().generation;
    let image = serde_json::to_string(&json!({"plugin": source})).unwrap();
    assert_eq!(kind(&state.load_program_image(generation, &image, "plugin")), OutcomeKind::Completed);

    // Every startup mode is checked against every subset of the three declared capabilities.
    for mode in MODES {
        startup(&state, generation, mode);
        for mask in 0..(1usize << CAPABILITIES.len()) {
            let available: Vec<&str> = CAPABILITIES.iter().enumerate()
                .filter_map(|(index, capability)| (mask & (1 << index) != 0).then_some(*capability))
                .collect();
            let expected = match mode {
                "ECHO" | "DELAYED_ECHO" => available.contains(&"audio.playback"),
                "STT" => available.contains(&"audio.transcription"),
                "TTS" => available.contains(&"audio.synthesis") && available.contains(&"audio.playback"),
                "STT_TTS" => available.len() == 3,
                _ => false,
            };
            assert_eq!(readiness(&state, generation, &available, mode), expected, "mode={mode}, available={available:?}");
            assert_eq!(value(&state.invoke_callback(generation, "handle_readiness", "{\"capabilities\":{}}"))["status"], json!(mode));
        }
    }

    // Invalid capture events are rejected before any host operation admission.
    startup(&state, generation, "ECHO");
    let host = admitter();
    let invalid = state.invoke_input_callback_with_spawn_admitter(generation, r#"{"event":"wrong"}"#, "invalid-token", host.clone() as Arc<dyn SpawnAdmitter>);
    assert_eq!(kind(&invalid), OutcomeKind::Completed);
    assert_eq!(value(&invalid)["error"]["code"], json!("E_INVALID_ARGUMENT"));
    assert!(host.calls.lock().is_empty());

    // ECHO schedules captured audio immediately and never yields a sleep operation.
    startup(&state, generation, "ECHO");
    let echo = invoke_input(&state, generation, "echo-token", &host);
    assert_eq!(kind(&echo), OutcomeKind::Yielded);
    let echo_done = resume_success(&state, generation, echo, &host, "ignored");
    assert_eq!(value(&echo_done), json!({"ok": true}));
    assert!(host.calls.lock().iter().any(|call| call.ends_with(":echo-token:0")));

    // DELAYED_ECHO retains both captures and admits them FIFO at exactly five seconds.
    startup(&state, generation, "DELAYED_ECHO");
    let first = invoke_input(&state, generation, "first-token", &host);
    let _ = resume_success(&state, generation, first, &host, "ignored");
    let second = invoke_input(&state, generation, "second-token", &host);
    let _ = resume_success(&state, generation, second, &host, "ignored");
    let calls = host.calls.lock().clone();
    let delayed: Vec<&String> = calls.iter().filter(|call| call.starts_with("playback:") && call.ends_with(":5" )).collect();
    assert!(delayed.iter().any(|call| call.contains(":first-token:5")));
    assert!(delayed.iter().any(|call| call.contains(":second-token:5")));
    assert!(delayed.iter().position(|call| call.contains(":first-token:5")) < delayed.iter().position(|call| call.contains(":second-token:5")));

    // STT logs the exact transcript and does not admit playback.
    startup(&state, generation, "STT");
    let before = host.calls.lock().len();
    let stt = resume_success(&state, generation, invoke_input(&state, generation, "stt-token", &host), &host, "exact transcript");
    assert_eq!(value(&stt), json!({"ok": true}));
    let stt_calls = host.calls.lock().clone();
    assert!(stt_calls[before..].iter().any(|call| call.starts_with("transcription:")));
    assert!(!stt_calls[before..].iter().any(|call| call.starts_with("playback:")));
    let stt_json = stt.to_json();
    let logs = stt_json["logs"].as_array().expect("STT transcript log");
    assert!(logs.iter().any(|entry| entry.as_str().unwrap_or_default().contains("exact transcript")));

    // TTS uses exact constants, then schedules synthesized audio at zero delay.
    startup(&state, generation, "TTS");
    let before = host.calls.lock().len();
    let tts = resume_success(&state, generation, invoke_input(&state, generation, "tts-token", &host), &host, "tts-audio");
    assert_eq!(value(&tts), json!({"ok": true}));
    let tts_calls = host.calls.lock().clone();
    let synthesis = tts_calls[before..].iter().find(|call| call.starts_with("synthesis:")).expect("TTS synthesis admission");
    let params: Value = serde_json::from_str(synthesis.split_once(':').and_then(|(_, rest)| rest.split_once(':')).map(|(_, encoded)| encoded).expect("synthesis params")).unwrap();
    assert_eq!(params, json!({"text":"Debug synthesis test","language":"en","voice":"default","speed":1.0}));
    assert!(tts_calls[before..].iter().any(|call| call.ends_with(":tts-audio:0")) || tts_calls[before..].iter().any(|call| call.starts_with("playback:")));

    // STT_TTS chains the exact transcript into synthesis and then zero-delay playback.
    startup(&state, generation, "STT_TTS");
    let before = host.calls.lock().len();
    let stt_tts = resume_success(&state, generation, invoke_input(&state, generation, "stt-tts-token", &host), &host, "chained transcript");
    assert_eq!(value(&stt_tts), json!({"ok": true}));
    let chain_calls = host.calls.lock().clone();
    let chain = &chain_calls[before..];
    assert!(chain.iter().any(|call| call.starts_with("transcription:")));
    let chain_synthesis = chain.iter().find(|call| call.starts_with("synthesis:")).expect("STT_TTS synthesis admission");
    let chain_params: Value = serde_json::from_str(chain_synthesis.split_once(':').and_then(|(_, rest)| rest.split_once(':')).map(|(_, encoded)| encoded).expect("chain synthesis params")).unwrap();
    assert_eq!(chain_params["text"], json!("chained transcript"));
    assert!(chain.iter().any(|call| call.starts_with("playback:")));

    // Each mode normalizes a host failure without fallback or substitution.
    for mode in MODES {
        startup(&state, generation, mode);
        let failed = invoke_input(&state, generation, "failure-token", &host);
        assert_eq!(kind(&failed), OutcomeKind::Yielded, "mode={mode} should admit its first operation");
        let encoded = failed.to_json();
        let failed = state.resume_coroutine_with_spawn_admitter(
            generation,
            encoded["coroutineId"].as_i64().unwrap(),
            encoded["operationId"].as_i64().unwrap(),
            false,
            "E_BUSY",
            host.clone() as Arc<dyn SpawnAdmitter>,
        );
        assert_eq!(kind(&failed), OutcomeKind::Completed, "mode={mode} failure: {:?}", failed.to_json());
        assert_eq!(value(&failed)["error"]["code"], json!("E_BUSY"));
        assert!(value(&failed)["error"]["detail"].as_str().unwrap_or_default().len() > 0);
    }

    assert_eq!(kind(&state.close(generation)), OutcomeKind::Closed);
}
