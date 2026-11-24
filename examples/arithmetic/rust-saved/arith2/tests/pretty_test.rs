//! Pretty-print test: demonstrates the built-in pretty-printer.
//!
//! Uses `DefaultPrettyPrinter` from `pretty.rs` to format each parsed
//! expression.  The pretty-printer renders the AST as an indented tree
//! using the Wadler-Lindig algorithm.  Each non-token node shows its
//! kind name (e.g., `AdditiveExpression`) with children indented beneath
//! it.  Token leaves show their type and text (e.g., `Token(NUMBER, "2")`).
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

        // pretty_print() starts from the root node and renders to a
        // string at the default target line width (DEFAULT_WIDTH = 120).
        let formatted = DefaultPrettyPrinter.pretty_print(&ast);

        println!("--- Input: {} ---", input);
        println!("{}", formatted);
        println!();

        // The formatted output should be non-empty for valid expressions.
        assert!(
            !formatted.is_empty(),
            "pretty-print output should not be empty for '{}'",
            input
        );

        // The output should contain AST node kind names, not just raw tokens.
        assert!(
            formatted.contains("AdditiveExpression"),
            "output should contain 'AdditiveExpression' for '{}', got:\n{}",
            input, formatted
        );
        assert!(
            formatted.contains("MultiplicativeExpression"),
            "output should contain 'MultiplicativeExpression' for '{}', got:\n{}",
            input, formatted
        );

        // Token leaves should show their type and text.
        assert!(
            formatted.contains("Token(NUMBER,"),
            "output should contain 'Token(NUMBER,' for '{}', got:\n{}",
            input, formatted
        );
    }
}

#[test]
fn pretty_print_structure() {
    // Verify that "2+3*4" produces the expected indented tree structure.
    //
    // The parser creates nodes as the Java parser does, with smart node
    // creation (the grammar's default): a production's node is only created
    // when it has more than one child.  So the operand "2" is a NUMBER token
    // directly under the AdditiveExpression, while "3*4" is a
    // MultiplicativeExpression.  This is the tree that the Java parser
    // generated from Arithmetic2.ccc builds for the same input.
    let ast = Parser::parse("2+3*4", Some("test")).unwrap();
    let formatted = DefaultPrettyPrinter.pretty_print(&ast);

    println!("--- Pretty-print of 2+3*4 ---");
    println!("{}", formatted);

    let expected = "\
AdditiveExpression
  Token(NUMBER, \"2\")
  Token(PLUS, \"+\")
  MultiplicativeExpression
    Token(NUMBER, \"3\")
    Token(TIMES, \"*\")
    Token(NUMBER, \"4\")
";
    assert_eq!(
        formatted.trim_end(),
        expected.trim_end(),
        "unexpected pretty-print of 2+3*4; each level of the tree must be indented by 2 more spaces"
    );
}

#[test]
fn pretty_print_parenthesized() {
    // Verify parenthesized expressions produce deeper nesting.
    let ast = Parser::parse("(2+3)*4", Some("test")).unwrap();
    let formatted = DefaultPrettyPrinter.pretty_print(&ast);

    println!("--- Pretty-print of (2+3)*4 ---");
    println!("{}", formatted);

    // Should contain ParentheticalExpression for the grouping.
    assert!(
        formatted.contains("ParentheticalExpression"),
        "output should contain 'ParentheticalExpression' for '(2+3)*4'"
    );

    // OPEN_PAREN and CLOSE_PAREN tokens should appear.
    assert!(formatted.contains("Token(OPEN_PAREN,"), "should have OPEN_PAREN");
    assert!(formatted.contains("Token(CLOSE_PAREN,"), "should have CLOSE_PAREN");
}
