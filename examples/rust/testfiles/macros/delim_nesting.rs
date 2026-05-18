// Phase 2: nested delimiters in macro invocations
fn main() {
    let _ = vec![mac!(a { b { c } })];
    let _ = wrap!(one(two(three())));
}

macro_rules! mac {
    ($e:expr) => { $e };
}

macro_rules! wrap {
    ($e:expr) => { $e };
}
