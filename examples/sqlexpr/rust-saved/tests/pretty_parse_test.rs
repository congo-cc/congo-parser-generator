//! Pretty-print regression tests for the SQL Expression parser.
//!
//! Each test parses a short SQL boolean expression, runs the
//! `DefaultPrettyPrinter` against the resulting AST, and asserts
//! that the rendered output matches the expected snapshot exactly.
//!
//! Coverage: every test combines at least two logical operators
//! (chosen from `AND`, `OR`, `NOT`) with at least two relational or
//! comparison operators.  Across the suite, every grammar production
//! (`BooleanExpression`, `orExpression`, `andExpression`, `equalityExpression`,
//! `comparisonExpression`, `addExpression`, `multExpr`, `unaryExpr`,
//! `primaryExpr`, `literal`, `stringLitteral`, `variable`) and every
//! literal kind (`STRING_LITERAL`, `DECIMAL_LITERAL`, `HEX_LITERAL`,
//! `OCTAL_LITERAL`, `FLOATING_POINT_LITERAL`, `TRUE`, `FALSE`, `NULL`)
//! is exercised at least once.
//!
//! Cargo's default behavior captures stdout, so `cargo test` shows only
//! the standard pass/fail summary.  Run `cargo test -- --nocapture` to
//! see the SQL input and the pretty-printed AST for each case.

use parser::parser::Parser;
use parser::pretty::{DefaultPrettyPrinter, PrettyPrinter};

struct PrettyCase {
    name: &'static str,
    sql: &'static str,
    expected: &'static str,
}

/// Parses `case.sql`, pretty-prints the AST, prints the SQL and the
/// rendered tree (visible only with `cargo test -- --nocapture`), and
/// asserts that the rendering matches `case.expected` byte-for-byte.
fn run_pretty_case(case: &PrettyCase) {
    let ast = Parser::parse(case.sql, Some(case.name))
        .unwrap_or_else(|e| panic!("[{}] parse failed for {:?}: {}", case.name, case.sql, e));

    let actual = DefaultPrettyPrinter.pretty_print(&ast);

    println!("=== {} ===", case.name);
    println!("SQL:  {}", case.sql);
    println!("AST:");
    println!("{}", actual);
    println!();

    assert_eq!(
        actual,
        case.expected,
        "[{}] pretty-printed output did not match expected snapshot.\n\
         --- ACTUAL ---\n{}\n\
         --- EXPECTED ---\n{}\n",
        case.name,
        actual,
        case.expected,
    );
}

// =================================================================
// Test cases
// =================================================================
//
// Each test exercises a deliberately chosen mix of operators and
// expression types.  See module-level docs for coverage notes.

#[test]
fn pp_and_or_with_eq_gt() {
    // Coverage: AND, OR  +  =, >, =  +  STRING_LITERAL, DECIMAL_LITERAL, TRUE
    run_pretty_case(&PrettyCase {
        name: "pp_and_or_with_eq_gt",
        sql: "name = 'foo' AND age > 18 OR active = TRUE",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      equalityExpression
        variable
          Token(ID, "name")
        Token(EQUALS, "=")
        literal
          stringLitteral
            Token(STRING_LITERAL, "'foo'")
      Token(AND, "AND")
      comparisonExpression
        variable
          Token(ID, "age")
        Token(GT, ">")
        literal
          Token(DECIMAL_LITERAL, "18")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "active")
      Token(EQUALS, "=")
      literal
        Token(TRUE, "TRUE")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_not_and_with_eq_ge_le() {
    // Coverage: NOT, AND, AND  +  =, >=, <=  +  parenthesized primary
    run_pretty_case(&PrettyCase {
        name: "pp_not_and_with_eq_ge_le",
        sql: "NOT (status = 'closed') AND priority >= 5 AND retries <= 3",
        expected: r#"BooleanExpression
  andExpression
    unaryExpr
      Token(NOT, "NOT")
      primaryExpr
        Token(LPAREN, "(")
        equalityExpression
          variable
            Token(ID, "status")
          Token(EQUALS, "=")
          literal
            stringLitteral
              Token(STRING_LITERAL, "'closed'")
        Token(RPAREN, ")")
    Token(AND, "AND")
    comparisonExpression
      variable
        Token(ID, "priority")
      Token(GTE, ">=")
      literal
        Token(DECIMAL_LITERAL, "5")
    Token(AND, "AND")
    comparisonExpression
      variable
        Token(ID, "retries")
      Token(LTE, "<=")
      literal
        Token(DECIMAL_LITERAL, "3")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_or_and_with_isnull_isnotnull_eq() {
    // Coverage: OR, AND  +  IS NULL, IS NOT NULL, =  +  FALSE literal
    run_pretty_case(&PrettyCase {
        name: "pp_or_and_with_isnull_isnotnull_eq",
        sql: "email IS NULL OR username IS NOT NULL AND verified = FALSE",
        expected: r#"BooleanExpression
  orExpression
    equalityExpression
      variable
        Token(ID, "email")
      Token(IS, "IS")
      Token(NULL, "NULL")
    Token(OR, "OR")
    andExpression
      equalityExpression
        variable
          Token(ID, "username")
        Token(IS, "IS")
        Token(NOT, "NOT")
        Token(NULL, "NULL")
      Token(AND, "AND")
      equalityExpression
        variable
          Token(ID, "verified")
        Token(EQUALS, "=")
        literal
          Token(FALSE, "FALSE")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_not_or_with_like_ne_eq() {
    // Coverage: AND, NOT, OR  +  LIKE, <>, =  +  parenthesized primary
    run_pretty_case(&PrettyCase {
        name: "pp_and_not_or_with_like_ne_eq",
        sql: "name LIKE 'admin%' AND NOT (level <> 0) OR role = 'guest'",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      comparisonExpression
        variable
          Token(ID, "name")
        Token(LIKE, "LIKE")
        stringLitteral
          Token(STRING_LITERAL, "'admin%'")
      Token(AND, "AND")
      unaryExpr
        Token(NOT, "NOT")
        primaryExpr
          Token(LPAREN, "(")
          equalityExpression
            variable
              Token(ID, "level")
            Token(DIAMOND, "<>")
            literal
              Token(OCTAL_LITERAL, "0")
          Token(RPAREN, ")")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "role")
      Token(EQUALS, "=")
      literal
        stringLitteral
          Token(STRING_LITERAL, "'guest'")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_or_with_arith_between_eq() {
    // Coverage: AND, OR (and BETWEEN's internal AND)
    //         + >, BETWEEN, =  +  arithmetic '+', parenthesized primary
    run_pretty_case(&PrettyCase {
        name: "pp_and_or_with_arith_between_eq",
        sql: "(x + y) > z AND value BETWEEN 100 AND 200 OR flag = TRUE",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      comparisonExpression
        primaryExpr
          Token(LPAREN, "(")
          addExpression
            variable
              Token(ID, "x")
            Token(PLUS, "+")
            variable
              Token(ID, "y")
          Token(RPAREN, ")")
        Token(GT, ">")
        variable
          Token(ID, "z")
      Token(AND, "AND")
      comparisonExpression
        variable
          Token(ID, "value")
        Token(BETWEEN, "BETWEEN")
        literal
          Token(DECIMAL_LITERAL, "100")
        Token(AND, "AND")
        literal
          Token(DECIMAL_LITERAL, "200")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "flag")
      Token(EQUALS, "=")
      literal
        Token(TRUE, "TRUE")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_or_not_with_in_isnull() {
    // Coverage: OR, NOT  +  IN (with multi-element list), IS NULL
    //         + multiple stringLitteral nodes
    run_pretty_case(&PrettyCase {
        name: "pp_or_not_with_in_isnull",
        sql: "country IN ('US', 'CA', 'MX') OR NOT (state IS NULL)",
        expected: r#"BooleanExpression
  orExpression
    comparisonExpression
      variable
        Token(ID, "country")
      Token(IN, "IN")
      Token(LPAREN, "(")
      inElement
        literal
          stringLitteral
            Token(STRING_LITERAL, "'US'")
      Token(COMMA, ",")
      inElement
        literal
          stringLitteral
            Token(STRING_LITERAL, "'CA'")
      Token(COMMA, ",")
      inElement
        literal
          stringLitteral
            Token(STRING_LITERAL, "'MX'")
      Token(RPAREN, ")")
    Token(OR, "OR")
    unaryExpr
      Token(NOT, "NOT")
      primaryExpr
        Token(LPAREN, "(")
        equalityExpression
          variable
            Token(ID, "state")
          Token(IS, "IS")
          Token(NULL, "NULL")
        Token(RPAREN, ")")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_or_with_notin_notbetween_eq() {
    // Coverage: AND, OR  +  NOT IN, NOT BETWEEN, =  +  TRUE literal
    run_pretty_case(&PrettyCase {
        name: "pp_and_or_with_notin_notbetween_eq",
        sql: "code NOT IN ('A', 'B') AND price NOT BETWEEN 10 AND 100 OR active = TRUE",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      comparisonExpression
        variable
          Token(ID, "code")
        Token(NOT, "NOT")
        Token(IN, "IN")
        Token(LPAREN, "(")
        inElement
          literal
            stringLitteral
              Token(STRING_LITERAL, "'A'")
        Token(COMMA, ",")
        inElement
          literal
            stringLitteral
              Token(STRING_LITERAL, "'B'")
        Token(RPAREN, ")")
      Token(AND, "AND")
      comparisonExpression
        variable
          Token(ID, "price")
        Token(NOT, "NOT")
        Token(BETWEEN, "BETWEEN")
        literal
          Token(DECIMAL_LITERAL, "10")
        Token(AND, "AND")
        literal
          Token(DECIMAL_LITERAL, "100")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "active")
      Token(EQUALS, "=")
      literal
        Token(TRUE, "TRUE")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_not_or_with_arith_ge_lt_eq() {
    // Coverage: AND, NOT, OR  +  >=, <, =
    //         + arithmetic '*', '/', '%', parenthesized primary
    run_pretty_case(&PrettyCase {
        name: "pp_and_not_or_with_arith_ge_lt_eq",
        sql: "(x * 2) >= y AND NOT (z / 3 < 1) OR remainder = (n % 5)",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      comparisonExpression
        primaryExpr
          Token(LPAREN, "(")
          multExpr
            variable
              Token(ID, "x")
            Token(STAR, "*")
            literal
              Token(DECIMAL_LITERAL, "2")
          Token(RPAREN, ")")
        Token(GTE, ">=")
        variable
          Token(ID, "y")
      Token(AND, "AND")
      unaryExpr
        Token(NOT, "NOT")
        primaryExpr
          Token(LPAREN, "(")
          comparisonExpression
            multExpr
              variable
                Token(ID, "z")
              Token(DIV, "/")
              literal
                Token(DECIMAL_LITERAL, "3")
            Token(LT, "<")
            literal
              Token(DECIMAL_LITERAL, "1")
          Token(RPAREN, ")")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "remainder")
      Token(EQUALS, "=")
      primaryExpr
        Token(LPAREN, "(")
        multExpr
          variable
            Token(ID, "n")
          Token(PERCENT, "%")
          literal
            Token(DECIMAL_LITERAL, "5")
        Token(RPAREN, ")")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_or_with_hex_oct_arith() {
    // Coverage: AND, OR  +  =, >, =
    //         + HEX_LITERAL, OCTAL_LITERAL, arithmetic '-'
    run_pretty_case(&PrettyCase {
        name: "pp_and_or_with_hex_oct_arith",
        sql: "value = 0xFF AND (count - 1) > 0 OR flags = 0777",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      equalityExpression
        variable
          Token(ID, "value")
        Token(EQUALS, "=")
        literal
          Token(HEX_LITERAL, "0xFF")
      Token(AND, "AND")
      comparisonExpression
        primaryExpr
          Token(LPAREN, "(")
          addExpression
            variable
              Token(ID, "count")
            Token(MINUS, "-")
            literal
              Token(DECIMAL_LITERAL, "1")
          Token(RPAREN, ")")
        Token(GT, ">")
        literal
          Token(OCTAL_LITERAL, "0")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "flags")
      Token(EQUALS, "=")
      literal
        Token(OCTAL_LITERAL, "0777")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_or_with_floats() {
    // Coverage: AND, OR  +  =, >, <  +  three FLOATING_POINT_LITERAL flavors
    run_pretty_case(&PrettyCase {
        name: "pp_and_or_with_floats",
        sql: "temperature = 98.6 AND height > 5.5e1 OR weight < .75e2",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      equalityExpression
        variable
          Token(ID, "temperature")
        Token(EQUALS, "=")
        literal
          Token(FLOATING_POINT_LITERAL, "98.6")
      Token(AND, "AND")
      comparisonExpression
        variable
          Token(ID, "height")
        Token(GT, ">")
        literal
          Token(FLOATING_POINT_LITERAL, "5.5e1")
    Token(OR, "OR")
    comparisonExpression
      variable
        Token(ID, "weight")
      Token(LT, "<")
      literal
        Token(FLOATING_POINT_LITERAL, ".75e2")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_not_or_with_unary_eq_isnotnull() {
    // Coverage: AND, NOT, OR  +  >, =, IS NOT NULL
    //         + unary '-', NULL literal, parenthesized primary
    run_pretty_case(&PrettyCase {
        name: "pp_and_not_or_with_unary_eq_isnotnull",
        sql: "(-x) > 0 AND NOT (y = NULL) OR z IS NOT NULL",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      comparisonExpression
        primaryExpr
          Token(LPAREN, "(")
          unaryExpr
            Token(MINUS, "-")
            variable
              Token(ID, "x")
          Token(RPAREN, ")")
        Token(GT, ">")
        literal
          Token(OCTAL_LITERAL, "0")
      Token(AND, "AND")
      unaryExpr
        Token(NOT, "NOT")
        primaryExpr
          Token(LPAREN, "(")
          equalityExpression
            variable
              Token(ID, "y")
            Token(EQUALS, "=")
            literal
              Token(NULL, "NULL")
          Token(RPAREN, ")")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "z")
      Token(IS, "IS")
      Token(NOT, "NOT")
      Token(NULL, "NULL")
  Token(EOF, "")"#,
    });
}

#[test]
fn pp_and_or_with_like_escape_notlike_eq() {
    // Coverage: AND, OR  +  LIKE ESCAPE, NOT LIKE, =
    //         + multiple stringLitteral nodes
    run_pretty_case(&PrettyCase {
        name: "pp_and_or_with_like_escape_notlike_eq",
        sql: "path LIKE '/usr/!%doc' ESCAPE '!' AND owner NOT LIKE 'svc_%' OR mode = 'rw'",
        expected: r#"BooleanExpression
  orExpression
    andExpression
      comparisonExpression
        variable
          Token(ID, "path")
        Token(LIKE, "LIKE")
        stringLitteral
          Token(STRING_LITERAL, "'/usr/!%doc'")
        Token(ESCAPE, "ESCAPE")
        stringLitteral
          Token(STRING_LITERAL, "'!'")
      Token(AND, "AND")
      comparisonExpression
        variable
          Token(ID, "owner")
        Token(NOT, "NOT")
        Token(LIKE, "LIKE")
        stringLitteral
          Token(STRING_LITERAL, "'svc_%'")
    Token(OR, "OR")
    equalityExpression
      variable
        Token(ID, "mode")
      Token(EQUALS, "=")
      literal
        stringLitteral
          Token(STRING_LITERAL, "'rw'")
  Token(EOF, "")"#,
    });
}
