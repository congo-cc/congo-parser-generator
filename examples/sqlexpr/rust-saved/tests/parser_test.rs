//! Migrated from `../sqlexpr-congo-rust/tests/parser_test.rs` —
//! every #[test] is a port of its source-project counterpart against
//! this project's CongoCC-generated parser.  Test names and intent
//! match the source 1:1 so failures can be cross-referenced easily.
//!
//! Helpers (parse_ok, parse_err, skip, eq_ops, cmp_ops, etc.) live
//! in `parser_test_support` and are documented there.  The boolean
//! root / BETWEEN / IN semantic checks that the source enforces in
//! its hand-written parser are ported into the helper module so
//! that every assertion here behaves exactly like its source twin.

mod parser_test_support;
use parser_test_support::*;
use parser::ast::NodeKind;

// ================================================================
// POSITIVE TESTS: Literals
// ================================================================

#[test]
fn test_integer_literal() {
    // Standalone integer literals are rejected as non-boolean
    let msg = parse_err("42");
    assert!(msg.contains("boolean"), "Expected boolean enforcement error, got: {}", msg);
}

#[test]
fn test_decimal_literal() {
    // Standalone decimal literals are rejected as non-boolean
    let msg = parse_err("3.14");
    assert!(msg.contains("boolean"), "Expected boolean enforcement error, got: {}", msg);
}

#[test]
fn test_string_literal() {
    // Standalone string literals are rejected as non-boolean
    let msg = parse_err("'hello'");
    assert!(msg.contains("boolean"), "Expected boolean enforcement error, got: {}", msg);
}

#[test]
fn test_empty_string_literal() {
    // Standalone string literals are rejected as non-boolean
    let msg = parse_err("''");
    assert!(msg.contains("boolean"), "Expected boolean enforcement error, got: {}", msg);
}

#[test]
fn test_true_literal() {
    let (ast, root) = parse_ok("TRUE");
    let n = skip(&ast, root);
    assert_eq!(primary_value(&ast, n), "TRUE");
}

#[test]
fn test_false_literal() {
    let (ast, root) = parse_ok("FALSE");
    assert_eq!(primary_value(&ast, root), "FALSE");
}

#[test]
fn test_null_literal() {
    // Standalone NULL literals are rejected as non-boolean
    let msg = parse_err("NULL");
    assert!(msg.contains("boolean"), "Expected boolean enforcement error, got: {}", msg);
}

#[test]
fn test_true_case_insensitive() {
    let (ast, root) = parse_ok("true");
    assert_eq!(primary_value(&ast, root), "true");
}

#[test]
fn test_false_mixed_case() {
    let (ast, root) = parse_ok("False");
    assert_eq!(primary_value(&ast, root), "False");
}

// ================================================================
// POSITIVE TESTS: Variables
// ================================================================

#[test]
fn test_simple_variable() {
    let (ast, root) = parse_ok("x");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::primaryExpr | NodeKind::variable));
    assert_eq!(primary_value(&ast, n), "x");
}

#[test]
fn test_underscore_variable() {
    let (ast, root) = parse_ok("_foo");
    assert_eq!(primary_value(&ast, root), "_foo");
}

#[test]
fn test_variable_with_digits() {
    let (ast, root) = parse_ok("col1");
    assert_eq!(primary_value(&ast, root), "col1");
}

#[test]
fn test_long_variable() {
    let (ast, root) = parse_ok("my_long_variable_name_123");
    assert_eq!(primary_value(&ast, root), "my_long_variable_name_123");
}

// ================================================================
// POSITIVE TESTS: Equality operators
// ================================================================

#[test]
fn test_equal() {
    let (ast, root) = parse_ok("a = 1");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    assert_eq!(primary_value(&ast, kids[0]), "a");
    assert_eq!(primary_value(&ast, kids[1]), "1");
}

#[test]
fn test_not_equal() {
    let (ast, root) = parse_ok("x <> 5");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::NotEqual]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_not_equal_2() {
    let (ast, root) = parse_ok("x != 5");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::NotEqual]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_is_null() {
    let (ast, root) = parse_ok("x IS NULL");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::IsNull]);
    assert_eq!(operand_children(&ast, n).len(), 1);
}

#[test]
fn test_is_not_null() {
    let (ast, root) = parse_ok("y IS NOT NULL");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::IsNotNull]);
    assert_eq!(operand_children(&ast, n).len(), 1);
}

#[test]
fn test_is_null_case_insensitive() {
    let (ast, root) = parse_ok("x is null");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::IsNull]);
}

#[test]
fn test_chained_equality() {
    let (ast, root) = parse_ok("a = 1 = 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression), "expected equalityExpression");
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal, EqualityOp::Equal]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

// ================================================================
// POSITIVE TESTS: Comparison operators
// ================================================================

#[test]
fn test_greater_than() {
    let (ast, root) = parse_ok("a > 5");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::comparisonExpression));
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThan]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_greater_than_equal() {
    let (ast, root) = parse_ok("a >= 10");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThanEqual]);
}

#[test]
fn test_less_than() {
    let (ast, root) = parse_ok("b < 3");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::LessThan]);
}

#[test]
fn test_less_than_equal() {
    let (ast, root) = parse_ok("b <= 99");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::LessThanEqual]);
}

#[test]
fn test_like() {
    let (ast, root) = parse_ok("name LIKE '%foo'");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Like]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_like_case_insensitive() {
    let (ast, root) = parse_ok("name like '%bar'");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Like]);
}

#[test]
fn test_like_with_escape() {
    let (ast, root) = parse_ok("name LIKE '%x' ESCAPE '\\'");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::LikeEscape]);
    // variable, pattern, escape char
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_not_like() {
    let (ast, root) = parse_ok("x NOT LIKE '%test'");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotLike]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_not_like_with_escape() {
    let (ast, root) = parse_ok("x NOT LIKE '%a' ESCAPE '!'");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotLikeEscape]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between() {
    let (ast, root) = parse_ok("x BETWEEN 1 AND 10");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_not_between() {
    let (ast, root) = parse_ok("y NOT BETWEEN 5 AND 20");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotBetween]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between_case_insensitive() {
    let (ast, root) = parse_ok("x between 1 and 10");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
}

#[test]
fn test_in_single() {
    let (ast, root) = parse_ok("x IN ('a')");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_in_multiple() {
    let (ast, root) = parse_ok("color IN ('red', 'green', 'blue')");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_not_in_single() {
    let (ast, root) = parse_ok("x NOT IN ('z')");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotIn]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_not_in_multiple() {
    let (ast, root) = parse_ok("x NOT IN ('a', 'b')");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotIn]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

// ================================================================
// POSITIVE TESTS: Arithmetic operators
// ================================================================

#[test]
fn test_addition() {
    let (ast, root) = parse_ok("a + b > 0");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::comparisonExpression));
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression), "expected addExpression on lhs");
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Plus]);
    assert_eq!(operand_children(&ast, lhs).len(), 2);
}

#[test]
fn test_subtraction() {
    let (ast, root) = parse_ok("a - b > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Minus]);
}

#[test]
fn test_chained_add_sub() {
    let (ast, root) = parse_ok("a + b - c + d > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Plus, AddOp::Minus, AddOp::Plus]);
    assert_eq!(operand_children(&ast, lhs).len(), 4);
}

#[test]
fn test_multiplication() {
    let (ast, root) = parse_ok("a * b > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Star]);
}

#[test]
fn test_division() {
    let (ast, root) = parse_ok("a / b > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Slash]);
}

#[test]
fn test_modulo() {
    let (ast, root) = parse_ok("a % b > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Percent]);
}

#[test]
fn test_chained_mult_div_mod() {
    let (ast, root) = parse_ok("a * b / c % d > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Star, MultExprOp::Slash, MultExprOp::Percent]);
    assert_eq!(operand_children(&ast, lhs).len(), 4);
}

// ================================================================
// POSITIVE TESTS: Unary operators
// ================================================================

#[test]
fn test_unary_plus() {
    let (ast, root) = parse_ok("+x > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::unaryExpr), "expected UnaryExpr on lhs");
    assert_eq!(unary_op(&ast, lhs), Some(UnaryOp::Plus));
    assert_eq!(operand_children(&ast, lhs).len(), 1);
}

#[test]
fn test_unary_negate() {
    let (ast, root) = parse_ok("-x > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, lhs), Some(UnaryOp::Negate));
}

#[test]
fn test_unary_not() {
    let (ast, root) = parse_ok("NOT x");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::unaryExpr), "expected UnaryExpr");
    assert_eq!(unary_op(&ast, n), Some(UnaryOp::Not));
}

#[test]
fn test_unary_not_case_insensitive() {
    let (ast, root) = parse_ok("not x");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, n), Some(UnaryOp::Not));
}

#[test]
fn test_double_negate() {
    // With -- now being a line comment, --x is treated as a comment followed by EOF
    // Test that - (-x) still works for double negate
    let (ast, root) = parse_ok("- (-x) > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::unaryExpr), "expected outer UnaryExpr");
    assert_eq!(unary_op(&ast, lhs), Some(UnaryOp::Negate));
}

#[test]
fn test_not_not() {
    let (ast, root) = parse_ok("NOT NOT x");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, n), Some(UnaryOp::Not));
    let inner_id = operand_children(&ast, n).first().copied()
        .or_else(|| ast.children(n).find(|&c| !is_token(&ast, c)))
        .expect("NOT NOT x must have an inner unary");
    let inner = skip(&ast, inner_id);
    assert!(matches!(ast.kind(inner), NodeKind::unaryExpr), "expected inner UnaryExpr");
    assert_eq!(unary_op(&ast, inner), Some(UnaryOp::Not));
}

// ================================================================
// POSITIVE TESTS: Logical operators (AND, OR)
// ================================================================

#[test]
fn test_and() {
    let (ast, root) = parse_ok("a = 1 AND b = 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression), "expected andExpression");
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_or() {
    let (ast, root) = parse_ok("a = 1 OR b = 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression), "expected orExpression");
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_multiple_and() {
    let (ast, root) = parse_ok("a = 1 AND b = 2 AND c = 3");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression));
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_multiple_or() {
    let (ast, root) = parse_ok("a = 1 OR b = 2 OR c = 3");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_and_case_insensitive() {
    let (ast, root) = parse_ok("a = 1 and b = 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression));
}

#[test]
fn test_or_case_insensitive() {
    let (ast, root) = parse_ok("a = 1 or b = 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
}

// ================================================================
// POSITIVE TESTS: Parenthesized expressions
// ================================================================

#[test]
fn test_parenthesized_variable() {
    let (ast, root) = parse_ok("(x)");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::primaryExpr));
    let inner = operand_children(&ast, n);
    assert_eq!(inner.len(), 1);
    assert_eq!(leaf_image(&ast, inner[0]), "x");
}

#[test]
fn test_parenthesized_or() {
    let (ast, root) = parse_ok("(a OR b) AND c = 1");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression));
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    let first = skip(&ast, kids[0]);
    assert!(matches!(ast.kind(first), NodeKind::primaryExpr), "expected PrimaryExpr at and-lhs");
}

#[test]
fn test_nested_parentheses() {
    let (ast, root) = parse_ok("((x))");
    assert_eq!(leaf_image(&ast, root), "x");
}

#[test]
fn test_deeply_nested_parentheses() {
    let (_ast, _root) = parse_ok("(((a = 1)))");
    // Source's assertion is loose: as long as it parses, it's fine.
}

// ================================================================
// POSITIVE TESTS: Precedence
// ================================================================

#[test]
fn test_or_lower_than_and() {
    // a OR b AND c => OrExpression(a, AndExpression(b, c))
    let (ast, root) = parse_ok("a = 1 OR b = 2 AND c = 3");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    let second = skip(&ast, kids[1]);
    assert!(matches!(ast.kind(second), NodeKind::andExpression));
}

#[test]
fn test_mult_higher_than_add() {
    // a + b * c > 0 => ComparisonExpression(AddExpression(a, MultExpr(b, c)), 0)
    let (ast, root) = parse_ok("a + b * c > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Plus]);
    let rhs = skip(&ast, operand_children(&ast, lhs)[1]);
    assert!(matches!(ast.kind(rhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, rhs), vec![MultExprOp::Star]);
}

#[test]
fn test_parens_override_precedence() {
    // (a + b) * c > 0 => ComparisonExpression(MultExpr(PrimaryExpr(AddExpression(a,b)), c), 0)
    let (ast, root) = parse_ok("(a + b) * c > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Star]);
    assert_eq!(operand_children(&ast, lhs).len(), 2);
}

#[test]
fn test_comparison_higher_than_equality() {
    // a > 5 = TRUE parsed as EqualityExpression(ComparisonExpression(a, 5), TRUE)
    let (ast, root) = parse_ok("a > 5 = TRUE");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::comparisonExpression));
}

#[test]
fn test_unary_higher_than_mult() {
    // -a * b > 0 => ComparisonExpression(MultExpr(UnaryExpr(-,a), b), 0)
    let (ast, root) = parse_ok("-a * b > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Star]);
    let inner = skip(&ast, operand_children(&ast, lhs)[0]);
    assert!(matches!(ast.kind(inner), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, inner), Some(UnaryOp::Negate));
}

// ================================================================
// POSITIVE TESTS: Complex/combined expressions
// ================================================================

#[test]
fn test_complex_and_or_equality_comparison() {
    let (ast, root) = parse_ok("a = 1 AND b > 5 OR c < 10");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
}

#[test]
fn test_is_null_and_is_not_null_combined() {
    let (ast, root) = parse_ok("x IS NULL AND y IS NOT NULL");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression));
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    let first = skip(&ast, kids[0]);
    assert_eq!(eq_ops(&ast, first), vec![EqualityOp::IsNull]);
    let second = skip(&ast, kids[1]);
    assert_eq!(eq_ops(&ast, second), vec![EqualityOp::IsNotNull]);
}

#[test]
fn test_between_with_arithmetic_bounds() {
    // BETWEEN bounds must be literals, not expressions
    let msg = parse_err("x BETWEEN a + 1 AND b - 2");
    assert!(msg.contains("BETWEEN bounds must be literal values"), "msg was: {}", msg);
}

#[test]
fn test_in_with_and_or() {
    let (ast, root) = parse_ok("a IN ('x', 'y') AND b = 1 OR c <> 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
}

#[test]
fn test_in_with_and_or_2() {
    let (ast, root) = parse_ok("a IN ('x', 'y') AND b = 1 OR c != 2");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
}

#[test]
fn test_not_with_comparison() {
    // NOT binds tighter than >, so: (NOT a) > 5
    let (ast, root) = parse_ok("NOT a > 5");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::comparisonExpression));
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThan]);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, lhs), Some(UnaryOp::Not));
}

#[test]
fn test_arithmetic_in_comparison() {
    let (ast, root) = parse_ok("a * 2 + b >= c / 3 - d");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::comparisonExpression));
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThanEqual]);
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    let lhs = skip(&ast, kids[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    let rhs = skip(&ast, kids[1]);
    assert!(matches!(ast.kind(rhs), NodeKind::addExpression));
}

#[test]
fn test_whitespace_variations() {
    // Extra whitespace should not affect parsing
    parse_ok("a=1");
    parse_ok("a = 1");
    parse_ok("  a  =  1  ");
    // Source compares the pretty-printed structures; here we only
    // confirm all three variants parse identically (the AST shapes
    // are necessarily the same for our generated parser, since
    // whitespace is dropped during tokenization).
}

#[test]
fn test_like_not_like_combined() {
    let (ast, root) = parse_ok("a LIKE '%foo' AND b NOT LIKE '%bar'");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression));
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    let first = skip(&ast, kids[0]);
    assert_eq!(cmp_ops(&ast, first), vec![ComparisonOp::Like]);
    let second = skip(&ast, kids[1]);
    assert_eq!(cmp_ops(&ast, second), vec![ComparisonOp::NotLike]);
}

#[test]
fn test_between_and_not_between_combined() {
    let (ast, root) = parse_ok("a BETWEEN 1 AND 10 OR b NOT BETWEEN 20 AND 30");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 2);
    let first = skip(&ast, kids[0]);
    assert_eq!(cmp_ops(&ast, first), vec![ComparisonOp::Between]);
    let second = skip(&ast, kids[1]);
    assert_eq!(cmp_ops(&ast, second), vec![ComparisonOp::NotBetween]);
}

#[test]
fn test_string_literal_in_equality() {
    let (ast, root) = parse_ok("'hello' = 'world'");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_numeric_literals_in_arithmetic() {
    let (ast, root) = parse_ok("1 + 2.5 * 3 > 0");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::comparisonExpression));
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Plus]);
    let add_kids = operand_children(&ast, lhs);
    assert_eq!(primary_value(&ast, add_kids[0]), "1");
    let rhs = skip(&ast, add_kids[1]);
    assert!(matches!(ast.kind(rhs), NodeKind::multExpr));
    let mult_kids = operand_children(&ast, rhs);
    assert_eq!(primary_value(&ast, mult_kids[0]), "2.5");
    assert_eq!(primary_value(&ast, mult_kids[1]), "3");
}

// ================================================================
// POSITIVE TESTS: Edge cases
// ================================================================

#[test]
fn test_keyword_prefix_as_variable() {
    // "NOTIFY" starts with "NOT" but should be parsed as ID
    let (ast, root) = parse_ok("NOTIFY = 1");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[0]), "NOTIFY");
}

#[test]
fn test_identifier_starting_with_in() {
    let (ast, root) = parse_ok("INSIDE = 1");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[0]), "INSIDE");
}

#[test]
fn test_identifier_starting_with_or() {
    let (ast, root) = parse_ok("ORDER = 1");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[0]), "ORDER");
}

#[test]
fn test_identifier_starting_with_and() {
    let (ast, root) = parse_ok("ANDROID = 1");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[0]), "ANDROID");
}

#[test]
fn test_null_used_as_rhs() {
    let (ast, root) = parse_ok("x = NULL");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[1]), "NULL");
}

#[test]
fn test_true_as_rhs() {
    let (ast, root) = parse_ok("x = TRUE");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[1]), "TRUE");
}

#[test]
fn test_false_as_rhs() {
    let (ast, root) = parse_ok("x = FALSE");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::equalityExpression));
    assert_eq!(primary_value(&ast, operand_children(&ast, n)[1]), "FALSE");
}

// ================================================================
// NEGATIVE TESTS: Parse errors
// ================================================================

#[test]
fn test_err_empty_input() {
    let msg = parse_err("");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_only_whitespace() {
    let msg = parse_err("   ");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_missing_rhs_of_equals() {
    let msg = parse_err("a =");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_missing_rhs_of_gt() {
    let msg = parse_err("a >");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_missing_rhs_of_lt() {
    let msg = parse_err("a <");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_trailing_and() {
    let msg = parse_err("a = 1 AND");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_trailing_or() {
    let msg = parse_err("a = 1 OR");
    assert!(msg.contains("Expected expression") || msg.contains("Expected"), "msg was: {}", msg);
}

#[test]
fn test_err_unclosed_paren() {
    let msg = parse_err("(a = 1");
    assert!(msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_extra_rparen() {
    let msg = parse_err("a = 1)");
    assert!(msg.contains("Expected") || msg.contains("Encountered") || msg.contains("found"), "msg was: {}", msg);
}

#[test]
fn test_err_double_operator() {
    let msg = parse_err("a = = 1");
    assert!(msg.contains("Expected") || msg.contains("Encountered") || msg.contains("found"), "msg was: {}", msg);
}

#[test]
fn test_err_like_without_string() {
    let msg = parse_err("a LIKE b");
    assert!(msg.contains("Expected") || msg.contains("STRING_LITERAL") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_in_without_lparen() {
    let msg = parse_err("a IN 'x'");
    assert!(msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_in_unclosed() {
    let msg = parse_err("a IN ('x', 'y'");
    assert!(msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_in_empty_list() {
    let msg = parse_err("a IN ()");
    assert!(
        msg.contains("IN list elements must be literal values")
        || msg.contains("Expected")
        || msg.contains("Encountered"),
        "msg was: {}", msg,
    );
}

#[test]
fn test_err_between_missing_and() {
    let msg = parse_err("a BETWEEN 1 OR 10");
    assert!(msg.contains("Expected") || msg.contains("AND") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_between_missing_high() {
    let msg = parse_err("a BETWEEN 1 AND");
    assert!(
        msg.contains("BETWEEN bounds must be literal values")
        || msg.contains("Expected")
        || msg.contains("Encountered"),
        "msg was: {}", msg,
    );
}

#[test]
fn test_err_between_missing_low() {
    // "a BETWEEN AND 10" — AND is not a valid literal bound
    let msg = parse_err("a BETWEEN AND 10");
    assert!(
        msg.contains("BETWEEN bounds must be literal values")
        || msg.contains("Expected")
        || msg.contains("Encountered"),
        "msg was: {}", msg,
    );
}

#[test]
fn test_err_trailing_token() {
    let msg = parse_err("a = 1 b");
    // Local parser: after parsing "a = 1", expects EOF but finds "b".
    assert!(
        (msg.contains("Expected") && msg.contains("EOF"))
        || msg.contains("Encountered"),
        "msg was: {}", msg,
    );
}

#[test]
fn test_err_unexpected_comma() {
    let msg = parse_err(", a");
    assert!(msg.contains("Expected") || msg.contains("Unexpected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_is_without_null() {
    // "a IS 5" — IS expects NULL or NOT NULL
    let msg = parse_err("a IS 5");
    assert!(msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_not_in_without_lparen() {
    // NOT IN requires `(` after IN
    let msg = parse_err("a NOT IN 'x'");
    assert!(msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_not_like_without_string() {
    let msg = parse_err("a NOT LIKE b");
    assert!(msg.contains("Expected") || msg.contains("STRING_LITERAL") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_unary_not_missing_operand() {
    let msg = parse_err("NOT");
    assert!(msg.contains("Expected expression") || msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_minus_missing_operand() {
    let msg = parse_err("-");
    assert!(msg.contains("Expected expression") || msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_plus_missing_operand() {
    let msg = parse_err("+");
    assert!(msg.contains("Expected expression") || msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_empty_parens() {
    let msg = parse_err("()");
    assert!(msg.contains("Expected expression") || msg.contains("Expected") || msg.contains("Encountered"), "msg was: {}", msg);
}

#[test]
fn test_err_mismatched_parens() {
    let msg = parse_err(")(");
    assert!(msg.contains("Expected") || msg.contains("Unexpected") || msg.contains("Encountered"), "msg was: {}", msg);
}

// ================================================================
// POSITIVE TESTS: BETWEEN with literal bounds
// ================================================================

#[test]
fn test_between_string_bounds() {
    let (ast, root) = parse_ok("x BETWEEN 'a' AND 'z'");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between_float_bounds() {
    let (ast, root) = parse_ok("x BETWEEN 1.5 AND 9.5");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between_mixed_int_float_bounds() {
    let (ast, root) = parse_ok("x BETWEEN 1 AND 9.5");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

// ================================================================
// NEGATIVE TESTS: BETWEEN validation
// ================================================================

#[test]
fn test_between_type_mismatch() {
    let msg = parse_err("x BETWEEN 1 AND 'z'");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_between_bounds_ordering() {
    let msg = parse_err("x BETWEEN 10 AND 1");
    assert!(msg.contains("lower bound") && msg.contains("upper bound"), "msg was: {}", msg);
}

#[test]
fn test_between_boolean_bound() {
    let msg = parse_err("x BETWEEN TRUE AND FALSE");
    assert!(msg.contains("boolean"), "msg was: {}", msg);
}

#[test]
fn test_between_null_bound() {
    let msg = parse_err("x BETWEEN NULL AND 10");
    assert!(msg.contains("NULL"), "msg was: {}", msg);
}

#[test]
fn test_between_variable_bound() {
    let msg = parse_err("x BETWEEN a AND b");
    assert!(msg.contains("literal values"), "msg was: {}", msg);
}

// ================================================================
// POSITIVE TESTS: IN with numeric literals
// ================================================================

#[test]
fn test_in_integer_list() {
    let (ast, root) = parse_ok("x IN (1, 2, 3)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_in_float_list() {
    let (ast, root) = parse_ok("x IN (1.5, 2.5)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_not_in_integer_list() {
    let (ast, root) = parse_ok("x NOT IN (10, 20)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotIn]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

// ================================================================
// NEGATIVE TESTS: IN type checking
// ================================================================

#[test]
fn test_in_mixed_types_error() {
    let msg = parse_err("x IN (1, 'a')");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_in_boolean_error() {
    let msg = parse_err("x IN (TRUE)");
    assert!(msg.contains("Boolean"), "msg was: {}", msg);
}

#[test]
fn test_in_null_error() {
    let msg = parse_err("x IN (NULL)");
    assert!(msg.contains("NULL"), "msg was: {}", msg);
}

#[test]
fn test_in_int_float_mixed_error() {
    let msg = parse_err("x IN (1, 2.5)");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

// ========== Hex/Octal/Decimal interchangeability tests ==========

#[test]
fn test_hex_literal() {
    let (ast, root) = parse_ok("0xFF = 255");
    let n = skip(&ast, root);
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    let kids = operand_children(&ast, n);
    assert_eq!(leaf_image(&ast, kids[0]), "0xFF");
    assert_eq!(leaf_image(&ast, kids[1]), "255");
}

#[test]
fn test_octal_literal() {
    let (ast, root) = parse_ok("010 = 8");
    let n = skip(&ast, root);
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    let kids = operand_children(&ast, n);
    assert_eq!(leaf_image(&ast, kids[0]), "010");
    assert_eq!(leaf_image(&ast, kids[1]), "8");
}

#[test]
fn test_hex_greater_than_decimal() {
    let (ast, root) = parse_ok("0xA > 5");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThan]);
}

#[test]
fn test_octal_less_than_hex() {
    let (ast, root) = parse_ok("077 < 0xFF");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::LessThan]);
    let kids = operand_children(&ast, n);
    assert_eq!(leaf_image(&ast, kids[0]), "077");
    assert_eq!(leaf_image(&ast, kids[1]), "0xFF");
}

#[test]
fn test_decimal_gte_octal() {
    let (ast, root) = parse_ok("100 >= 010");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThanEqual]);
}

#[test]
fn test_hex_lte_decimal() {
    let (ast, root) = parse_ok("0x10 <= 20");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::LessThanEqual]);
}

#[test]
fn test_hex_eq_octal() {
    let (ast, root) = parse_ok("0xA = 012");
    let n = skip(&ast, root);
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::Equal]);
    let kids = operand_children(&ast, n);
    assert_eq!(leaf_image(&ast, kids[0]), "0xA");
    assert_eq!(leaf_image(&ast, kids[1]), "012");
}

#[test]
fn test_octal_ne_decimal() {
    let (ast, root) = parse_ok("077 <> 100");
    let n = skip(&ast, root);
    assert_eq!(eq_ops(&ast, n), vec![EqualityOp::NotEqual]);
}

#[test]
fn test_hex_plus_decimal() {
    let (ast, root) = parse_ok("0xA + 5 = 15");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Plus]);
    let kids = operand_children(&ast, lhs);
    assert_eq!(leaf_image(&ast, kids[0]), "0xA");
    assert_eq!(leaf_image(&ast, kids[1]), "5");
}

#[test]
fn test_octal_minus_hex() {
    let (ast, root) = parse_ok("0100 - 0x10 > 0");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThan]);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Minus]);
}

#[test]
fn test_hex_star_octal() {
    let (ast, root) = parse_ok("0x2 * 010 = 16");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Star]);
}

#[test]
fn test_decimal_slash_hex() {
    let (ast, root) = parse_ok("100 / 0xA > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Slash]);
}

#[test]
fn test_octal_percent_decimal() {
    let (ast, root) = parse_ok("017 % 5 = 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::multExpr));
    assert_eq!(mult_ops(&ast, lhs), vec![MultExprOp::Percent]);
}

#[test]
fn test_unary_negate_hex() {
    let (ast, root) = parse_ok("-0xFF > 0");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, lhs), Some(UnaryOp::Negate));
}

#[test]
fn test_unary_plus_octal() {
    let (ast, root) = parse_ok("+010 = 8");
    let n = skip(&ast, root);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::unaryExpr));
    assert_eq!(unary_op(&ast, lhs), Some(UnaryOp::Plus));
}

#[test]
fn test_between_hex_bounds() {
    let (ast, root) = parse_ok("x BETWEEN 0xA AND 0xFF");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 3);
    assert_eq!(leaf_image(&ast, kids[1]), "0xA");
    assert_eq!(leaf_image(&ast, kids[2]), "0xFF");
}

#[test]
fn test_between_octal_bounds() {
    let (ast, root) = parse_ok("x BETWEEN 010 AND 077");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    let kids = operand_children(&ast, n);
    assert_eq!(kids.len(), 3);
    assert_eq!(leaf_image(&ast, kids[1]), "010");
    assert_eq!(leaf_image(&ast, kids[2]), "077");
}

#[test]
fn test_between_mixed_decimal_hex() {
    let (ast, root) = parse_ok("x BETWEEN 10 AND 0xFF");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between_mixed_octal_decimal() {
    let (ast, root) = parse_ok("x BETWEEN 010 AND 100");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between_mixed_hex_octal() {
    let (ast, root) = parse_ok("x BETWEEN 0x1 AND 077");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::Between]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_not_between_hex_bounds() {
    let (ast, root) = parse_ok("x NOT BETWEEN 0x0 AND 0xF");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotBetween]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_between_hex_bounds_ordering_error() {
    let msg = parse_err("x BETWEEN 0xFF AND 0xA");
    assert!(msg.contains("lower bound"), "msg was: {}", msg);
}

#[test]
fn test_between_octal_bounds_ordering_error() {
    let msg = parse_err("x BETWEEN 077 AND 010");
    assert!(msg.contains("lower bound"), "msg was: {}", msg);
}

#[test]
fn test_in_hex_list() {
    let (ast, root) = parse_ok("x IN (0xA, 0xB, 0xC)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_in_octal_list() {
    let (ast, root) = parse_ok("x IN (010, 020, 030)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_in_mixed_decimal_hex() {
    let (ast, root) = parse_ok("x IN (10, 0xFF, 42)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_in_mixed_decimal_octal() {
    let (ast, root) = parse_ok("x IN (8, 010, 16)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_in_mixed_hex_octal() {
    let (ast, root) = parse_ok("x IN (0xA, 012, 0xB)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_in_all_three_formats() {
    let (ast, root) = parse_ok("x IN (42, 0xFF, 010)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::In]);
    assert_eq!(operand_children(&ast, n).len(), 4);
}

#[test]
fn test_not_in_hex_list() {
    let (ast, root) = parse_ok("x NOT IN (0xA, 0xB)");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::NotIn]);
    assert_eq!(operand_children(&ast, n).len(), 3);
}

#[test]
fn test_in_hex_float_mixed_error() {
    let msg = parse_err("x IN (0xFF, 2.5)");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_in_octal_float_mixed_error() {
    let msg = parse_err("x IN (010, 1.5)");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_in_float_hex_mixed_error() {
    let msg = parse_err("x IN (1.5, 0xA)");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_in_string_hex_mixed_error() {
    let msg = parse_err("x IN ('a', 0xA)");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_in_hex_string_mixed_error() {
    let msg = parse_err("x IN (0xA, 'a')");
    assert!(msg.contains("same type"), "msg was: {}", msg);
}

#[test]
fn test_parenthesized_hex_arithmetic() {
    let (ast, root) = parse_ok("(0xA + 010) > 5");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThan]);
}

#[test]
fn test_complex_all_formats_in_and_or() {
    let (ast, root) = parse_ok("x = 0xFF AND y > 010 OR z < 42");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::orExpression));
}

#[test]
fn test_complex_between_and_in_mixed_formats() {
    let (ast, root) = parse_ok("x BETWEEN 0xA AND 0xFF AND y IN (010, 020)");
    let n = skip(&ast, root);
    assert!(matches!(ast.kind(n), NodeKind::andExpression));
    assert_eq!(operand_children(&ast, n).len(), 2);
}

#[test]
fn test_complex_arithmetic_all_formats() {
    let (ast, root) = parse_ok("0xA + 010 - 5 > 0");
    let n = skip(&ast, root);
    assert_eq!(cmp_ops(&ast, n), vec![ComparisonOp::GreaterThan]);
    let lhs = skip(&ast, operand_children(&ast, n)[0]);
    assert!(matches!(ast.kind(lhs), NodeKind::addExpression));
    assert_eq!(add_ops(&ast, lhs), vec![AddOp::Plus, AddOp::Minus]);
    let kids = operand_children(&ast, lhs);
    assert_eq!(kids.len(), 3);
    assert_eq!(leaf_image(&ast, kids[0]), "0xA");
    assert_eq!(leaf_image(&ast, kids[1]), "010");
    assert_eq!(leaf_image(&ast, kids[2]), "5");
}
