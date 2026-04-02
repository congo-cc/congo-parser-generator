//! Tests for the ex1 (Arithmetic1) evaluation stub.
//!
//! The ex1 grammar does not define evaluation logic.  These tests verify that:
//! 1. evaluate() and evaluate_root() correctly return EvalError::Unsupported.
//! 2. Parsing still works correctly (no regression from adding the stub).

use ex1::parser::Parser;
use ex1::inject::EvalError;

// ===========================================================================
// evaluate() must return Unsupported
// ===========================================================================

#[test]
fn evaluate_root_returns_unsupported() {
    let ast = Parser::parse("1+1", Some("test"))
        .expect("parse should succeed for '1+1'");
    let result = ast.evaluate_root();
    assert!(result.is_err(), "ex1 evaluate_root() should return an error");
    match result.unwrap_err() {
        EvalError::Unsupported(msg) => {
            assert!(
                msg.contains("ex2") || msg.contains("rust-arith2"),
                "error message should direct user to ex2/rust-arith2, got: {}",
                msg
            );
        }
    }
}

#[test]
fn evaluate_node_returns_unsupported() {
    let ast = Parser::parse("42", Some("test"))
        .expect("parse should succeed for '42'");
    let root = ast.root().expect("AST should have a root node");
    let result = ast.evaluate(root);
    assert!(result.is_err(), "ex1 evaluate() should return an error");
    assert!(matches!(result.unwrap_err(), EvalError::Unsupported(_)));
}

// ===========================================================================
// Parsing regression tests: verify that adding the stub didn't break parsing
// ===========================================================================

#[test]
fn parse_basic_expression() {
    let ast = Parser::parse("1+1", Some("test"));
    assert!(ast.is_ok(), "should parse '1+1'");
    let ast = ast.unwrap();
    assert!(ast.root().is_some(), "AST should have a root node");
    assert!(ast.node_count() > 0, "AST should have nodes");
}

#[test]
fn parse_complex_expression() {
    let ast = Parser::parse("(2+3)*(4-1)", Some("test"));
    assert!(ast.is_ok(), "should parse '(2+3)*(4-1)'");
}

#[test]
fn parse_decimal_expression() {
    let ast = Parser::parse("3.14 * 2.0 + 0.5", Some("test"));
    assert!(ast.is_ok(), "should parse '3.14 * 2.0 + 0.5'");
}

#[test]
fn parse_nested_expression() {
    let ast = Parser::parse("((1+2)*(3+4))/(5-(6-7))", Some("test"));
    assert!(ast.is_ok(), "should parse nested expression");
}

#[test]
fn parse_error_empty_input() {
    let result = Parser::parse("", Some("test"));
    assert!(result.is_err(), "empty input should fail to parse");
}

#[test]
fn parse_error_invalid_syntax() {
    let result = Parser::parse("1++2", Some("test"));
    assert!(result.is_err(), "'1++2' should fail to parse");
}
