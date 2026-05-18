// Phase 2: intentionally unbalanced — parse should fail (for manual negative testing)
// Run with: java RParse testfiles/macros/unbalanced_fail.rs
// Do not include in ant test (expected failure).

macro_rules! bad {
    () => { ( [ };
}
