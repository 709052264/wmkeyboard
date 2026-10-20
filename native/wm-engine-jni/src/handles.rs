//! Rust objects behind `long` handles: what Kotlin holds instead of the data.
//!
//! A mapped dictionary, an n-gram pack or a decoder's state lives here, and
//! Kotlin keeps the handle a [`Registry`] issued for it. Handles are never
//! reused (a 64-bit counter), and every call clones the object's `Arc`
//! before using it, so a handle closed on one thread while another is
//! mid-call cannot free what that call is reading: the object goes when its
//! last user does. A call with a closed or unknown handle gets `None` and
//! throws `IllegalStateException`, rather than reading freed memory.
//!
//! The cost per call is one uncontended read lock and an atomic increment,
//! tens of nanoseconds, small beside the JNI crossing itself.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, RwLock};

pub struct Registry<T> {
    objects: RwLock<HashMap<i64, Arc<T>>>,
    next: AtomicI64,
}

impl<T> Registry<T> {
    pub fn new() -> Registry<T> {
        Registry { objects: RwLock::new(HashMap::new()), next: AtomicI64::new(1) }
    }

    /// Keeps `object` and returns its handle, never 0.
    pub fn insert(&self, object: T) -> i64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        self.write().insert(handle, Arc::new(object));
        handle
    }

    pub fn get(&self, handle: i64) -> Option<Arc<T>> {
        self.objects.read().unwrap_or_else(|e| e.into_inner()).get(&handle).cloned()
    }

    /// Drops the registry's reference; true when the handle was live.
    pub fn remove(&self, handle: i64) -> bool {
        self.write().remove(&handle).is_some()
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.objects.read().unwrap_or_else(|e| e.into_inner()).len()
    }

    /// A panic never happens while the lock is held, but a poisoned lock
    /// would otherwise turn every later call into a panic too.
    fn write(&self) -> std::sync::RwLockWriteGuard<'_, HashMap<i64, Arc<T>>> {
        self.objects.write().unwrap_or_else(|e| e.into_inner())
    }
}

impl<T> Default for Registry<T> {
    fn default() -> Self {
        Registry::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handles_are_never_reused_and_a_closed_one_reads_as_gone() {
        let registry = Registry::new();
        let a = registry.insert("a");
        let b = registry.insert("b");
        assert_ne!(0, a);
        assert_ne!(a, b);
        assert_eq!("a", *registry.get(a).unwrap());
        assert!(registry.remove(a));
        assert!(!registry.remove(a));
        assert!(registry.get(a).is_none());
        let c = registry.insert("c");
        assert!(c != a && c != b);
        assert_eq!(2, registry.len());
    }

    #[test]
    fn a_held_object_outlives_its_handle() {
        let registry = Registry::new();
        let handle = registry.insert(vec![1, 2, 3]);
        let held = registry.get(handle).unwrap();
        registry.remove(handle);
        assert_eq!(vec![1, 2, 3], *held);
    }
}
