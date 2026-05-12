//! Migrated from `../sqlexpr-congo-rust/tests/parser_test2.rs`.
//! Uses the shared `parser_test_support::parse` helper, which runs the
//! same boolean-root / BETWEEN / IN semantic validations the source's
//! hand-written parser performs inline.  Test names match source 1:1.

mod parser_test_support;
use parser_test_support::parse;

// ============================================================================
// BOOLEAN OPERATORS
// ============================================================================

#[test]
fn test_boolean_literal_true() {
    let result = parse("TRUE");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected TRUE literal");
}

#[test]
fn test_boolean_literal_false() {
    let result = parse("FALSE");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected FALSE literal");
}

#[test]
fn test_boolean_variable() {
    let result = parse("is_active");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected variable");
}

#[test]
fn test_and_operator() {
    let result = parse("x > 5 AND y < 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected AND expression");
}

#[test]
fn test_or_operator() {
    let result = parse("x > 5 OR y < 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected OR expression");
}

#[test]
fn test_not_operator() {
    let result = parse("NOT x > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT expression");
}

#[test]
fn test_complex_boolean_expression() {
    let result = parse("(x > 5 AND y < 10) OR (z = 20 AND NOT w >= 100)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_and_or_precedence() {
    // AND should bind tighter than OR
    let result = parse("a = 1 OR b = 2 AND c = 3");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected AND on right side of OR");
}

#[test]
fn test_parenthesized_boolean() {
    let result = parse("(x > 5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// Enhancement 2: Boolean operators with string operands
#[test]
fn test_and_with_string_comparisons() {
    let result = parse("name = 'John' AND city = 'Boston'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_or_with_string_comparisons() {
    let result = parse("status = 'active' OR status = 'pending' OR status = 'verified'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_with_string_comparison() {
    let result = parse("NOT (name = 'Admin')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_complex_boolean_with_strings() {
    let result = parse("(first_name = 'John' AND last_name = 'Doe') OR (email = 'john@example.com')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_boolean_with_string_inequality() {
    let result = parse("username <> 'guest' AND password <> ''");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_and_or_not_with_strings() {
    let result = parse("(category = 'electronics' OR category = 'computers') AND NOT brand = 'unknown'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// RELATIONAL OPERATORS
// ============================================================================

#[test]
fn test_equal_operator() {
    let result = parse("x = 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected equality expression");
}

#[test]
fn test_not_equal_operator_angle_brackets() {
    let result = parse("x <> 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected not-equal expression");
}

#[test]
fn test_not_equal_operator_exclamation() {
    let result = parse("x != 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected not-equal expression");
}

#[test]
fn test_greater_than() {
    let result = parse("x > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected greater-than expression");
}

#[test]
fn test_greater_or_equal() {
    let result = parse("x >= 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected greater-or-equal expression");
}

#[test]
fn test_less_than() {
    let result = parse("x < 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected less-than expression");
}

#[test]
fn test_less_or_equal() {
    let result = parse("x <= 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected less-or-equal expression");
}

// Enhancement 3: Relational operators with string operands
#[test]
fn test_string_equality() {
    let result = parse("name = 'Alice'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected equality expression");
}

#[test]
fn test_string_inequality_not_equal() {
    let result = parse("status <> 'deleted'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_inequality_exclamation() {
    let result = parse("username != 'admin'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_greater_than() {
    let result = parse("name > 'M'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected greater-than expression");
}

#[test]
fn test_string_greater_or_equal() {
    let result = parse("city >= 'Boston'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_less_than() {
    let result = parse("code < 'ZZZ'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_less_or_equal() {
    let result = parse("country <= 'USA'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_empty_string_comparison() {
    let result = parse("description <> ''");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_with_special_chars() {
    let result = parse("email = 'user@example.com'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_comparison_with_spaces() {
    let result = parse("full_name = 'John Smith'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_comparison_with_numbers() {
    let result = parse("code >= '12345'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_precedence_no_parens() {
    let result = parse("2 + 3 * 4 = 14");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_precedence_with_parens() {
    let result = parse("(2 + 3) * 4 = 20");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// LIKE OPERATOR
// ============================================================================

#[test]
fn test_like_operator() {
    let result = parse("name LIKE '%test%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected LIKE expression");
}

#[test]
fn test_like_with_escape() {
    let result = parse("name LIKE '%test\\%%' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected LIKE with ESCAPE");
}

#[test]
fn test_not_like_operator() {
    let result = parse("name NOT LIKE '%test%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT LIKE expression");
}

#[test]
fn test_not_like_with_escape() {
    let result = parse("name NOT LIKE '%test\\%%' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT LIKE with ESCAPE");
}

#[test]
fn test_like_case_insensitive_keyword() {
    let result = parse("name LiKe '%test%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// Enhancement 4: LIKE/NOT LIKE with multi-character wildcards (%)
#[test]
fn test_like_leading_multichar_wildcard() {
    let result = parse("filename LIKE '%@gmail.com'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_trailing_multichar_wildcard() {
    let result = parse("path LIKE '/home/user/%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_embedded_multichar_wildcard() {
    let result = parse("description LIKE 'Product%Details'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_multiple_multichar_wildcards() {
    let result = parse("url LIKE 'http://%example.com/%/index.html'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_like_multichar_wildcard() {
    let result = parse("email NOT LIKE '%@spam.com'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_multichar_with_escape() {
    let result = parse("text LIKE '%50\\%%' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_like_multichar_with_escape() {
    let result = parse("code NOT LIKE 'TEST\\%USER%' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_only_multichar_wildcard() {
    let result = parse("anything LIKE '%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_double_multichar_wildcards() {
    let result = parse("pattern LIKE '%%text%%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// Enhancement 5: LIKE/NOT LIKE with single-character wildcards (_)
#[test]
fn test_like_single_char_wildcard() {
    let result = parse("code LIKE 'A_C'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_multiple_single_char_wildcards() {
    let result = parse("phone LIKE '___-___-____'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_leading_single_char_wildcard() {
    let result = parse("name LIKE '_ohn'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_trailing_single_char_wildcard() {
    let result = parse("word LIKE 'tes_'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_embedded_single_char_wildcards() {
    let result = parse("license LIKE 'AB_-_EF_-____'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_mixed_wildcards() {
    let result = parse("identifier LIKE '%_ID_%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_like_single_char_wildcard() {
    let result = parse("status NOT LIKE '_nknown'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_single_char_with_escape() {
    let result = parse("text LIKE 'test\\_data' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_like_mixed_with_escape() {
    let result = parse("pattern NOT LIKE '%\\_%test%' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_complex_pattern() {
    let result = parse("filepath LIKE '/usr/__/bin/%/app_'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_zero_or_more_leading() {
    let result = parse("text LIKE '%suffix'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_zero_or_more_trailing() {
    let result = parse("text LIKE 'prefix%'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_like_zero_or_more_embedded() {
    let result = parse("text LIKE 'start%middle%end'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_like_complex_escape() {
    let result = parse("data NOT LIKE 'test\\%\\_value%' ESCAPE '\\'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// BETWEEN OPERATOR
// ============================================================================

#[test]
fn test_between_operator() {
    let result = parse("age BETWEEN 18 AND 65");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN expression");
}

#[test]
fn test_not_between_operator() {
    let result = parse("age NOT BETWEEN 18 AND 65");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT BETWEEN expression");
}

#[test]
fn test_between_with_expressions() {
    // NOTE: BETWEEN now requires literal bounds, not expressions
    // This is a breaking change - expressions in BETWEEN are no longer allowed
    let result = parse("(x + y) BETWEEN (a - 5) AND (b * 2)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for expressions in BETWEEN bounds");
}

#[test]
fn test_between_case_insensitive() {
    let result = parse("age BeTwEeN 18 aNd 65");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// Enhancement 1: BETWEEN with string operands
#[test]
fn test_between_with_string_operands() {
    let result = parse("name BETWEEN 'Alice' AND 'Zeus'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN expression");
}

#[test]
fn test_not_between_with_string_operands() {
    let result = parse("username NOT BETWEEN 'aaa' AND 'zzz'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT BETWEEN expression");
}

#[test]
fn test_between_with_mixed_string_cases() {
    let result = parse("city BETWEEN 'Boston' AND 'Seattle'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_between_with_date_strings() {
    let result = parse("date_str BETWEEN '2024-01-01' AND '2024-12-31'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_between_with_empty_strings() {
    let result = parse("code NOT BETWEEN '' AND 'A'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// IN OPERATOR
// ============================================================================

#[test]
fn test_in_operator_single_value() {
    let result = parse("status IN ('active')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected string literal");
}

#[test]
fn test_in_operator_multiple_values() {
    let result = parse("status IN ('active', 'pending', 'completed')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected string literals");
}

#[test]
fn test_not_in_operator() {
    let result = parse("status NOT IN ('inactive', 'deleted')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN expression");
}

#[test]
fn test_in_case_insensitive() {
    let result = parse("status iN ('active')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// Enhancement 6: IN/NOT IN with numeric value sets
#[test]
fn test_in_with_integer_values() {
    let result = parse("age IN (18, 21, 25, 30)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected integer literal");
}

#[test]
fn test_not_in_with_integer_values() {
    let result = parse("error_code NOT IN (404, 500, 503)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN expression");
}

#[test]
fn test_in_with_float_values() {
    let result = parse("temperature IN (98.6, 99.0, 100.4)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_with_hex_values() {
    let result = parse("flags IN (0x00, 0xFF, 0x1A)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_with_mixed_numeric_types() {
    // NOTE: Mixing Integer and Float is no longer allowed in IN lists
    // This is a breaking change - all values must be exactly the same type
    let result = parse("value IN (10, 20.5, 0x1F, 100L)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Integer/Float in IN list");
}

#[test]
fn test_not_in_with_negative_integers() {
    let result = parse("balance NOT IN (-100, -50, -25, 0)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_with_scientific_notation() {
    let result = parse("measurement IN (1.5e-10, 2.5e-10, 3.5e-10)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_single_integer() {
    let result = parse("status_code IN (200)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_not_in_with_long_literals() {
    // Long literals (with L suffix) are now treated as regular integers
    let result = parse("big_number NOT IN (1000000L, 2000000L, 3000000L)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_with_octal_values() {
    let result = parse("permissions IN (0644, 0755, 0777)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_with_mixed_values() {
    // NOTE: Mixing Integer and Float is no longer allowed in IN lists
    // This is a breaking change - all values must be exactly the same type
    let result = parse("v IN (0644, 0x755, 777, 3000000L, 3.14e2, 2.628)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Integer/Float in IN list");
}

#[test]
fn test_in_mixed_strings_and_numbers_strings_only() {
    // Note: Since we enforce type consistency at parse level,
    // IN lists should contain same type values
    let result = parse("code IN ('A001', 'B002', 'C003')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_in_zero_values() {
    // NOTE: Mixing Integer (0) and Float (0.0) is no longer allowed
    // This is a breaking change - all values must be exactly the same type
    let result = parse("count IN (0, 0.0)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Integer/Float in IN list");
}

// ============================================================================
// IS NULL OPERATOR
// ============================================================================

#[test]
fn test_is_null() {
    let result = parse("value IS NULL");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IS NULL expression");
}

#[test]
fn test_is_not_null() {
    let result = parse("value IS NOT NULL");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IS NOT NULL expression");
}

#[test]
fn test_is_null_case_insensitive() {
    let result = parse("value Is NuLl");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// ARITHMETIC EXPRESSIONS
// ============================================================================

#[test]
fn test_addition_in_comparison() {
    let result = parse("(a + b) > 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected addition expression");
}

#[test]
fn test_subtraction_in_comparison() {
    let result = parse("(a - b) > 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_multiplication_in_comparison() {
    let result = parse("(a * b) > 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_division_in_comparison() {
    let result = parse("(a / b) > 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_modulo_in_comparison() {
    let result = parse("(a % b) = 0");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_arithmetic_precedence() {
    // Multiplication should bind tighter than addition
    let result = parse("(a + b * c) = 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_complex_arithmetic() {
    let result = parse("((a + b) * (c - d) / e) > 100");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// UNARY OPERATORS
// ============================================================================

#[test]
fn test_unary_plus() {
    let result = parse("(+x) > 0");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_unary_minus() {
    let result = parse("(-x) < 0");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_double_unary_minus() {
    // Note: (--x) would be treated as a comment in SQL
    // So we use spaces: (- -x) to get double unary minus
    let result = parse("(- -x) = 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// LITERALS
// ============================================================================

#[test]
fn test_integer_literal() {
    let result = parse("x = 42");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_long_literal() {
    // Long suffix (L) is now treated as regular integer
    let result = parse("x = 42L");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_long_literal_lowercase() {
    // Long suffix (l) is now treated as regular integer
    let result = parse("x = 42l");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_hex_literal() {
    let result = parse("x = 0x1A");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_hex_literal_lowercase() {
    let result = parse("x = 0x1a");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_octal_literal() {
    let result = parse("x = 077");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_float_literal_with_decimal() {
    let result = parse("x = 3.14");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_float_literal_with_exponent() {
    let result = parse("x = 1e5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_float_literal_with_negative_exponent() {
    let result = parse("x = 1e-5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_float_literal_starting_with_dot() {
    let result = parse("x = .5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_float_literal_full() {
    let result = parse("x = 3.14e-2");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_literal() {
    let result = parse("name = 'John'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_string_literal_with_escaped_quote() {
    let result = parse("name = 'It''s John'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_null_literal() {
    let result = parse("x = NULL");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_boolean_literal_as_value() {
    let result = parse("x = TRUE");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// IDENTIFIERS / VARIABLES
// ============================================================================

#[test]
fn test_identifier_with_underscore() {
    let result = parse("my_variable > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_identifier_with_dollar() {
    let result = parse("$variable > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_identifier_starting_with_underscore() {
    let result = parse("_variable > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_identifier_with_numbers() {
    let result = parse("var123 > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// COMMENTS
// ============================================================================

#[test]
fn test_line_comment() {
    let result = parse("x > 5 -- this is a comment\n");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_line_comment_at_end() {
    let result = parse("x > 5 -- comment");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_block_comment() {
    let result = parse("x /* comment */ > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_multiline_block_comment() {
    let result = parse("x /* this is\n a multiline\n comment */ > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_multiple_comments() {
    let result = parse("x > 5 /* c1 */ AND /* c2 */ y < 10 -- c3");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// WHITESPACE
// ============================================================================

#[test]
fn test_whitespace_handling() {
    let result = parse("  x   >   5  ");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_newlines_in_expression() {
    let result = parse("x > 5\nAND\ny < 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_tabs_in_expression() {
    let result = parse("x\t>\t5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// COMPLEX REAL-WORLD EXAMPLES
// ============================================================================

#[test]
fn test_example_from_grammar_1() {
    let result = parse("x > 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_2() {
    let result = parse("name = 'John' AND age >= 18");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_3() {
    let result = parse("price BETWEEN 10 AND 100");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_4() {
    let result = parse("status IN ('active', 'pending')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_5() {
    let result = parse("email LIKE '%@example.com'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_6() {
    let result = parse("value IS NOT NULL");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_7() {
    let result = parse("(a + b) > (c - d)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_8() {
    let result = parse("TRUE");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_example_from_grammar_9() {
    let result = parse("NOT (x = 5 OR y = 10)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_complex_real_world_1() {
    let result = parse(
        "(customer_age >= 18 AND customer_age <= 65) AND \
         (account_status IN ('active', 'pending', 'verified')) AND \
         (credit_score > 650 OR has_collateral = TRUE) AND \
         last_login IS NOT NULL"
    );
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_complex_real_world_2() {
    let result = parse(
        "(product_name LIKE '%laptop%' OR product_name LIKE '%computer%') AND \
         (price BETWEEN 500 AND 2000) AND \
         (stock_quantity > 0) AND \
         NOT (category IN ('refurbished', 'damaged'))"
    );
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_complex_real_world_3() {
    let result = parse(
        "((revenue - cost) / revenue * 100) >= 20 AND \
         (sales_count > 1000 OR premium_customer = TRUE) AND \
         region NOT IN ('excluded1', 'excluded2')"
    );
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// ERROR CASES (Should fail)
// ============================================================================

#[test]
fn test_reject_standalone_literal() {
    // Grammar should reject non-boolean top-level expressions
    let result = parse("42");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_standalone_arithmetic() {
    let result = parse("1 + 2");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_standalone_string() {
    let result = parse("'hello'");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_parenthesized_arithmetic() {
    let result = parse("(a * b)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_unterminated_string() {
    let result = parse("x = 'unterminated");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_unterminated_block_comment() {
    let result = parse("x > 5 /* unterminated comment");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_invalid_operator() {
    let result = parse("x === 5");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

#[test]
fn test_reject_missing_operand() {
    let result = parse("x >");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err());
}

// ============================================================================
// PARENTHESIZATION
// ============================================================================

#[test]
fn test_parenthesized_comparison() {
    let result = parse("(x > 5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_nested_parentheses() {
    let result = parse("((x > 5))");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_parenthesized_value_expressions() {
    let result = parse("((a + b) * (c - d)) > ((e / f) + (g % h))");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

// ============================================================================
// CASE INSENSITIVITY
// ============================================================================

#[test]
fn test_keywords_case_insensitive() {
    let result = parse("x > 5 aNd y < 10 oR z = 20");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_null_case_insensitive() {
    let result = parse("x = nULl");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}

#[test]
fn test_true_false_case_insensitive() {
    let result = parse("tRuE aNd FaLsE");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok());
}
