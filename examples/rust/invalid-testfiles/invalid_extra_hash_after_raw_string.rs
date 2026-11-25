// Not valid Rust: the raw string ends at the first "#, and rustc rejects the
// second #.  The lexical action of RAW_STRING_LITERAL_HASH in RustLexer.ccc
// sets the end of the token one character too far, so the token includes the
// second # and both the Java and the Rust parser accept this file.
fn f() { let s = r#"a"##; }
