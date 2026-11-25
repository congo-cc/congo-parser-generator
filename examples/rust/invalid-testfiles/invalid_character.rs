// No Rust token starts with "¤" (a character outside ASCII: the
// offsets count code points).
fn f() { let x = 1 ¤ 2; }
