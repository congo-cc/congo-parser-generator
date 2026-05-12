//! Comprehensive tests for the public Lexer/Token/TokenType interface.
//!
//! Coverage targets, by module:
//!
//! * `parser::tokens`
//!     - `TokenType::is_regular`, `is_skipped`, `is_unparsed`, `is_more`
//!     - `TokenType::COUNT`
//!     - `TokenType` `Display` impl
//!     - free function `is_contextual_token`
//!     - `LexicalState::DEFAULT` and `Default::default()`
//!
//! * `parser::lexer`
//!     - `Token` fields (`kind`, `start`, `end`, `is_unparsed`)
//!     - `Token::text`
//!     - `Lexer::new`, `tokenize`, `tokens`, `source_name`
//!     - `Lexer::activate_token_type`, `deactivate_token_type`,
//!       `save_active_tokens`, `restore_active_tokens`, `switch_to`
//!
//! Tests are split into positive (the API behaves as documented when
//! given valid input) and negative / edge-case (invalid characters,
//! unterminated literals, empty input, repeated calls, etc.).

use std::collections::HashSet;

use parser::lexer::{Lexer, Token};
use parser::tokens::{is_contextual_token, LexicalState, TokenType};

// =================================================================
// Helpers
// =================================================================

/// Tokenize `source` and return the resulting token list.  Panics on
/// tokenization error so failing tests stop with a clear message.
fn lex(source: &str) -> Vec<Token> {
    let mut lx = Lexer::new(source, "test");
    lx.tokenize()
        .unwrap_or_else(|e| panic!("tokenize failed for {source:?}: {e}"));
    lx.tokens().to_vec()
}

/// Tokenize and drop tokens whose `is_unparsed` flag is set
/// (whitespace).  Useful when an assertion only cares about
/// "real" tokens.
fn lex_no_ws(source: &str) -> Vec<Token> {
    lex(source).into_iter().filter(|t| !t.is_unparsed).collect()
}

/// Convenience: extract just the `(kind, text)` pairs from a token
/// list, for compact `assert_eq!` comparisons.
fn shape<'a>(tokens: &'a [Token], source: &'a str) -> Vec<(TokenType, &'a str)> {
    tokens.iter().map(|t| (t.kind, t.text(source))).collect()
}

// =================================================================
// TokenType: classification predicates
// =================================================================

#[test]
fn token_type_count_matches_defined_variants() {
    // COUNT excludes the DUMMY and INVALID sentinels (40 grammar
    // tokens: EOF + 5 unparsed + 11 keywords + 15 operators + 2
    // comments + 5 literals + ID).
    assert_eq!(TokenType::COUNT, 40);
}

#[test]
fn token_type_is_regular_positive() {
    // Spot-check every flavor of "regular" token: keyword, operator,
    // literal kind, identifier, EOF.
    for tt in [
        TokenType::EOF,
        TokenType::AND,
        TokenType::OR,
        TokenType::NOT,
        TokenType::TRUE,
        TokenType::FALSE,
        TokenType::NULL,
        TokenType::BETWEEN,
        TokenType::LIKE,
        TokenType::ESCAPE,
        TokenType::IN,
        TokenType::IS,
        TokenType::_TOKEN_17, // "="
        TokenType::_TOKEN_31, // "%"
        TokenType::DECIMAL_LITERAL,
        TokenType::HEX_LITERAL,
        TokenType::OCTAL_LITERAL,
        TokenType::FLOATING_POINT_LITERAL,
        TokenType::STRING_LITERAL,
        TokenType::ID,
    ] {
        assert!(tt.is_regular(), "{tt} should be regular");
    }
}

#[test]
fn token_type_is_regular_negative() {
    // Skipped tokens (comments) and unparsed tokens (whitespace) are
    // NOT classified as regular.  The two sentinels (DUMMY, INVALID)
    // are also non-regular.
    for tt in [
        TokenType::LINE_COMMENT,
        TokenType::BLOCK_COMMENT,
        TokenType::_TOKEN_1,
        TokenType::_TOKEN_5,
        TokenType::DUMMY,
        TokenType::INVALID,
    ] {
        assert!(!tt.is_regular(), "{tt} should not be regular");
    }
}

#[test]
fn token_type_is_skipped_only_for_comments() {
    assert!(TokenType::LINE_COMMENT.is_skipped());
    assert!(TokenType::BLOCK_COMMENT.is_skipped());

    // Anything else, including whitespace, is NOT classified as
    // skipped — whitespace lives in `is_unparsed` instead.
    for tt in [
        TokenType::EOF,
        TokenType::ID,
        TokenType::AND,
        TokenType::_TOKEN_1,
        TokenType::_TOKEN_5,
        TokenType::STRING_LITERAL,
        TokenType::INVALID,
        TokenType::DUMMY,
    ] {
        assert!(!tt.is_skipped(), "{tt} should not be skipped");
    }
}

#[test]
fn token_type_is_unparsed_only_for_whitespace_tokens() {
    // _TOKEN_1 .. _TOKEN_5 cover space, tab, LF, CR, FF.
    for tt in [
        TokenType::_TOKEN_1,
        TokenType::_TOKEN_2,
        TokenType::_TOKEN_3,
        TokenType::_TOKEN_4,
        TokenType::_TOKEN_5,
    ] {
        assert!(tt.is_unparsed(), "{tt} should be unparsed");
    }
    for tt in [
        TokenType::EOF,
        TokenType::ID,
        TokenType::AND,
        TokenType::LINE_COMMENT,
        TokenType::BLOCK_COMMENT,
        TokenType::DECIMAL_LITERAL,
        TokenType::INVALID,
        TokenType::DUMMY,
    ] {
        assert!(!tt.is_unparsed(), "{tt} should not be unparsed");
    }
}

#[test]
fn token_type_is_more_always_false() {
    // This grammar has no MORE tokens.
    for tt in [
        TokenType::EOF,
        TokenType::AND,
        TokenType::ID,
        TokenType::STRING_LITERAL,
        TokenType::_TOKEN_1,
        TokenType::LINE_COMMENT,
        TokenType::INVALID,
        TokenType::DUMMY,
    ] {
        assert!(!tt.is_more(), "{tt}.is_more() should be false");
    }
}

#[test]
fn token_type_classification_partitions_grammar_tokens() {
    // Every grammar token (excluding DUMMY/INVALID sentinels) lands
    // in exactly one of {regular, skipped, unparsed}.
    let all_grammar: &[TokenType] = &[
        TokenType::EOF,
        TokenType::_TOKEN_1, TokenType::_TOKEN_2, TokenType::_TOKEN_3,
        TokenType::_TOKEN_4, TokenType::_TOKEN_5,
        TokenType::NOT, TokenType::AND, TokenType::OR,
        TokenType::BETWEEN, TokenType::LIKE, TokenType::ESCAPE,
        TokenType::IN, TokenType::IS,
        TokenType::TRUE, TokenType::FALSE, TokenType::NULL,
        TokenType::_TOKEN_17, TokenType::_TOKEN_18, TokenType::_TOKEN_19,
        TokenType::_TOKEN_20, TokenType::_TOKEN_21, TokenType::_TOKEN_22,
        TokenType::_TOKEN_23, TokenType::_TOKEN_24, TokenType::_TOKEN_25,
        TokenType::_TOKEN_26, TokenType::_TOKEN_27, TokenType::_TOKEN_28,
        TokenType::_TOKEN_29, TokenType::_TOKEN_30, TokenType::_TOKEN_31,
        TokenType::LINE_COMMENT, TokenType::BLOCK_COMMENT,
        TokenType::DECIMAL_LITERAL, TokenType::HEX_LITERAL,
        TokenType::OCTAL_LITERAL, TokenType::FLOATING_POINT_LITERAL,
        TokenType::STRING_LITERAL, TokenType::ID,
    ];
    assert_eq!(all_grammar.len(), TokenType::COUNT);
    for tt in all_grammar {
        let r = tt.is_regular() as u8;
        let s = tt.is_skipped() as u8;
        let u = tt.is_unparsed() as u8;
        assert_eq!(r + s + u, 1, "{tt} must be exactly one of regular/skipped/unparsed");
    }
}

#[test]
fn token_type_display_matches_variant_name() {
    // A representative sample — Display should write the variant name
    // verbatim (matching how the parser surfaces token types in error
    // messages and AST dumps).
    let cases = [
        (TokenType::EOF, "EOF"),
        (TokenType::AND, "AND"),
        (TokenType::OR, "OR"),
        (TokenType::NOT, "NOT"),
        (TokenType::TRUE, "TRUE"),
        (TokenType::FALSE, "FALSE"),
        (TokenType::NULL, "NULL"),
        (TokenType::BETWEEN, "BETWEEN"),
        (TokenType::LIKE, "LIKE"),
        (TokenType::ESCAPE, "ESCAPE"),
        (TokenType::IN, "IN"),
        (TokenType::IS, "IS"),
        (TokenType::_TOKEN_17, "_TOKEN_17"),
        (TokenType::_TOKEN_31, "_TOKEN_31"),
        (TokenType::DECIMAL_LITERAL, "DECIMAL_LITERAL"),
        (TokenType::HEX_LITERAL, "HEX_LITERAL"),
        (TokenType::OCTAL_LITERAL, "OCTAL_LITERAL"),
        (TokenType::FLOATING_POINT_LITERAL, "FLOATING_POINT_LITERAL"),
        (TokenType::STRING_LITERAL, "STRING_LITERAL"),
        (TokenType::ID, "ID"),
        (TokenType::LINE_COMMENT, "LINE_COMMENT"),
        (TokenType::BLOCK_COMMENT, "BLOCK_COMMENT"),
        (TokenType::DUMMY, "DUMMY"),
        (TokenType::INVALID, "INVALID"),
    ];
    for (tt, expected) in cases {
        assert_eq!(format!("{}", tt), expected);
        assert_eq!(tt.to_string(), expected);
    }
}

#[test]
fn token_type_equality_and_ordering() {
    // The enum is `#[repr(u16)]` and derives Ord; smaller discriminants
    // sort before larger ones.  This matters because the lexer breaks
    // ties between equal-length matches by preferring the smaller
    // discriminant (see `get_match_info`).
    assert!(TokenType::EOF < TokenType::AND);
    assert!(TokenType::AND < TokenType::OR);
    assert_eq!(TokenType::AND, TokenType::AND);
    assert_ne!(TokenType::AND, TokenType::OR);
}

#[test]
fn is_contextual_token_returns_false_for_all() {
    // This grammar declares no contextual tokens, so the helper must
    // never return true.
    for tt in [
        TokenType::EOF, TokenType::AND, TokenType::ID,
        TokenType::STRING_LITERAL, TokenType::_TOKEN_1, TokenType::DUMMY,
    ] {
        assert!(!is_contextual_token(tt), "{tt} should be non-contextual");
    }
}

// =================================================================
// LexicalState
// =================================================================

#[test]
fn lexical_state_default_is_default_variant() {
    let s: LexicalState = Default::default();
    assert_eq!(s, LexicalState::DEFAULT);
}

#[test]
fn lexical_state_equality_and_copy() {
    // LexicalState derives Copy + PartialEq + Eq + Hash, so it can
    // round-trip through a HashSet.
    let a = LexicalState::DEFAULT;
    let b = a; // copy, not move
    assert_eq!(a, b);
    let mut set: HashSet<LexicalState> = HashSet::new();
    set.insert(a);
    assert!(set.contains(&LexicalState::DEFAULT));
}

// =================================================================
// Token: fields and `text()`
// =================================================================

#[test]
fn token_text_returns_source_slice() {
    let src = "abc 123";
    let tokens = lex(src);
    // Each token's text() must equal &src[start..end].
    for t in &tokens {
        if t.kind == TokenType::EOF {
            // EOF spans an empty range at end-of-input.
            assert_eq!(t.start, t.end);
            assert_eq!(t.text(src), "");
        } else {
            assert!(t.start <= t.end);
            assert_eq!(t.text(src), &src[t.start..t.end]);
        }
    }
}

#[test]
fn token_text_handles_multibyte_utf8_offsets() {
    // 'α' is two bytes in UTF-8.  The lexer doesn't recognize it as
    // a valid identifier start (only ASCII), so it becomes INVALID.
    // The byte range still has to land on a UTF-8 character boundary.
    let src = "α = 1";
    let tokens = lex(src);
    assert!(!tokens.is_empty());
    let invalid = &tokens[0];
    assert_eq!(invalid.kind, TokenType::INVALID);
    assert_eq!(invalid.start, 0);
    assert_eq!(invalid.end, 2, "α occupies 2 UTF-8 bytes");
    assert_eq!(invalid.text(src), "α");
}

#[test]
fn token_fields_describe_position_and_unparsed_flag() {
    let src = "x  y";
    let tokens = lex(src);
    // Expected: ID("x"), _TOKEN_1(" "), _TOKEN_1(" "), ID("y"), EOF.
    assert_eq!(tokens.len(), 5);

    assert_eq!(tokens[0].kind, TokenType::ID);
    assert_eq!(tokens[0].is_unparsed, false);
    assert_eq!((tokens[0].start, tokens[0].end), (0, 1));

    assert_eq!(tokens[1].kind, TokenType::_TOKEN_1);
    assert_eq!(tokens[1].is_unparsed, true);
    assert_eq!((tokens[1].start, tokens[1].end), (1, 2));

    assert_eq!(tokens[2].kind, TokenType::_TOKEN_1);
    assert_eq!(tokens[2].is_unparsed, true);
    assert_eq!((tokens[2].start, tokens[2].end), (2, 3));

    assert_eq!(tokens[3].kind, TokenType::ID);
    assert_eq!(tokens[3].is_unparsed, false);
    assert_eq!((tokens[3].start, tokens[3].end), (3, 4));

    assert_eq!(tokens[4].kind, TokenType::EOF);
    assert_eq!((tokens[4].start, tokens[4].end), (4, 4));
}

// =================================================================
// Lexer::new, source_name, tokens (positive)
// =================================================================

#[test]
fn lexer_new_records_source_name() {
    let lx = Lexer::new("abc", "my_file.sql");
    assert_eq!(lx.source_name(), "my_file.sql");
}

#[test]
fn lexer_tokens_is_empty_before_tokenize() {
    let lx = Lexer::new("abc", "test");
    assert!(lx.tokens().is_empty());
}

#[test]
fn lexer_tokenize_empty_input_yields_only_eof() {
    let tokens = lex("");
    assert_eq!(tokens.len(), 1);
    assert_eq!(tokens[0].kind, TokenType::EOF);
    assert_eq!((tokens[0].start, tokens[0].end), (0, 0));
}

#[test]
fn lexer_tokenize_is_idempotent_in_practice() {
    // Calling tokenize() twice without resetting will append a second
    // EOF (because tokenize starts at position 0 each call and pushes
    // every non-skipped token).  This documents the behavior so users
    // know to construct a fresh Lexer if they need to re-tokenize.
    let mut lx = Lexer::new("abc", "test");
    lx.tokenize().unwrap();
    let after_first = lx.tokens().len();
    lx.tokenize().unwrap();
    let after_second = lx.tokens().len();
    assert!(after_second > after_first, "second tokenize should append");
}

// =================================================================
// Tokenization: keywords, identifiers, literals
// =================================================================

#[test]
fn tokenize_all_reserved_keywords() {
    let src = "NOT AND OR BETWEEN LIKE ESCAPE IN IS TRUE FALSE NULL";
    let kinds: Vec<TokenType> = lex_no_ws(&src).iter().map(|t| t.kind).collect();
    assert_eq!(
        kinds,
        vec![
            TokenType::NOT, TokenType::AND, TokenType::OR,
            TokenType::BETWEEN, TokenType::LIKE, TokenType::ESCAPE,
            TokenType::IN, TokenType::IS,
            TokenType::TRUE, TokenType::FALSE, TokenType::NULL,
            TokenType::EOF,
        ],
    );
}

#[test]
fn tokenize_keywords_are_case_insensitive() {
    // The grammar declares its keywords with [IGNORE_CASE].  Mixed
    // case spellings must all yield the same token type.
    let src = "and AND aNd AnD";
    let kinds: Vec<TokenType> = lex_no_ws(src)
        .iter()
        .filter(|t| t.kind != TokenType::EOF)
        .map(|t| t.kind)
        .collect();
    assert_eq!(kinds, vec![TokenType::AND; 4]);
}

#[test]
fn tokenize_identifier_basic() {
    let tokens = lex_no_ws("foo");
    let kinds: Vec<TokenType> = tokens.iter().map(|t| t.kind).collect();
    assert_eq!(kinds, vec![TokenType::ID, TokenType::EOF]);
    assert_eq!(tokens[0].text("foo"), "foo");
}

#[test]
fn tokenize_identifiers_with_special_starts_and_digits() {
    // Per grammar `<ID>` allows letters, digits, '_', '$', starting
    // with letter, '_', or '$'.
    let cases = ["_foo", "$bar", "abc123", "x_y_z", "a$b$c", "X1"];
    for case in cases {
        let toks = lex_no_ws(case);
        assert_eq!(toks[0].kind, TokenType::ID, "expected ID for {case:?}");
        assert_eq!(toks[0].text(case), case);
    }
}

#[test]
fn tokenize_identifier_cannot_start_with_digit() {
    // "1abc" should split into DECIMAL_LITERAL("1") + ID("abc").
    let toks = lex_no_ws("1abc");
    let shape = shape(&toks, "1abc");
    assert_eq!(shape, vec![
        (TokenType::DECIMAL_LITERAL, "1"),
        (TokenType::ID, "abc"),
        (TokenType::EOF, ""),
    ]);
}

#[test]
fn tokenize_decimal_literal() {
    let cases = ["1", "42", "1234567890", "1L", "9l"];
    for src in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, TokenType::DECIMAL_LITERAL,
                   "expected DECIMAL_LITERAL for {src:?}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), src);
    }
}

#[test]
fn tokenize_hex_literal() {
    let cases = ["0x0", "0xFF", "0Xff", "0xDEADBEEF", "0xabc123"];
    for src in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, TokenType::HEX_LITERAL,
                   "expected HEX_LITERAL for {src:?}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), src);
    }
}

#[test]
fn tokenize_octal_literal() {
    // Per grammar: `0` followed by zero or more octal digits.
    let cases = ["0", "00", "0777", "01234567"];
    for src in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, TokenType::OCTAL_LITERAL,
                   "expected OCTAL_LITERAL for {src:?}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), src);
    }
}

#[test]
fn tokenize_floating_point_literal_variants() {
    // Three grammar productions: `digits.digits[exp]`, `.digits[exp]`,
    // `digits exp`.
    let cases = ["3.14", "5.", ".5", "5.5e10", "5.E10", ".5E10", "5e10",
                 "1.0e+5", "2.0E-3"];
    for src in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, TokenType::FLOATING_POINT_LITERAL,
                   "expected FLOATING_POINT_LITERAL for {src:?}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), src);
    }
}

#[test]
fn tokenize_string_literal_basic() {
    let src = "'hello'";
    let toks = lex_no_ws(src);
    assert_eq!(toks[0].kind, TokenType::STRING_LITERAL);
    assert_eq!(toks[0].text(src), "'hello'");
}

#[test]
fn tokenize_string_literal_empty() {
    let src = "''";
    let toks = lex_no_ws(src);
    assert_eq!(toks[0].kind, TokenType::STRING_LITERAL);
    assert_eq!(toks[0].text(src), "''");
}

#[test]
fn tokenize_string_literal_with_doubled_quote() {
    // The grammar treats `''` inside a string literal as an embedded
    // single quote (`it's`), not as a string terminator.
    let src = "'it''s'";
    let toks = lex_no_ws(src);
    assert_eq!(toks.len(), 2); // STRING_LITERAL + EOF
    assert_eq!(toks[0].kind, TokenType::STRING_LITERAL);
    assert_eq!(toks[0].text(src), "'it''s'");
}

#[test]
fn tokenize_string_literal_with_special_chars() {
    // Anything except `'` is allowed inside a string literal.
    let src = "'a%b_c\\d'";
    let toks = lex_no_ws(src);
    assert_eq!(toks[0].kind, TokenType::STRING_LITERAL);
    assert_eq!(toks[0].text(src), "'a%b_c\\d'");
}

// =================================================================
// Tokenization: operators
// =================================================================

#[test]
fn tokenize_comparison_and_equality_operators() {
    let cases: &[(&str, TokenType)] = &[
        ("=",  TokenType::_TOKEN_17),
        ("<>", TokenType::_TOKEN_18),
        ("!=", TokenType::_TOKEN_19),
        (">",  TokenType::_TOKEN_20),
        (">=", TokenType::_TOKEN_21),
        ("<",  TokenType::_TOKEN_22),
        ("<=", TokenType::_TOKEN_23),
    ];
    for (src, expected) in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, *expected,
                   "operator {src:?} should map to {expected}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), *src);
    }
}

#[test]
fn tokenize_arithmetic_operators() {
    let cases: &[(&str, TokenType)] = &[
        ("+", TokenType::_TOKEN_27),
        ("-", TokenType::_TOKEN_28),
        ("*", TokenType::_TOKEN_29),
        ("/", TokenType::_TOKEN_30),
        ("%", TokenType::_TOKEN_31),
    ];
    for (src, expected) in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, *expected,
                   "operator {src:?} should map to {expected}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), *src);
    }
}

#[test]
fn tokenize_punctuation() {
    let cases: &[(&str, TokenType)] = &[
        ("(", TokenType::_TOKEN_24),
        (",", TokenType::_TOKEN_25),
        (")", TokenType::_TOKEN_26),
    ];
    for (src, expected) in cases {
        let toks = lex_no_ws(src);
        assert_eq!(toks[0].kind, *expected,
                   "punctuation {src:?} should map to {expected}, got {:?}", toks[0].kind);
        assert_eq!(toks[0].text(src), *src);
    }
}

#[test]
fn tokenize_prefers_longest_match() {
    // ">=" must lex as a single 2-char token, NOT as ">" followed by
    // "=".  This exercises the longest-match rule in `get_match_info`.
    let src = ">=";
    let toks = lex_no_ws(src);
    assert_eq!(toks.len(), 2); // operator + EOF
    assert_eq!(toks[0].kind, TokenType::_TOKEN_21);
    assert_eq!(toks[0].text(src), ">=");

    // Same for "<=", "<>", and "!=".
    for (input, expected) in [
        ("<=", TokenType::_TOKEN_23),
        ("<>", TokenType::_TOKEN_18),
        ("!=", TokenType::_TOKEN_19),
    ] {
        let toks = lex_no_ws(input);
        assert_eq!(toks[0].kind, expected, "longest-match failed for {input:?}");
        assert_eq!(toks[0].text(input), input);
    }
}

// =================================================================
// Whitespace handling (UNPARSED tokens)
// =================================================================

#[test]
fn whitespace_produces_unparsed_tokens() {
    // The grammar declares the five whitespace forms as `UNPARSED`, so
    // they appear in tokens() with `is_unparsed: true` rather than
    // being silently dropped.
    let src = " \t\n\r\x0c";
    let tokens = lex(src);
    // 5 whitespace tokens + EOF.
    assert_eq!(tokens.len(), 6);
    assert_eq!(tokens[0].kind, TokenType::_TOKEN_1); // space
    assert_eq!(tokens[1].kind, TokenType::_TOKEN_2); // tab
    assert_eq!(tokens[2].kind, TokenType::_TOKEN_3); // LF
    assert_eq!(tokens[3].kind, TokenType::_TOKEN_4); // CR
    assert_eq!(tokens[4].kind, TokenType::_TOKEN_5); // FF
    assert_eq!(tokens[5].kind, TokenType::EOF);

    for t in &tokens[..5] {
        assert!(t.is_unparsed, "whitespace token should be marked unparsed");
        assert!(t.kind.is_unparsed());
    }
    assert!(!tokens[5].is_unparsed);
}

#[test]
fn whitespace_only_input_then_eof() {
    let tokens = lex("    ");
    // 4 spaces + EOF.
    assert_eq!(tokens.len(), 5);
    assert!(tokens[..4].iter().all(|t| t.kind == TokenType::_TOKEN_1));
    assert_eq!(tokens[4].kind, TokenType::EOF);
}

// =================================================================
// Comments (SKIPPED tokens)
// =================================================================

#[test]
fn line_comment_is_skipped_from_token_list() {
    // SKIPPED tokens (comments) do NOT appear in tokens().
    let src = "x -- a line comment\ny";
    let no_ws = lex_no_ws(src);
    let kinds: Vec<TokenType> = no_ws.iter().map(|t| t.kind).collect();
    assert_eq!(kinds, vec![TokenType::ID, TokenType::ID, TokenType::EOF]);
    assert_eq!(no_ws[0].text(src), "x");
    assert_eq!(no_ws[1].text(src), "y");
    // Confirm not present even in the unfiltered token list.
    let all = lex(src);
    assert!(all.iter().all(|t| t.kind != TokenType::LINE_COMMENT));
}

#[test]
fn block_comment_is_skipped_from_token_list() {
    let src = "x /* block\n   comment */ y";
    let no_ws = lex_no_ws(src);
    let kinds: Vec<TokenType> = no_ws.iter().map(|t| t.kind).collect();
    assert_eq!(kinds, vec![TokenType::ID, TokenType::ID, TokenType::EOF]);
    assert_eq!(no_ws[0].text(src), "x");
    assert_eq!(no_ws[1].text(src), "y");
    let all = lex(src);
    assert!(all.iter().all(|t| t.kind != TokenType::BLOCK_COMMENT));
}

#[test]
fn nested_block_comment_terminator_handling() {
    // The grammar is non-nesting: the FIRST `*/` closes the block,
    // even if `/*` appears inside.
    let src = "/* outer /* inner */ rest";
    let toks = lex_no_ws(src);
    // After the first `*/`, lexer resumes at "rest".
    let kinds: Vec<TokenType> = toks.iter().map(|t| t.kind).collect();
    assert_eq!(kinds, vec![TokenType::ID, TokenType::EOF]);
    assert_eq!(toks[0].text(src), "rest");
}

// =================================================================
// Composite expressions (smoke-test the tokenizer end to end)
// =================================================================

#[test]
fn tokenize_complex_boolean_expression() {
    let src = "name = 'foo' AND age >= 18 OR active = TRUE";
    let toks = lex_no_ws(src);
    let pairs = shape(&toks, src);
    assert_eq!(pairs, vec![
        (TokenType::ID,             "name"),
        (TokenType::_TOKEN_17,      "="),
        (TokenType::STRING_LITERAL, "'foo'"),
        (TokenType::AND,            "AND"),
        (TokenType::ID,             "age"),
        (TokenType::_TOKEN_21,      ">="),
        (TokenType::DECIMAL_LITERAL, "18"),
        (TokenType::OR,             "OR"),
        (TokenType::ID,             "active"),
        (TokenType::_TOKEN_17,      "="),
        (TokenType::TRUE,           "TRUE"),
        (TokenType::EOF,            ""),
    ]);
}

#[test]
fn tokenize_in_clause_with_string_list() {
    let src = "country IN ('US', 'CA')";
    let toks = lex_no_ws(src);
    let pairs = shape(&toks, src);
    assert_eq!(pairs, vec![
        (TokenType::ID,             "country"),
        (TokenType::IN,             "IN"),
        (TokenType::_TOKEN_24,      "("),
        (TokenType::STRING_LITERAL, "'US'"),
        (TokenType::_TOKEN_25,      ","),
        (TokenType::STRING_LITERAL, "'CA'"),
        (TokenType::_TOKEN_26,      ")"),
        (TokenType::EOF,            ""),
    ]);
}

// =================================================================
// Negative / edge-case tests
// =================================================================

#[test]
fn invalid_character_produces_invalid_token_and_recovers() {
    // `#` is not valid anywhere in this grammar — but the lexer must
    // not panic.  It emits an INVALID token of length 1 (in code-point
    // space) and continues so subsequent valid tokens are still
    // recognized.  This lets the parser surface a useful error rather
    // than aborting at the bad byte.
    let src = "a # b";
    let toks = lex_no_ws(src);
    let pairs = shape(&toks, src);
    assert_eq!(pairs, vec![
        (TokenType::ID,      "a"),
        (TokenType::INVALID, "#"),
        (TokenType::ID,      "b"),
        (TokenType::EOF,     ""),
    ]);
}

#[test]
fn run_of_invalid_chars_produces_one_invalid_per_char() {
    let src = "###";
    let toks = lex_no_ws(src);
    let kinds: Vec<TokenType> = toks.iter().map(|t| t.kind).collect();
    assert_eq!(kinds, vec![
        TokenType::INVALID,
        TokenType::INVALID,
        TokenType::INVALID,
        TokenType::EOF,
    ]);
    for t in &toks[..3] {
        assert_eq!(t.text(src), "#");
        assert!(!t.is_unparsed);
    }
}

#[test]
fn unterminated_string_does_not_panic() {
    // The grammar's STRING_LITERAL pattern requires a closing `'`.
    // Without one, the opening `'` becomes INVALID and the rest is
    // re-lexed as ordinary tokens.  This documents the recovery
    // behavior — the lexer must NOT hang or panic.
    let src = "'hello";
    let toks = lex(src);
    let kinds: Vec<TokenType> = toks.iter().map(|t| t.kind).collect();
    assert_eq!(kinds, vec![
        TokenType::INVALID, // opening quote
        TokenType::ID,      // "hello"
        TokenType::EOF,
    ]);
    assert_eq!(toks[0].text(src), "'");
    assert_eq!(toks[1].text(src), "hello");
}

#[test]
fn standalone_dot_is_invalid() {
    // A `.` not followed by digits is not a valid floating literal
    // and matches no other production, so it becomes INVALID.
    let src = ".";
    let toks = lex_no_ws(src);
    assert_eq!(toks[0].kind, TokenType::INVALID);
    assert_eq!(toks[0].text(src), ".");
}

#[test]
fn malformed_hex_literal_falls_back() {
    // "0x" with no following hex digits should NOT match HEX_LITERAL.
    // The "0" matches OCTAL_LITERAL and then 'x' starts an ID.
    let src = "0x";
    let toks = lex_no_ws(src);
    let pairs = shape(&toks, src);
    assert_eq!(pairs, vec![
        (TokenType::OCTAL_LITERAL, "0"),
        (TokenType::ID,            "x"),
        (TokenType::EOF,           ""),
    ]);
}

#[test]
fn eof_is_always_present_and_last() {
    // Every successful tokenization must end with exactly one EOF,
    // and EOF must not appear anywhere else in the list.
    for src in ["", " ", "abc", "1 + 2", "TRUE OR FALSE"] {
        let toks = lex(src);
        assert!(!toks.is_empty(), "no tokens for {src:?}");
        let last = toks.last().unwrap();
        assert_eq!(last.kind, TokenType::EOF, "last token must be EOF for {src:?}");
        let eof_count = toks.iter().filter(|t| t.kind == TokenType::EOF).count();
        assert_eq!(eof_count, 1, "exactly one EOF expected for {src:?}");
    }
}

#[test]
fn eof_byte_offsets_at_end_of_source() {
    let src = "abc";
    let toks = lex(src);
    let eof = toks.last().unwrap();
    assert_eq!(eof.kind, TokenType::EOF);
    assert_eq!(eof.start, 3);
    assert_eq!(eof.end, 3);
    assert_eq!(eof.text(src), "");
}

#[test]
fn tokens_have_strictly_non_decreasing_offsets() {
    let src = "name = 'foo' AND age >= 18";
    let toks = lex(src);
    for pair in toks.windows(2) {
        assert!(
            pair[0].end <= pair[1].start,
            "tokens overlap or go backwards: {:?} -> {:?}", pair[0], pair[1],
        );
    }
}

#[test]
fn token_type_does_not_match_inside_identifier() {
    // "AND2" should be a single ID, not the keyword AND followed by
    // the literal "2", because identifier matching is greedy.
    let src = "AND2";
    let toks = lex_no_ws(src);
    let pairs = shape(&toks, src);
    assert_eq!(pairs, vec![
        (TokenType::ID, "AND2"),
        (TokenType::EOF, ""),
    ]);
}

// =================================================================
// activate / deactivate / save / restore
// =================================================================

#[test]
fn save_active_tokens_returns_none_initially() {
    // The active-set is lazily allocated, so a fresh lexer reports
    // None — meaning "all token types are active".
    let lx = Lexer::new("abc", "test");
    assert!(lx.save_active_tokens().is_none());
}

#[test]
fn deactivate_without_initialized_set_is_noop() {
    // deactivate doesn't allocate the set on its own.  Saving still
    // returns None.
    let mut lx = Lexer::new("abc", "test");
    lx.deactivate_token_type(TokenType::ID);
    assert!(lx.save_active_tokens().is_none());
}

#[test]
fn activate_initializes_full_set() {
    // The first activate call allocates a set seeded with every
    // token type, then inserts the requested type (a no-op since it
    // was already in the seeded set).
    let mut lx = Lexer::new("abc", "test");
    lx.activate_token_type(TokenType::ID);
    let active = lx.save_active_tokens().expect("set should be initialized");
    assert!(active.contains(&TokenType::ID));
    // The seeded set covers indices 0..COUNT.
    assert_eq!(active.len(), TokenType::COUNT);
}

#[test]
fn activate_then_deactivate_removes_only_target() {
    let mut lx = Lexer::new("abc", "test");
    lx.activate_token_type(TokenType::ID); // initialize the set
    lx.deactivate_token_type(TokenType::AND);
    let active = lx.save_active_tokens().unwrap();
    assert!(!active.contains(&TokenType::AND), "AND should be removed");
    assert!(active.contains(&TokenType::OR), "OR should remain");
    assert!(active.contains(&TokenType::ID), "ID should remain");
    assert_eq!(active.len(), TokenType::COUNT - 1);
}

#[test]
fn save_returns_independent_clone() {
    // The saved snapshot must NOT alias the lexer's live set: further
    // mutations of the lexer should not be visible in the saved copy.
    let mut lx = Lexer::new("abc", "test");
    lx.activate_token_type(TokenType::ID);
    let snapshot = lx.save_active_tokens().unwrap();
    let snapshot_len = snapshot.len();
    lx.deactivate_token_type(TokenType::AND);
    assert_eq!(snapshot.len(), snapshot_len, "snapshot must not change");
    assert!(snapshot.contains(&TokenType::AND));
}

#[test]
fn restore_active_tokens_round_trip() {
    let mut lx = Lexer::new("abc", "test");
    lx.activate_token_type(TokenType::ID);
    let saved = lx.save_active_tokens();
    assert!(saved.is_some());

    lx.deactivate_token_type(TokenType::AND);
    assert!(!lx.save_active_tokens().unwrap().contains(&TokenType::AND));

    lx.restore_active_tokens(saved.clone(), 0);
    let now = lx.save_active_tokens().unwrap();
    assert!(now.contains(&TokenType::AND), "restore should re-include AND");
    assert_eq!(now.len(), TokenType::COUNT);
}

#[test]
fn restore_active_tokens_truncates_token_cache() {
    // The second argument tells restore_active_tokens how many of the
    // already-produced tokens to keep.  Passing 0 should clear them all.
    let mut lx = Lexer::new("a b c", "test");
    lx.tokenize().unwrap();
    assert!(lx.tokens().len() > 1);
    lx.restore_active_tokens(None, 0);
    assert!(lx.tokens().is_empty(), "all cached tokens should be cleared");
}

#[test]
fn restore_to_none_disables_filtering() {
    let mut lx = Lexer::new("abc", "test");
    lx.activate_token_type(TokenType::ID);
    assert!(lx.save_active_tokens().is_some());
    lx.restore_active_tokens(None, 0);
    assert!(lx.save_active_tokens().is_none(),
            "restoring None must drop the active-types set");
}

// =================================================================
// switch_to (lexical state)
// =================================================================

#[test]
fn switch_to_default_state_does_not_break_tokenization() {
    // This grammar declares only the DEFAULT lexical state, but
    // switch_to(DEFAULT) is still a valid public call.  Tokenization
    // after the switch should behave identically to no switch.
    let mut lx_a = Lexer::new("foo = 1", "test");
    lx_a.tokenize().unwrap();
    let baseline: Vec<TokenType> = lx_a.tokens().iter().map(|t| t.kind).collect();

    let mut lx_b = Lexer::new("foo = 1", "test");
    lx_b.switch_to(LexicalState::DEFAULT);
    lx_b.tokenize().unwrap();
    let after_switch: Vec<TokenType> = lx_b.tokens().iter().map(|t| t.kind).collect();

    assert_eq!(baseline, after_switch);
}

// =================================================================
// Token::clone / Debug
// =================================================================

#[test]
fn token_is_cloneable_and_debuggable() {
    // Token derives Clone + Debug, so we can copy it for inspection
    // and format it for diagnostics without touching internals.
    let toks = lex("abc");
    let original = &toks[0];
    let cloned: Token = original.clone();
    assert_eq!(original.kind, cloned.kind);
    assert_eq!(original.start, cloned.start);
    assert_eq!(original.end, cloned.end);
    assert_eq!(original.is_unparsed, cloned.is_unparsed);

    let dbg = format!("{:?}", original);
    assert!(dbg.contains("Token"));
    assert!(dbg.contains("kind"));
}
