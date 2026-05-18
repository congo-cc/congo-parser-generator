// Phase 1: attribute bodies with delimiter trees (parse-only)
#[derive(Clone, Copy)]
#[cfg_attr(test, derive(Debug))]
#[proc_macro_derive(MyTrait)]
#[proc_macro_attribute]
struct Foo {
    x: i32,
}

#[allow(unused)]
fn bar() {}
