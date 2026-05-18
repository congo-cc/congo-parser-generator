// Phase 4: proc-macro syntax (parse-only; no expansion or TokenStream AST)
use proc_macro::TokenStream;
use proc_macro2::Ident;

// Legacy crate link; parses as ExternCrate like other extern crate items.
extern crate proc_macro;

#[proc_macro]
pub fn bang_macro(input: TokenStream) -> TokenStream {
    input
}

#[proc_macro_derive(MyTrait, attributes(my_attr))]
pub fn derive_trait(input: TokenStream) -> TokenStream {
    input
}

#[proc_macro_attribute]
pub fn attr_macro(_args: TokenStream, input: TokenStream) -> TokenStream {
    input
}

mod paths {
    use ::proc_macro::Literal;
    use super::Ident;
}
