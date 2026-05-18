// Phase 2: macro invocation in match arm
fn classify(x: i32) -> &'static str {
    match x {
        0 => log!(0),
        _ => "other",
    }
}

macro_rules! log {
    ($v:expr) => { "logged" };
}
