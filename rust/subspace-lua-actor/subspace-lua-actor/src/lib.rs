//! Internal embedded stock Lua 5.4 actor kernel for the Subspace plugin
//! runtime.
//!
//! Native library `subspace_lua_actor` (Android: `libsubspace_lua_actor.so`).
//! Kotlin JNI object `LuaNativeKernel` in package `dev.nilp0inter.subspace.lua`.
//!
//! # What this provides
//!
//! - A restricted stock Lua 5.4 VM with a narrow standard-library subset and
//!   no IO/OS/debug/FFI/dynamic loading.
//! - Generation-safe `(stateId, generation)` ownership with exactly-once
//!   resume/cancel/close and idempotent closure. Duplicate, foreign, stale,
//!   and post-close operations never enter Lua.
//! - Yielding operation semantics: a Lua entrypoint may call
//!   `subspace.yield_operation(label)` to suspend and yield an opaque
//!   operation; the host resumes once with success/failure or cancels once.
//! - Instruction hook with configurable count interval and total instruction
//!   budget; hook/budget failures and external interrupts normalize as
//!   `interrupted`.
//! - Per-state allocator accounting and denial (current, sampled peak, denied
//!   count, terminal Lua bytes, bridge-owned bytes recorded separately).
//! - The `jvm_owned` execution model driving the state engine and the same
//!   ownership/terminal state machine.
//! - JSON outcomes over JNI; JNI functions convert all expected errors to JSON
//!   and never unwind across the JNI boundary.
//!
//! # Pinned substrate
//!
//! `mlua = =0.12.0` with features `lua54`, `vendored`, `send`. `vendored`
//! pulls `lua-src 550.1.1`, which vendors upstream Lua 5.4 source (the Lua
//! 5.4.8 release line). `send` makes `Lua`/`Thread`/values `Send` so the
//! engine can hold a `Lua` instance behind an `Arc<Mutex<>>` and still satisfy
//! `Send + sync`.
//!
//! # Safety posture
//!
//! Release profile is `panic = "unwind"` (this crate lives in its own nested
//! Cargo workspace). Every JNI entry point wraps its body in
//! [`std::panic::catch_unwind`] and converts any panic into a `runtime_failure`
//! JSON outcome, so a Rust panic never propagates across the JNI boundary into
//! the JVM. `mlua`'s `catch_rust_panics` option is left at its default `true`
//! so Lua `pcall`/`xpcall` contain Rust panics raised inside callbacks.

#![allow(clippy::missing_safety_doc)]

mod accounting;
mod jni_bridge;
mod outcome;
mod ownership;
mod state;

/// Lua version reported to the host, matching the vendored substrate.
pub const LUA_VERSION: &str = "5.4.8";

/// Bridge binding version reported to the host.
pub const BINDING_VERSION: &str = "0.1.0";

pub use accounting::MemoryReport;
pub use outcome::{Outcome, OutcomeKind};
pub use ownership::{Generation, Lifecycle, OperationId, StateHandle, StateId};
pub use state::{StateEngine, StateSnapshot};

#[cfg(any(test, feature = "registry-test"))]
pub use jni_bridge::registry_test;