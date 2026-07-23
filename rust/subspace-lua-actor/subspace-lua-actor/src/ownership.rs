//! Generation-safe ownership and operation/coroutine handles.
//!
//! State handles are `(stateId, generation)` tuples. Coroutine and operation
//! handles carry their owning state handle. No raw pointers or Lua registry
//! indices cross JNI: only integer ids.
//!
//! The ownership state machine enforces:
//! - duplicate operations never enter Lua (idempotent exactly-once);
//! - foreign operations (operationId not issued by this state) are rejected;
//! - stale generations are rejected without touching Lua;
//! - post-close operations report `closed` and never enter Lua.

use std::sync::atomic::{AtomicI64, Ordering};

/// Opaque state id. Positive, monotonically assigned.
pub type StateId = i64;

/// Monotonically increasing generation for a given state id. Bumped on close.
pub type Generation = i64;

/// Opaque operation id. Positive, monotonically assigned per state.
pub type OperationId = i64;

/// A `(stateId, generation)` handle presented across JNI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StateHandle {
    pub state_id: StateId,
    pub generation: Generation,
}

impl StateHandle {
    pub fn new(state_id: StateId, generation: Generation) -> Self {
        StateHandle {
            state_id,
            generation,
        }
    }
}

/// Global monotonic id source. Lives for the process lifetime; only assigned,
/// never reused, so ids are stable across close/reopen.
static NEXT_STATE_ID: AtomicI64 = AtomicI64::new(1);

pub fn assign_state_id() -> StateId {
    NEXT_STATE_ID.fetch_add(1, Ordering::Relaxed)
}

/// Global monotonic operation-id source. Operation ids are globally unique
/// across all states so a foreign state's operation id can never collide
/// with another state's current operation.
static NEXT_OPERATION_ID: AtomicI64 = AtomicI64::new(1);

pub fn assign_operation_id() -> OperationId {
    NEXT_OPERATION_ID.fetch_add(1, Ordering::Relaxed)
}

/// Lifecycle phase of a state.
///
/// The coroutine progresses through phases until it reaches a terminal
/// state. `Closed` is the state-level terminal (state dropped, generation
/// bumped). A coroutine in `Completed`/`Failed`/`Cancelled` can still be
/// re-loaded or snapshotted; only `Closed` is fully terminal.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lifecycle {
    /// Created, source not yet loaded.
    Init,
    /// Source loaded and entrypoint validated; ready to start.
    Loaded,
    /// Entry coroutine started, not currently suspended on an operation.
    Running,
    /// Suspended on a yielded operation awaiting resume/cancel.
    Yielded,
    /// Coroutine finished successfully (terminal coroutine state).
    Completed,
    /// Coroutine errored or was interrupted (terminal coroutine state).
    Failed,
    /// Coroutine was cancelled (terminal coroutine state).
    Cancelled,
    /// State closed (state-level terminal). All further operations report `closed`.
    Closed,
}

impl Lifecycle {
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            Lifecycle::Completed | Lifecycle::Failed | Lifecycle::Cancelled | Lifecycle::Closed
        )
    }

    pub fn is_coroutine_terminal(self) -> bool {
        matches!(
            self,
            Lifecycle::Completed | Lifecycle::Failed | Lifecycle::Cancelled
        )
    }

    pub fn is_closed(self) -> bool {
        matches!(self, Lifecycle::Closed)
    }

    pub fn is_running_or_yielded(self) -> bool {
        matches!(self, Lifecycle::Running | Lifecycle::Yielded)
    }
}

/// Ownership/operation status returned to the engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OwnershipVerdict {
    /// Operation may proceed.
    Admit,
    /// Generation does not match the live generation.
    Stale,
    /// State has been closed.
    Closed,
}

/// Status of an operation id during resume/cancel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OperationVerdict {
    /// First time we see this op for resume/cancel — proceed.
    Admit,
    /// Already resumed/cancelled — duplicate, ignore (exactly-once).
    Duplicate,
    /// Operation id was never issued by this state.
    Foreign,
}
