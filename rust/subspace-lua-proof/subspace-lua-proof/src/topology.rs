//! Execution topology.
//!
//! The single selected topology `jvm_owned`: the JNI caller thread drives the
//! engine directly under a per-state serialization lock. No background thread
//! is owned by the native side; the JVM owns the executing thread.
//!
//! The ownership/terminal state machine and workloads are topology-invariant,
//! so the contract's behavior is the same regardless of which topology
//! construction token is used.
//!
//! > **Proof note:** A second candidate (`native_owned`) was prototyped and
//! > removed after physical-device benchmarking showed no latency benefit and
//! > higher complexity. Only `jvm_owned` survives in the published proof crate.

use std::fmt;

/// Configured execution topology for a state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Topology {
    /// The JNI caller thread drives the engine directly. No per-state worker.
    JvmOwned,
}

impl Topology {
    /// Parse from the JNI `topology` string. Only `"jvm_owned"` is accepted.
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "jvm_owned" => Some(Topology::JvmOwned),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Topology::JvmOwned => "jvm_owned",
        }
    }
}

impl fmt::Display for Topology {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}