//! Migrated from `../sqlexpr-congo-rust/tests/parser_type_checking_tests.rs`.
//!
//! Verifies the type-checking the source's hand-written parser performs
//! at parse time for BETWEEN and IN operators.  Source's `parse()`
//! returns `NodeId` on success; this project's `parser_test_support::parse`
//! returns `()` because the validation logic lives in the test helper
//! (the CongoCC-generated parser is purely syntactic).  Functionally
//! the contract is the same: Ok on a parseable+validating input,
//! Err on either a syntax error or a semantic-validation failure.

mod parser_test_support;
use parser_test_support::parse;

// ============================================================================
// A. BETWEEN - Positive Tests (Should Pass)
// ============================================================================

#[test]
fn test_between_both_integers() {
    let result = parse("x BETWEEN 1 AND 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with integer bounds");
}

#[test]
fn test_between_both_floats() {
    let result = parse("x BETWEEN 1.5 AND 10.5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with float bounds");
}

#[test]
fn test_between_mixed_numeric_int_float() {
    let result = parse("x BETWEEN 1 AND 10.5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with mixed int/float bounds");
}

#[test]
fn test_between_mixed_numeric_float_int() {
    let result = parse("x BETWEEN 1.5 AND 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with mixed float/int bounds");
}

#[test]
fn test_between_both_strings() {
    let result = parse("name BETWEEN 'Alice' AND 'Zeus'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with string bounds");
}

#[test]
fn test_between_negative_numbers() {
    let result = parse("temp BETWEEN -10 AND -5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with negative integer bounds");
}

#[test]
fn test_between_negative_and_positive() {
    let result = parse("balance BETWEEN -100 AND 100");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with negative to positive bounds");
}

#[test]
fn test_between_negative_floats() {
    let result = parse("value BETWEEN -3.14 AND -1.5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with negative float bounds");
}

#[test]
fn test_between_hex_literals() {
    let result = parse("flags BETWEEN 0x00 AND 0xFF");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with hex literal bounds");
}

#[test]
fn test_between_octal_literals() {
    let result = parse("perms BETWEEN 0644 AND 0777");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with octal literal bounds");
}

#[test]
fn test_between_scientific_notation() {
    let result = parse("measure BETWEEN 1.5e-10 AND 2.5e-10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with scientific notation bounds");
}

#[test]
fn test_between_long_literals() {
    let result = parse("id BETWEEN 1000000L AND 9999999L");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with long literal bounds");
}

#[test]
fn test_not_between_integers() {
    let result = parse("x NOT BETWEEN 1 AND 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT BETWEEN with integer bounds");
}

#[test]
fn test_not_between_floats() {
    let result = parse("score NOT BETWEEN 0.0 AND 59.9");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT BETWEEN with float bounds");
}

#[test]
fn test_not_between_strings() {
    let result = parse("code NOT BETWEEN 'A' AND 'M'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT BETWEEN with string bounds");
}

// ============================================================================
// B. BETWEEN - Negative Tests (Should Fail)
// ============================================================================

#[test]
fn test_between_null_lower() {
    let result = parse("x BETWEEN NULL AND 10");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL in BETWEEN lower bound");
}

#[test]
fn test_between_null_upper() {
    let result = parse("x BETWEEN 1 AND NULL");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL in BETWEEN upper bound");
}

#[test]
fn test_between_both_null() {
    let result = parse("x BETWEEN NULL AND NULL");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for both NULL in BETWEEN");
}

#[test]
fn test_between_boolean_lower() {
    let result = parse("x BETWEEN TRUE AND 10");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for Boolean in BETWEEN lower bound");
}

#[test]
fn test_between_boolean_upper() {
    let result = parse("x BETWEEN 1 AND FALSE");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for Boolean in BETWEEN upper bound");
}

#[test]
fn test_between_both_boolean() {
    let result = parse("x BETWEEN TRUE AND FALSE");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for both Boolean in BETWEEN");
}

#[test]
fn test_between_string_and_int() {
    let result = parse("x BETWEEN 'hello' AND 10");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for string/int type mismatch in BETWEEN");
}

#[test]
fn test_between_int_and_string() {
    let result = parse("x BETWEEN 1 AND 'world'");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for int/string type mismatch in BETWEEN");
}

#[test]
fn test_between_string_and_float() {
    let result = parse("x BETWEEN 'test' AND 3.14");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for string/float type mismatch in BETWEEN");
}

#[test]
fn test_between_float_and_string() {
    let result = parse("x BETWEEN 2.71 AND 'value'");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for float/string type mismatch in BETWEEN");
}

#[test]
fn test_between_variable_lower() {
    let result = parse("x BETWEEN y AND 10");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for variable in BETWEEN lower bound");
}

#[test]
fn test_between_variable_upper() {
    let result = parse("x BETWEEN 1 AND y");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for variable in BETWEEN upper bound");
}

#[test]
fn test_between_expression_lower() {
    let result = parse("x BETWEEN (y + 5) AND 10");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for expression in BETWEEN lower bound");
}

#[test]
fn test_between_expression_upper() {
    let result = parse("x BETWEEN 1 AND (y * 2)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for expression in BETWEEN upper bound");
}

#[test]
fn test_not_between_null_lower() {
    let result = parse("x NOT BETWEEN NULL AND 10");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL in NOT BETWEEN lower bound");
}

#[test]
fn test_not_between_type_mismatch() {
    let result = parse("x NOT BETWEEN 'A' AND 100");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for type mismatch in NOT BETWEEN");
}

#[test]
fn test_larger_lower() {
    let result = parse("x NOT BETWEEN 150 AND 100");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower bound larger than upper bound in NOT BETWEEN");
}

// ============================================================================
// C. IN - Positive Tests (Should Pass)
// ============================================================================

#[test]
fn test_in_all_integers() {
    let result = parse("x IN (1, 2, 3, 4, 5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with all integers");
}

#[test]
fn test_in_all_floats() {
    let result = parse("score IN (1.5, 2.5, 3.5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with all floats");
}

#[test]
fn test_in_all_strings() {
    let result = parse("status IN ('active', 'pending', 'completed')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with all strings");
}

#[test]
fn test_in_single_integer() {
    let result = parse("code IN (42)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with single integer");
}

#[test]
fn test_in_single_float() {
    let result = parse("value IN (3.14)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with single float");
}

#[test]
fn test_in_single_string() {
    let result = parse("state IN ('running')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with single string");
}

#[test]
fn test_in_negative_integers() {
    let result = parse("temp IN (-10, -5, 0, 5, 10)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with negative integers");
}

#[test]
fn test_in_negative_floats() {
    let result = parse("balance IN (-100.5, -50.25, 0.0)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with negative floats");
}

#[test]
fn test_in_hex_integers() {
    let result = parse("flags IN (0x00, 0x0F, 0xFF)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with hex integers");
}

#[test]
fn test_in_octal_integers() {
    let result = parse("perms IN (0644, 0755, 0777)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with octal integers");
}

#[test]
fn test_in_long_integers() {
    let result = parse("id IN (1000000L, 2000000L, 3000000L)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with long integers");
}

#[test]
fn test_in_scientific_floats() {
    let result = parse("measure IN (1.5e-10, 2.5e-10, 3.5e-10)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with scientific notation floats");
}

#[test]
fn test_in_many_values() {
    let result = parse("x IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with 10 values");
}

#[test]
fn test_not_in_integers() {
    let result = parse("x NOT IN (1, 2, 3)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN with integers");
}

#[test]
fn test_not_in_floats() {
    let result = parse("score NOT IN (0.0, 0.5, 1.0)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN with floats");
}

#[test]
fn test_not_in_strings() {
    let result = parse("role NOT IN ('admin', 'root')");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN with strings");
}

// ============================================================================
// D. IN - Negative Tests (Should Fail)
// ============================================================================

#[test]
fn test_in_with_null() {
    let result = parse("x IN (1, 2, NULL, 3)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL in IN list");
}

#[test]
fn test_in_with_null_first() {
    let result = parse("x IN (NULL, 1, 2)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL as first element in IN list");
}

#[test]
fn test_in_with_null_only() {
    let result = parse("x IN (NULL)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL-only IN list");
}

#[test]
fn test_in_with_boolean() {
    let result = parse("x IN (1, 2, TRUE, 3)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for Boolean in IN list");
}

#[test]
fn test_in_with_boolean_first() {
    let result = parse("x IN (FALSE, 1, 2)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for Boolean as first element in IN list");
}

#[test]
fn test_in_with_boolean_only() {
    let result = parse("x IN (TRUE)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for Boolean-only IN list");
}

#[test]
fn test_in_mixed_int_float() {
    let result = parse("x IN (1, 2.5, 3)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Integer/Float in IN list");
}

#[test]
fn test_in_mixed_float_int() {
    let result = parse("x IN (1.5, 2, 3.5)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Float/Integer in IN list");
}

#[test]
fn test_in_mixed_int_string() {
    let result = parse("x IN (1, 'hello', 3)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Integer/String in IN list");
}

#[test]
fn test_in_mixed_string_int() {
    let result = parse("x IN ('a', 1, 'b')");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed String/Integer in IN list");
}

#[test]
fn test_in_mixed_float_string() {
    let result = parse("x IN (1.5, 'test', 2.5)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Float/String in IN list");
}

#[test]
fn test_in_mixed_string_float() {
    let result = parse("x IN ('x', 1.5, 'y')");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed String/Float in IN list");
}

#[test]
fn test_in_mixed_types_all_three() {
    let result = parse("x IN (1, 2.5, 'hello')");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed Int/Float/String in IN list");
}

#[test]
fn test_in_alternating_types() {
    let result = parse("x IN ('a', 1, 'b', 2)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for alternating String/Integer types in IN list");
}

#[test]
fn test_not_in_with_null() {
    let result = parse("x NOT IN (1, NULL, 3)");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for NULL in NOT IN list");
}

#[test]
fn test_not_in_mixed_types() {
    let result = parse("x NOT IN (1, 2.5, 'hello')");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for mixed types in NOT IN list");
}

// ============================================================================
// E. Edge Cases and Complex Scenarios
// ============================================================================

#[test]
fn test_between_empty_strings() {
    let result = parse("code BETWEEN '' AND 'Z'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with empty string lower bound");
}

#[test]
fn test_between_same_value() {
    let result = parse("x BETWEEN 5 AND 5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with same value bounds");
}

#[test]
fn test_between_reverse_order() {
    // NOTE: Reverse order (lower > upper) is now rejected at parse time
    let result = parse("x BETWEEN 10 AND 1");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for reverse order bounds (lower > upper)");
}

#[test]
fn test_in_duplicate_values() {
    let result = parse("x IN (1, 2, 1, 3)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with duplicate values (allowed)");
}

#[test]
fn test_complex_between_in_expression() {
    let result = parse("(x BETWEEN 1 AND 10) AND (status IN ('a', 'b'))");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected complex expression with BETWEEN and IN");
}

#[test]
fn test_nested_not_between_not_in() {
    let result = parse("NOT (x BETWEEN 1 AND 5) OR NOT (y IN (10, 20))");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected nested NOT with BETWEEN and IN");
}

#[test]
fn test_between_with_zero() {
    let result = parse("x BETWEEN 0 AND 10");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with zero");
}

// ============================================================================
// G. BETWEEN Bounds Validation Tests
// ============================================================================

#[test]
fn test_between_lower_greater_than_upper_integers() {
    let result = parse("x BETWEEN 100 AND 50");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower > upper (integers)");
}

#[test]
fn test_between_lower_greater_than_upper_floats() {
    let result = parse("x BETWEEN 10.5 AND 5.5");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower > upper (floats)");
}

#[test]
fn test_between_lower_greater_than_upper_strings() {
    let result = parse("name BETWEEN 'Zeus' AND 'Alice'");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower > upper (strings)");
}

#[test]
fn test_between_lower_greater_than_upper_mixed_int_float() {
    let result = parse("x BETWEEN 100 AND 50.5");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower > upper (int/float)");
}

#[test]
fn test_between_lower_greater_than_upper_mixed_float_int() {
    let result = parse("x BETWEEN 100.5 AND 50");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower > upper (float/int)");
}

#[test]
fn test_between_equal_bounds_integers() {
    let result = parse("x BETWEEN 50 AND 50");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with equal bounds (integers)");
}

#[test]
fn test_between_equal_bounds_floats() {
    let result = parse("x BETWEEN 5.5 AND 5.5");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with equal bounds (floats)");
}

#[test]
fn test_between_equal_bounds_strings() {
    let result = parse("name BETWEEN 'Alice' AND 'Alice'");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected BETWEEN with equal bounds (strings)");
}

#[test]
fn test_not_between_lower_greater_than_upper() {
    let result = parse("x NOT BETWEEN 200 AND 100");
    if let Ok(r) = &result {
        eprintln!("Expected error but found success: {:?}", r);
    }
    assert!(result.is_err(), "Expected error for lower > upper in NOT BETWEEN");
}

// ============================================================================
// H. IN with Mixed Integer Formats
// ============================================================================

#[test]
fn test_in_mixed_integer_formats_hex_decimal() {
    let result = parse("x IN (0xFF, 255, 0x10)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with mixed hex and decimal integers");
}

#[test]
fn test_in_mixed_integer_formats_octal_decimal() {
    let result = parse("x IN (0755, 493, 0644)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with mixed octal and decimal integers");
}

#[test]
fn test_in_mixed_integer_formats_long_decimal() {
    let result = parse("x IN (1000000L, 2000000, 3000000L)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with mixed long and decimal integers");
}

#[test]
fn test_in_mixed_integer_formats_all_types() {
    let result = parse("x IN (100, 0x64, 0144, 100L)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with decimal, hex, octal, and long integers");
}

#[test]
fn test_in_mixed_integer_formats_negative() {
    let result = parse("x IN (-10, 0xFF, -0x05, 100L)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with negative numbers and various formats");
}

#[test]
fn test_not_in_mixed_integer_formats() {
    let result = parse("x NOT IN (0x00, 0, 0644, 1000L)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN with mixed integer formats");
}

// ============================================================================
// I. IN with Mixed Float Formats
// ============================================================================

#[test]
fn test_in_mixed_float_formats_decimal_exponential() {
    let result = parse("x IN (1.5, 2.5e0, 3.5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with mixed decimal and exponential floats");
}

#[test]
fn test_in_mixed_float_formats_scientific() {
    let result = parse("x IN (1.5e-10, 2.5e-10, 3.5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with mixed scientific and decimal floats");
}

#[test]
fn test_in_mixed_float_formats_positive_exponent() {
    let result = parse("x IN (100.0, 1e2, 1.0e2)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with mixed positive exponent floats");
}

#[test]
fn test_in_mixed_float_formats_negative_values() {
    let result = parse("x IN (-1.5, -2.5e0, -3.5)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected IN with negative mixed float formats");
}

#[test]
fn test_not_in_mixed_float_formats() {
    let result = parse("x NOT IN (1.0, 2.0e0, 3.14159)");
    if let Err(e) = &result {
        eprintln!("Parse error: {}", e);
    }
    assert!(result.is_ok(), "Expected NOT IN with mixed float formats");
}

// ============================================================================
// F. Error Message Quality Tests
// ============================================================================

#[test]
fn test_between_null_error_message() {
    let result = parse("x BETWEEN NULL AND 10");
    assert!(result.is_err());
    if let Err(e) = result {
        let msg = format!("{}", e);
        assert!(
            msg.contains("NULL") && msg.contains("not allowed"),
            "Error message should mention NULL is not allowed, got: {}",
            msg
        );
    }
}

#[test]
fn test_between_type_mismatch_error_message() {
    let result = parse("x BETWEEN 'hello' AND 10");
    assert!(result.is_err());
    if let Err(e) = result {
        let msg = format!("{}", e);
        assert!(
            msg.contains("string") && msg.contains("integer"),
            "Error should mention both types, got: {}",
            msg
        );
        assert!(
            msg.contains("both numeric or both string"),
            "Error should explain requirement, got: {}",
            msg
        );
    }
}

#[test]
fn test_in_null_error_message() {
    let result = parse("x IN (1, NULL, 3)");
    assert!(result.is_err());
    if let Err(e) = result {
        let msg = format!("{}", e);
        assert!(
            msg.contains("NULL") && msg.contains("not allowed"),
            "Error message should mention NULL is not allowed, got: {}",
            msg
        );
    }
}

#[test]
fn test_in_boolean_error_message() {
    let result = parse("x IN (1, TRUE, 3)");
    assert!(result.is_err());
    if let Err(e) = result {
        let msg = format!("{}", e);
        assert!(
            msg.contains("Boolean") && msg.contains("not allowed"),
            "Error message should mention Boolean is not allowed, got: {}",
            msg
        );
    }
}

#[test]
fn test_in_type_mismatch_error_message() {
    let result = parse("x IN (1, 2.5, 3)");
    assert!(result.is_err());
    if let Err(e) = result {
        let msg = format!("{}", e);
        assert!(
            msg.contains("integer") && msg.contains("float"),
            "Error should mention both types, got: {}",
            msg
        );
        assert!(
            msg.contains("must all be the same type"),
            "Error should explain requirement, got: {}",
            msg
        );
    }
}

#[test]
fn test_between_variable_error_message() {
    let result = parse("x BETWEEN y AND 10");
    assert!(result.is_err());
    if let Err(e) = result {
        let msg = format!("{}", e);
        assert!(
            msg.contains("Variables") || msg.contains("literal"),
            "Error should mention variables or literals, got: {}",
            msg
        );
    }
}
