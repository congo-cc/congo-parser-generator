// Phase 2: macro invocation as item with semicolon in module
macro_rules! item_mac {
    () => {};
}

item_mac!();

mod inner {
    macro_rules! inner_item {
        () => {};
    }
    inner_item!();
}
