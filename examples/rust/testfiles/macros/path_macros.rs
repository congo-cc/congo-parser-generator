// Phase 1: qualified paths and $crate in macro invocations
mod inner {
    macro_rules! local_mac {
        () => { 1 };
    }
}

fn main() {
    let _ = std::format!("ok");
    let _ = crate::inner::local_mac!();
    let _ = $crate::inner::local_mac!();
}
