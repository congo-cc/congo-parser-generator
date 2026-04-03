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
    let ast = Parser::parse("2+3*4", Some("test")).unwrap();
    let formatted = DefaultPrettyPrinter.render_ast(&ast, 80);

    println!("--- Pretty-print of 2+3*4 ---");
    println!("{}", formatted);

    // Check indentation: children should be indented under their parents.
    // The top-level AdditiveExpression should appear with no indentation.
    let lines: Vec<&str> = formatted.lines().collect();
    assert!(
        lines[0].starts_with("AdditiveExpression"),
        "first line should be AdditiveExpression, got: {:?}",
        lines[0]
    );

    // MultiplicativeExpression children should be indented by 2 spaces.
    let mult_lines: Vec<&&str> = lines.iter()
        .filter(|l| l.contains("MultiplicativeExpression"))
        .collect();
    assert!(
        mult_lines.len() >= 2,
        "should have at least 2 MultiplicativeExpression nodes"
    );
    for ml in &mult_lines {
        assert!(
            ml.starts_with("  MultiplicativeExpression"),
            "MultiplicativeExpression should be indented 2 spaces: {:?}",
            ml
        );
    }

    // Token children of a MultiplicativeExpression should be indented 4 spaces.
    let number_lines: Vec<&&str> = lines.iter()
        .filter(|l| l.contains("Token(NUMBER,"))
        .collect();
    assert!(
        !number_lines.is_empty(),
        "should have NUMBER token lines"
    );
    for nl in &number_lines {
        assert!(
            nl.starts_with("    Token("),
            "NUMBER tokens should be indented 4 spaces: {:?}",
            nl
        );
    }
}

#[test]
fn pretty_print_parenthesized() {
    // Verify parenthesized expressions produce deeper nesting.
    let ast = Parser::parse("(2+3)*4", Some("test")).unwrap();
    let formatted = DefaultPrettyPrinter.render_ast(&ast, 80);

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
