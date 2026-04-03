//! Pretty-print test: demonstrates the built-in pretty-printer.
//!
//! Uses `DefaultPrettyPrinter` from `pretty.rs` to format each parsed
//! expression.  The pretty-printer renders the AST back to text using
//! the Wadler-Lindig algorithm, which inserts line breaks and indentation
//! when the output exceeds a target width.
//!
//! Run with output:
//!     cargo test --test pretty_test -- --nocapture

use ex2::parser::Parser;
use ex2::pretty::{DefaultPrettyPrinter, PrettyPrinter};

/// The three standard test expressions used across all traversal tests.
const INPUTS: &[&str] = &[
    "2+3*4",
    "(2+3)*4",
    "((1+2)*(3+4))/(5-(6-7))",
];

#[test]
fn pretty_print_expressions() {
    for input in INPUTS {
        let ast = Parser::parse(input, Some("test"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));

        // render_ast() starts from the root node and renders to a string.
        // The width parameter (80) controls where soft line breaks occur.
        let formatted = DefaultPrettyPrinter.render_ast(&ast, 80);

        println!("--- Input: {} ---", input);
        println!("{}", formatted);
        println!();

        // The formatted output should be non-empty for valid expressions.
        assert!(
            !formatted.is_empty(),
            "pretty-print output should not be empty for '{}'",
            input
        );
    }
}

#[test]
fn pretty_print_narrow_width() {
    // Demonstrate how a narrow width forces more line breaks.
    let input = "((1+2)*(3+4))/(5-(6-7))";
    let ast = Parser::parse(input, Some("test")).unwrap();

    let wide = DefaultPrettyPrinter.render_ast(&ast, 80);
    let narrow = DefaultPrettyPrinter.render_ast(&ast, 20);

    println!("--- Width 80 ---");
    println!("{}", wide);
    println!("--- Width 20 ---");
    println!("{}", narrow);

    // Both should contain the source tokens regardless of width.
    assert!(wide.contains("1") && wide.contains("+") && wide.contains("2"));
    assert!(narrow.contains("1") && narrow.contains("+") && narrow.contains("2"));
}
