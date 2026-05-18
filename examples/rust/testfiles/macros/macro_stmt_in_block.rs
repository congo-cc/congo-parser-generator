// Phase 1: item-level macro stmt vs block tail (MacroInvocationSemi SCAN)
fn block_with_macro() {
    macro_rules! inner { () => {}; }
    inner!();
}

macro_rules! tail_macro { () => { 0 }; }

fn block_tail() {
    tail_macro!()
}
