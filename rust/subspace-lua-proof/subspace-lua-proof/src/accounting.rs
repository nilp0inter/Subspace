//! Per-state memory accounting and denial.
//!
//! Lua allocation is governed by mlua's per-state memory limit
//! ([`mlua::Lua::set_memory_limit`]). We layer accounting on top:
//! - `current_bytes`: live Lua bytes via [`mlua::Lua::used_memory`].
//! - `peak_bytes`: sampled peak of `current_bytes`.
//! - `denied_allocations`: count of allocations rejected because they would
//!   exceed `memory_limit_bytes`. mlua's allocator returns `NULL` on denial;
//!   we observe denial by detecting that `used_memory` did not grow after a
//!   failed allocation and by counting explicit `MemoryFailure` outcomes.
//! - `bridge_bytes`: bytes owned by the Rust bridge (operation queues,
//!   snapshots, source strings) tracked separately and never reported as Lua
//!   bytes.
//!
//! We never claim bridge allocations are Lua allocations.

use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};

/// Snapshot of memory accounting for a state.
#[derive(Debug, Clone, Copy)]
pub struct MemoryReport {
    pub current_bytes: u64,
    pub peak_bytes: u64,
    pub denied_allocations: u64,
    pub bridge_bytes: u64,
    pub memory_limit_bytes: u64,
}

impl MemoryReport {
    pub fn zero(memory_limit_bytes: u64) -> Self {
        MemoryReport {
            current_bytes: 0,
            peak_bytes: 0,
            denied_allocations: 0,
            bridge_bytes: 0,
            memory_limit_bytes,
        }
    }
}

/// Atomic counters backing a state's memory report.
pub(crate) struct Accountant {
    memory_limit_bytes: AtomicU64,
    peak_bytes: AtomicU64,
    denied_allocations: AtomicU64,
    bridge_bytes: AtomicI64,
}

impl Accountant {
    pub fn new(memory_limit_bytes: u64) -> Self {
        Accountant {
            memory_limit_bytes: AtomicU64::new(memory_limit_bytes),
            peak_bytes: AtomicU64::new(0),
            denied_allocations: AtomicU64::new(0),
            bridge_bytes: AtomicI64::new(0),
        }
    }

    /// Set the stored memory limit to `new_limit`. Accepts any value
    /// (0 = unlimited). Primarily used to lower the limit after creation
    /// for deterministic denial injection in tests.
    pub(crate) fn lower_limit(&self, new_limit: u64) {
        self.memory_limit_bytes.store(new_limit, Ordering::Relaxed);
    }



    /// Record the current Lua-byte reading (from `Lua::used_memory`) and
    /// update the sampled peak. Returns the updated current value.
    pub fn record_lua_current(&self, current: u64) {
        let mut prev_peak = self.peak_bytes.load(Ordering::Relaxed);
        while current > prev_peak {
            match self
                .peak_bytes
                .compare_exchange(prev_peak, current, Ordering::Relaxed, Ordering::Relaxed)
            {
                Ok(_) => break,
                Err(actual) => prev_peak = actual,
            }
        }
    }

    /// Record one denied allocation (observed by the engine when mlua raises a
    /// memory error or when `used_memory` fails to grow past the limit).
    pub fn record_denial(&self) {
        self.denied_allocations.fetch_add(1, Ordering::Relaxed);
    }

    /// Add `n` bytes to the bridge-owned tally. Negative `n` via [`sub`].
    pub fn add_bridge(&self, n: i64) {
        self.bridge_bytes.fetch_add(n, Ordering::Relaxed);
    }

    pub fn sub_bridge(&self, n: i64) {
        self.bridge_bytes.fetch_sub(n, Ordering::Relaxed);
    }

    pub fn report(&self, current_lua_bytes: u64) -> MemoryReport {
        self.record_lua_current(current_lua_bytes);
        MemoryReport {
            current_bytes: current_lua_bytes,
            peak_bytes: self.peak_bytes.load(Ordering::Relaxed),
            denied_allocations: self.denied_allocations.load(Ordering::Relaxed),
            bridge_bytes: self.bridge_bytes.load(Ordering::Relaxed).max(0) as u64,
            memory_limit_bytes: self.memory_limit_bytes.load(Ordering::Relaxed),
        }
    }
}