// Phase 1: macro_rules metavar repetition forms
macro_rules! rep {
    ($($x:expr),*) => { $($x),* };
    ($($x:expr),+ ) => { $($x),+ };
    ($($x:expr),? ) => { $($x),? };
}

fn _use_rep() {
    let _ = rep!(1, 2, 3);
}
