//! Normalized JSON outcome schema shared across the JNI boundary.
//!
//! Every JNI function returns a JSON object and must not throw for expected
//! input. The `kind` field selects the outcome variant; optional fields are
//! populated as appropriate. This mirrors the shared bridge contract exactly.

use std::fmt;

use serde_json::{json, Map, Value};

/// Required `kind` values. These are the only outcome categories the host
/// expects. Keep this list in lock-step with the Kotlin side.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutcomeKind {
    Created,
    Completed,
    Yielded,
    SyntaxFailure,
    ValidationFailure,
    RuntimeFailure,
    MemoryFailure,
    Interrupted,
    Cancelled,
    InvalidOwnership,
    Stale,
    Closed,
}

impl OutcomeKind {
    /// Wire string for this kind, exactly as the contract spells it.
    pub fn as_str(self) -> &'static str {
        match self {
            OutcomeKind::Created => "created",
            OutcomeKind::Completed => "completed",
            OutcomeKind::Yielded => "yielded",
            OutcomeKind::SyntaxFailure => "syntax_failure",
            OutcomeKind::ValidationFailure => "validation_failure",
            OutcomeKind::RuntimeFailure => "runtime_failure",
            OutcomeKind::MemoryFailure => "memory_failure",
            OutcomeKind::Interrupted => "interrupted",
            OutcomeKind::Cancelled => "cancelled",
            OutcomeKind::InvalidOwnership => "invalid_ownership",
            OutcomeKind::Stale => "stale",
            OutcomeKind::Closed => "closed",
        }
    }
}

impl fmt::Display for OutcomeKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// A normalized bridge outcome. Serializes to a flat JSON object via
/// [`Outcome::to_json`].
#[derive(Debug, Clone)]
pub struct Outcome {
    kind: OutcomeKind,
    fields: Map<String, Value>,
}

impl Outcome {
    pub fn new(kind: OutcomeKind) -> Self {
        Outcome {
            kind,
            fields: Map::new(),
        }
    }

    /// Add an optional field. `None` values are skipped so the JSON stays
    /// sparse and the host never sees null placeholders.
    pub fn with_opt(mut self, key: &str, value: Option<Value>) -> Self {
        if let Some(v) = value {
            self.fields.insert(key.to_string(), v);
        }
        self
    }

    pub fn with(mut self, key: &str, value: Value) -> Self {
        self.fields.insert(key.to_string(), value);
        self
    }

    pub fn kind(&self) -> OutcomeKind {
        self.kind
    }

    /// Serialize to a flat JSON object: `{"kind": "...", ...}`.
    pub fn to_json(&self) -> Value {
        let mut obj = Map::new();
        obj.insert(
            "kind".to_string(),
            Value::String(self.kind.as_str().to_string()),
        );
        for (k, v) in &self.fields {
            obj.insert(k.clone(), v.clone());
        }
        Value::Object(obj)
    }

    /// Serialize to a JSON string. Used for JNI return values.
    pub fn to_json_string(&self) -> String {
        self.to_json().to_string()
    }

    // ---- convenience constructors ------------------------------------------

    pub fn created(state_id: i64, generation: i64, topology: &str) -> Self {
        Outcome::new(OutcomeKind::Created)
            .with("stateId", json!(state_id))
            .with("generation", json!(generation))
            .with("topology", json!(topology))
            .with("luaVersion", json!(crate::LUA_VERSION))
            .with("bindingVersion", json!(crate::BINDING_VERSION))
    }

    pub fn invalid_ownership(diagnostic: impl Into<String>) -> Self {
        Outcome::new(OutcomeKind::InvalidOwnership).with("diagnostic", json!(diagnostic.into()))
    }

    pub fn stale(state_id: i64, generation: i64) -> Self {
        Outcome::new(OutcomeKind::Stale)
            .with("stateId", json!(state_id))
            .with("generation", json!(generation))
    }

    pub fn closed(state_id: i64, generation: i64) -> Self {
        Outcome::new(OutcomeKind::Closed)
            .with("stateId", json!(state_id))
            .with("generation", json!(generation))
    }

    pub fn syntax_failure(diagnostic: impl Into<String>) -> Self {
        Outcome::new(OutcomeKind::SyntaxFailure).with("diagnostic", json!(diagnostic.into()))
    }

    pub fn validation_failure(diagnostic: impl Into<String>) -> Self {
        Outcome::new(OutcomeKind::ValidationFailure).with("diagnostic", json!(diagnostic.into()))
    }

    pub fn runtime_failure(diagnostic: impl Into<String>) -> Self {
        Outcome::new(OutcomeKind::RuntimeFailure).with("diagnostic", json!(diagnostic.into()))
    }

    pub fn memory_failure(diagnostic: impl Into<String>) -> Self {
        Outcome::new(OutcomeKind::MemoryFailure).with("diagnostic", json!(diagnostic.into()))
    }
}
