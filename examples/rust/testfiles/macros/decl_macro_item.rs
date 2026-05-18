// Phase 3: declarative `macro` items (macros 2.0 / decl_macro surface)
macro m {
    () => {}
}

pub(crate) macro exported {
    () => { 1 }
}

#[macro_export]
pub macro with_attr {
    ($x:expr) => { $x };
}

mod inner {
    macro nested {
        () => {}
    }
}
