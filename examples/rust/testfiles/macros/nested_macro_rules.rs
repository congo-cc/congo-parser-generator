// Phase 1: macro_rules nested inside another macro's delimiter tree (join.rs style)
macro_rules! outer {
    () => {
        macro_rules! inner {
            ($x:expr) => { $x + 1 };
        }
        fn nested_user() -> i32 {
            inner!(41)
        }
    };
}

outer!();
