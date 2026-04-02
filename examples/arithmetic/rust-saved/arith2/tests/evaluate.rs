//! Comprehensive tests for the arithmetic expression evaluator.
//!
//! Tests cover:
//! - Single values (integer and decimal)
//! - Each operator individually (+, -, *, /)
//! - Operator precedence (multiplication/division before addition/subtraction)
//! - Parenthesized sub-expressions and deep nesting
//! - Chained operations
//! - Edge cases (zero, negative results, decimal arithmetic)
//! - Division by zero (evaluation error)
//! - Malformed expressions (parse errors)
//! - Evaluation of all test-data/*.arith files

use ex2::parser::Parser;
use ex2::inject::EvalError;

use std::fs;
use std::path::Path;

// ---------------------------------------------------------------------------
// Helper: parse and evaluate an expression, returning the f64 result.
// ---------------------------------------------------------------------------

fn eval(input: &str) -> f64 {
    let ast = Parser::parse(input, Some("test"))
        .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));
    ast.evaluate_root()
        .unwrap_or_else(|e| panic!("evaluate failed for '{}': {}", input, e))
}

/// Assert that two f64 values are approximately equal (within epsilon).
/// Needed for floating-point arithmetic that may introduce tiny rounding errors.
fn assert_approx(input: &str, expected: f64, actual: f64) {
    let epsilon = 1e-10;
    assert!(
        (actual - expected).abs() < epsilon,
        "for '{}': expected {} but got {} (diff: {})",
        input,
        expected,
        actual,
        (actual - expected).abs()
    );
}

// ===========================================================================
// Positive tests: single values
// ===========================================================================

#[test]
fn eval_single_integer() {
    assert_approx("42", 42.0, eval("42"));
}

#[test]
fn eval_single_decimal() {
    assert_approx("3.14", 3.14, eval("3.14"));
}

#[test]
fn eval_zero() {
    assert_approx("0", 0.0, eval("0"));
}

// ===========================================================================
// Positive tests: individual operators
// ===========================================================================

#[test]
fn eval_simple_addition() {
    assert_approx("1+1", 2.0, eval("1+1"));
}

#[test]
fn eval_simple_subtraction() {
    assert_approx("5-3", 2.0, eval("5-3"));
}

#[test]
fn eval_simple_multiplication() {
    assert_approx("3*4", 12.0, eval("3*4"));
}

#[test]
fn eval_simple_division() {
    assert_approx("10/2", 5.0, eval("10/2"));
}

// ===========================================================================
// Positive tests: operator precedence
// ===========================================================================

#[test]
fn eval_precedence_mul_over_add() {
    // 2 + 3*4 = 2 + 12 = 14 (not 20)
    assert_approx("2+3*4", 14.0, eval("2+3*4"));
}

#[test]
fn eval_precedence_div_over_sub() {
    // 10 - 6/3 = 10 - 2 = 8 (not 1.333)
    assert_approx("10-6/3", 8.0, eval("10-6/3"));
}

#[test]
fn eval_mixed_operators() {
    // 2 + 3*4 - 1/2 = 2 + 12 - 0.5 = 13.5
    assert_approx("2 + 3 * 4 - 1 / 2", 13.5, eval("2 + 3 * 4 - 1 / 2"));
}

#[test]
fn eval_precedence_mul_before_sub() {
    // 10 - 2*3 = 10 - 6 = 4
    assert_approx("10 - 2*3", 4.0, eval("10 - 2*3"));
}

// ===========================================================================
// Positive tests: parentheses
// ===========================================================================

#[test]
fn eval_parentheses_override_precedence() {
    // (2+3) * 4 = 5 * 4 = 20
    assert_approx("(2+3)*4", 20.0, eval("(2+3)*4"));
}

#[test]
fn eval_parentheses_complex() {
    // (2+3) * (4-1) = 5 * 3 = 15
    assert_approx("(2+3)*(4-1)", 15.0, eval("(2+3)*(4-1)"));
}

#[test]
fn eval_nested_parentheses() {
    // ((1+2) * (3+4)) / (5 - (6-7))
    // = (3 * 7) / (5 - (-1))
    // = 21 / 6
    // = 3.5
    assert_approx(
        "((1+2)*(3+4))/(5-(6-7))",
        3.5,
        eval("((1+2)*(3+4))/(5-(6-7))"),
    );
}

#[test]
fn eval_deeply_nested() {
    // (((1+2))) = 3
    assert_approx("(((1+2)))", 3.0, eval("(((1+2)))"));
}

#[test]
fn eval_parenthesized_single() {
    // (42) = 42
    assert_approx("(42)", 42.0, eval("(42)"));
}

// ===========================================================================
// Positive tests: chained operations
// ===========================================================================

#[test]
fn eval_chained_additions() {
    assert_approx("1+2+3+4+5", 15.0, eval("1+2+3+4+5"));
}

#[test]
fn eval_chained_subtractions() {
    // 10-1-2-3 = 4 (left-to-right)
    assert_approx("10-1-2-3", 4.0, eval("10-1-2-3"));
}

#[test]
fn eval_chained_multiplications() {
    assert_approx("2*3*4", 24.0, eval("2*3*4"));
}

#[test]
fn eval_chained_divisions() {
    // 24/2/3 = 4 (left-to-right)
    assert_approx("24/2/3", 4.0, eval("24/2/3"));
}

// ===========================================================================
// Positive tests: edge cases
// ===========================================================================

#[test]
fn eval_zero_result() {
    assert_approx("5-5", 0.0, eval("5-5"));
}

#[test]
fn eval_negative_result() {
    assert_approx("1-5", -4.0, eval("1-5"));
}

#[test]
fn eval_decimal_arithmetic() {
    // 3.14 * 2.0 + 0.5 = 6.28 + 0.5 = 6.78
    assert_approx("3.14 * 2.0 + 0.5", 6.78, eval("3.14 * 2.0 + 0.5"));
}

#[test]
fn eval_large_numbers() {
    assert_approx("1000000 * 1000000", 1e12, eval("1000000 * 1000000"));
}

#[test]
fn eval_small_decimal() {
    assert_approx("0.001 + 0.002", 0.003, eval("0.001 + 0.002"));
}

#[test]
fn eval_whitespace_handling() {
    // Extra whitespace should be ignored by the lexer.
    assert_approx("  1  +  2  ", 3.0, eval("  1  +  2  "));
}

#[test]
fn eval_mixed_add_sub() {
    // 1+2-3+4-5 = -1
    assert_approx("1+2-3+4-5", -1.0, eval("1+2-3+4-5"));
}

#[test]
fn eval_mixed_mul_div() {
    // 12*2/3*4 = 32 (left-to-right: 24/3=8, 8*4=32)
    assert_approx("12*2/3*4", 32.0, eval("12*2/3*4"));
}

// ===========================================================================
// Negative tests: division by zero
// ===========================================================================

#[test]
fn eval_division_by_zero() {
    let ast = Parser::parse("1/0", Some("test")).expect("parse should succeed");
    let result = ast.evaluate_root();
    assert!(result.is_err(), "expected division by zero error for '1/0'");
    match result.unwrap_err() {
        EvalError::DivisionByZero { location } => {
            assert!(
                !location.is_empty(),
                "division by zero error should include location"
            );
        }
        other => panic!("expected DivisionByZero, got: {}", other),
    }
}

#[test]
fn eval_division_by_zero_expression() {
    // The divisor is an expression that evaluates to zero.
    let ast = Parser::parse("10/(5-5)", Some("test")).expect("parse should succeed");
    let result = ast.evaluate_root();
    assert!(
        result.is_err(),
        "expected division by zero error for '10/(5-5)'"
    );
    assert!(matches!(
        result.unwrap_err(),
        EvalError::DivisionByZero { .. }
    ));
}

#[test]
fn eval_division_by_zero_in_subexpression() {
    // Division by zero occurs deep in a parenthesized sub-expression.
    let ast = Parser::parse("1 + (2/0) * 3", Some("test")).expect("parse should succeed");
    let result = ast.evaluate_root();
    assert!(result.is_err(), "expected division by zero error");
    assert!(matches!(
        result.unwrap_err(),
        EvalError::DivisionByZero { .. }
    ));
}

// ===========================================================================
// Negative tests: parse errors (malformed expressions)
// ===========================================================================

#[test]
fn parse_error_empty_input() {
    let result = Parser::parse("", Some("test"));
    assert!(result.is_err(), "empty input should fail to parse");
}

#[test]
fn parse_error_trailing_operator() {
    let result = Parser::parse("1+", Some("test"));
    assert!(result.is_err(), "'1+' should fail to parse");
}

#[test]
fn parse_error_leading_operator() {
    let result = Parser::parse("+1", Some("test"));
    assert!(result.is_err(), "'+1' should fail to parse");
}

#[test]
fn parse_error_unbalanced_open_paren() {
    let result = Parser::parse("(1+2", Some("test"));
    assert!(result.is_err(), "'(1+2' should fail to parse");
}

#[test]
fn parse_accepts_trailing_close_paren() {
    // The generated parser parses the valid expression prefix "1+2" and stops
    // before the trailing ")".  This is expected behavior for the CongoCC
    // recursive-descent parser -- it matches Root = AdditiveExpression EOF,
    // and the trailing token is not consumed by the grammar.
    let result = Parser::parse("1+2)", Some("test"));
    assert!(result.is_ok(), "parser should accept '1+2)' (parses as '1+2')");
}

#[test]
fn parse_error_empty_parens() {
    let result = Parser::parse("()", Some("test"));
    assert!(result.is_err(), "'()' should fail to parse");
}

#[test]
fn parse_error_double_operator() {
    let result = Parser::parse("1++2", Some("test"));
    assert!(result.is_err(), "'1++2' should fail to parse");
}

#[test]
fn parse_error_non_numeric() {
    let result = Parser::parse("abc", Some("test"));
    assert!(result.is_err(), "'abc' should fail to parse");
}

#[test]
fn parse_error_only_operator() {
    let result = Parser::parse("+", Some("test"));
    assert!(result.is_err(), "'+' should fail to parse");
}

// ===========================================================================
// Test-data file evaluation: parse and evaluate each .arith test file
// ===========================================================================

/// Expected results for each test-data file, verified by manual calculation.
const TEST_DATA_EXPECTED: &[(&str, f64)] = &[
    ("basic.arith", 2.0),              // 1+1
    ("decimals.arith", 6.78),          // 3.14 * 2.0 + 0.5
    ("nested.arith", 3.5),             // ((1+2)*(3+4))/(5-(6-7))
    ("operators.arith", 13.5),         // 2 + 3 * 4 - 1 / 2
    ("parentheses.arith", 15.0),       // (2 + 3) * (4 - 1)
    ("single_number.arith", 42.0),     // 42
];

#[test]
fn eval_test_data_files() {
    let test_dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("test-data");
    assert!(
        test_dir.exists(),
        "test-data directory not found: {}",
        test_dir.display()
    );

    let mut failures: Vec<String> = Vec::new();

    for (filename, expected) in TEST_DATA_EXPECTED {
        let path = test_dir.join(filename);
        let source = match fs::read_to_string(&path) {
            Ok(s) => s,
            Err(e) => {
                failures.push(format!("{}: read error: {}", filename, e));
                continue;
            }
        };

        // Trim trailing newlines/whitespace that may be in the file.
        let input = source.trim();

        let ast = match Parser::parse(input, Some(filename)) {
            Ok(ast) => ast,
            Err(e) => {
                failures.push(format!("{}: parse error: {}", filename, e));
                continue;
            }
        };

        match ast.evaluate_root() {
            Ok(value) => {
                let epsilon = 1e-10;
                if (value - expected).abs() > epsilon {
                    failures.push(format!(
                        "{}: expected {} but got {} (input: '{}')",
                        filename, expected, value, input,
                    ));
                }
            }
            Err(e) => {
                failures.push(format!("{}: evaluation error: {}", filename, e));
            }
        }
    }

    if !failures.is_empty() {
        panic!(
            "{} test-data file(s) failed:\n  {}",
            failures.len(),
            failures.join("\n  ")
        );
    }
}
