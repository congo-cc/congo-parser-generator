// Phase 1: common builtin-style ident! invocations (single-segment path)
fn main() {
    println!("hello");
    let _v = vec![1, 2, 3];
    let _s = format!("x = {}", 1);
    let _inc = include_str!("builtin_macros.rs");
}
