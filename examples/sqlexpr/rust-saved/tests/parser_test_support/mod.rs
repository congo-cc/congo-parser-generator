//! Shared support for the migrated `parser_test.rs` and
//! `parser_test2.rs` files.
//!
//! ## Why this module exists
//!
//! These tests originated in the sibling project `sqlexpr-congo-rust`,
//! whose parser exposes:
//!
//! * a typed AST (`AstNode::EqualityExpression { operators: Vec<EqualityOp>, ... }`),
//! * an arena with `pretty_print(...)`, and
//! * an entry point `Parser::new(input).parse() -> NodeId` that ALSO
//!   performs semantic validation (boolean root, BETWEEN bounds,
//!   IN element types) inline as part of parsing.
//!
//! The CongoCC-generated parser in this project produces a flat
//! `Ast` keyed by `NodeKind`, with operator tokens interleaved among
//! the operand children, and only performs syntactic parsing (the
//! Java semantic-action INJECT block that enforces boolean root in
//! the source grammar is FIXME'd by the Rust translator).
//!
//! To make the migrated tests express the same intent without
//! changes, this module:
//!
//! * Mirrors the source's operator enums (`EqualityOp`,
//!   `ComparisonOp`, `AddOp`, `MultExprOp`, `UnaryOp`) so test
//!   bodies can use them verbatim.
//! * Provides `eq_ops`, `cmp_ops`, `add_ops`, `mult_ops`, and
//!   `unary_op` helpers that read the interleaved operator tokens
//!   out of a node and return the same enum sequences the source
//!   tests assert against.
//! * Provides `operand_children` so the source's
//!   `eq.children.len() == 2` assertions translate directly.
//! * Re-implements the source's `validate_boolean_root`,
//!   `validate_between_bounds`, and IN-element validators in pure
//!   Rust over the local `Ast`.  `parse_ok` and `parse_err` invoke
//!   them so the source's "parse error contains 'boolean'" /
//!   "BETWEEN bounds must be literal values" / "same type" / etc.
//!   assertions all behave identically to the source.

#![allow(dead_code, non_snake_case)]

use parser::ast::{Ast, NodeId, NodeKind};
use parser::error::ParseError;
use parser::parser::Parser;
use parser::pretty::{DefaultPrettyPrinter, PrettyPrinter};
use parser::tokens::TokenType;

// =================================================================
// Operator enums — mirror the source project's typed AST API.
// =================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EqualityOp {
    Equal,
    NotEqual,
    IsNull,
    IsNotNull,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ComparisonOp {
    GreaterThan,
    GreaterThanEqual,
    LessThan,
    LessThanEqual,
    Like,
    NotLike,
    LikeEscape,
    NotLikeEscape,
    Between,
    NotBetween,
    In,
    NotIn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddOp {
    Plus,
    Minus,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MultExprOp {
    Star,
    Slash,
    Percent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnaryOp {
    Plus,
    Negate,
    Not,
}

// =================================================================
// Top-level entry points: parse_ok / parse_err
// =================================================================

/// Parse `input`, expecting success.  Performs the same semantic
/// validation the source's `Parser::parse` performs inline (boolean
/// root, BETWEEN bounds, IN elements); panics with diagnostic info
/// on either kind of failure.  Returns the `Ast` and the root node id.
///
/// Prints the pretty-printed AST so that `cargo test -- --nocapture`
/// produces output equivalent to the source's `arena.pretty_print(...)`.
pub fn parse_ok(input: &str) -> (Ast, NodeId) {
    let ast = Parser::parse(input, Some("test")).unwrap_or_else(|e| {
        panic!("parse failed for {input:?}: {e}");
    });
    let root = ast.root().expect("parsed ast must have a root");
    println!("{}", DefaultPrettyPrinter.pretty_print(&ast));
    if let Err(msg) = validate_semantic(&ast, root) {
        panic!("semantic validation failed for {input:?}: {msg}");
    }
    (ast, root)
}

/// Parse `input`, expecting either a syntactic parse error OR a
/// semantic-validation error.  Panics if the input parses AND
/// validates cleanly; otherwise returns the error message.
pub fn parse_err(input: &str) -> String {
    match Parser::parse(input, Some("test")) {
        Err(e) => {
            let msg = format!("{e}");
            println!("Error for {input:?}: {msg}");
            msg
        }
        Ok(ast) => {
            let root = ast.root().expect("parsed ast must have a root");
            match validate_semantic(&ast, root) {
                Err(msg) => {
                    println!("Error for {input:?}: {msg}");
                    msg
                }
                Ok(()) => panic!("expected parse error for {input:?}"),
            }
        }
    }
}

/// Convenience wrapper for tests that don't need the AST.
pub fn parse(input: &str) -> Result<(), ParseError> {
    let ast = Parser::parse(input, Some("test"))?;
    if let Some(root) = ast.root() {
        println!("{}", DefaultPrettyPrinter.pretty_print(&ast));
        if let Err(msg) = validate_semantic(&ast, root) {
            return Err(ParseError::new(msg));
        }
    }
    Ok(())
}

// =================================================================
// AST navigation helpers
// =================================================================

/// Walk through pass-through (single-child, no operator) wrappers
/// to reach the semantically meaningful node.  Mirrors the source's
/// `skip()` helper.
pub fn skip(ast: &Ast, mut id: NodeId) -> NodeId {
    loop {
        let kind = ast.kind(id).clone();
        let children: Vec<NodeId> = ast.children(id).collect();
        let dominated = match kind {
            NodeKind::JmsSelector => {
                // JmsSelector wraps the orExpression and the EOF token.
                // Skip into the orExpression child.
                operand_children(ast, id).len() == 1
            }
            NodeKind::orExpression
            | NodeKind::andExpression => operand_children(ast, id).len() == 1,
            NodeKind::equalityExpression
            | NodeKind::comparisonExpression
            | NodeKind::addExpression
            | NodeKind::multExpr => {
                operand_children(ast, id).len() == 1 && operator_token_count(ast, id) == 0
            }
            NodeKind::unaryExpr => {
                // A unaryExpr node is "pass-through" only when it has
                // no leading operator token (i.e., `+expr` / `-expr`
                // / `NOT expr` are NOT pass-throughs).
                children.len() == 1 && !is_token(ast, children[0])
            }
            _ => false,
        };
        if dominated {
            // Descend to the first non-token (or only) child.
            let next = if matches!(kind, NodeKind::JmsSelector) {
                operand_children(ast, id)[0]
            } else if matches!(kind, NodeKind::unaryExpr) {
                children[0]
            } else {
                operand_children(ast, id)[0]
            };
            id = next;
        } else {
            return id;
        }
    }
}

/// Direct children that are NOT token leaves (i.e., the operand
/// sub-expressions, with operator tokens filtered out).  Equivalent
/// to source's `node.children` for chained expressions.
pub fn operand_children(ast: &Ast, id: NodeId) -> Vec<NodeId> {
    ast.children(id).filter(|&c| !is_token(ast, c)).collect()
}

/// All direct children, in order.
pub fn all_children(ast: &Ast, id: NodeId) -> Vec<NodeId> {
    ast.children(id).collect()
}

fn operator_token_count(ast: &Ast, id: NodeId) -> usize {
    ast.children(id).filter(|&c| is_token(ast, c)).count()
}

pub fn is_token(ast: &Ast, id: NodeId) -> bool {
    matches!(ast.kind(id), NodeKind::Token(_))
}

/// Token text for a leaf, or descend through the wrapper chain to
/// the underlying token.  Source's `leaf_image()`.
///
/// Handles parenthesized primaries by descending into the inner
/// orExpression rather than returning the `(` token text.
pub fn leaf_image(ast: &Ast, id: NodeId) -> String {
    let id = skip(ast, id);
    match ast.kind(id) {
        NodeKind::Token(_) => ast.text(id).to_string(),
        NodeKind::primaryExpr => {
            // Either single-child wrapper, or `( orExpression )`.  In
            // both cases, recurse into the first non-token child so
            // we don't return `(`.
            if let Some(inner) = ast.children(id).find(|&c| !is_token(ast, c)) {
                leaf_image(ast, inner)
            } else {
                // Token-only primary (shouldn't happen given the grammar).
                ast.children(id).next().map(|c| ast.text(c).to_string()).unwrap_or_default()
            }
        }
        NodeKind::variable | NodeKind::literal | NodeKind::stringLitteral | NodeKind::inElement => {
            // Descend to the first child whose text is non-empty.
            for c in ast.children(id) {
                let s = leaf_image(ast, c);
                if !s.is_empty() {
                    return s;
                }
            }
            String::new()
        }
        _ => {
            // Fall through: walk descendants, skipping `(` and EOF tokens.
            for d in ast.descendants(id) {
                if let NodeKind::Token(_) = ast.kind(d) {
                    let tt = ast.token_type(d);
                    if tt == Some(TokenType::EOF) || tt == Some(TokenType::_TOKEN_24) {
                        continue;
                    }
                    return ast.text(d).to_string();
                }
            }
            String::new()
        }
    }
}

/// Source's `primary_value()` — same as `leaf_image` for our flat
/// AST shape (the source's distinction matters only for its typed
/// PrimaryExpr variant).
pub fn primary_value(ast: &Ast, id: NodeId) -> String {
    leaf_image(ast, id)
}

// =================================================================
// Operator extraction — read interleaved operator tokens.
// =================================================================

/// Extract the sequence of equality operators from an
/// `equalityExpression` node.  Recognizes `=`, `<>`, `!=`,
/// `IS NULL` (two tokens), and `IS NOT NULL` (three tokens).
pub fn eq_ops(ast: &Ast, id: NodeId) -> Vec<EqualityOp> {
    let mut ops = Vec::new();
    let children: Vec<NodeId> = ast.children(id).collect();
    let mut i = 0;
    while i < children.len() {
        if !is_token(ast, children[i]) {
            i += 1;
            continue;
        }
        let tt = ast.token_type(children[i]).expect("token must have type");
        match tt {
            TokenType::_TOKEN_17 => { ops.push(EqualityOp::Equal); i += 1; }
            TokenType::_TOKEN_18 | TokenType::_TOKEN_19 => {
                // <>  or  != — both NotEqual.
                ops.push(EqualityOp::NotEqual);
                i += 1;
            }
            TokenType::IS => {
                // IS NULL  or  IS NOT NULL.
                let next = ast.token_type(children[i + 1]).expect("IS must be followed by NULL or NOT");
                if next == TokenType::NOT {
                    ops.push(EqualityOp::IsNotNull);
                    i += 3;
                } else {
                    ops.push(EqualityOp::IsNull);
                    i += 2;
                }
            }
            _ => i += 1,
        }
    }
    ops
}

/// Extract the sequence of comparison operators from a
/// `comparisonExpression` node.  Recognizes `>`, `>=`, `<`, `<=`,
/// `LIKE`, `LIKE … ESCAPE`, `NOT LIKE`, `NOT LIKE … ESCAPE`,
/// `BETWEEN`, `NOT BETWEEN`, `IN`, `NOT IN`.
pub fn cmp_ops(ast: &Ast, id: NodeId) -> Vec<ComparisonOp> {
    let mut ops = Vec::new();
    let children: Vec<NodeId> = ast.children(id).collect();
    let mut i = 0;
    while i < children.len() {
        if !is_token(ast, children[i]) {
            i += 1;
            continue;
        }
        let tt = ast.token_type(children[i]).expect("token must have type");
        match tt {
            // `>` was _TOKEN_19 before `!=` was added; it is now _TOKEN_20.
            TokenType::_TOKEN_20 => { ops.push(ComparisonOp::GreaterThan); i += 1; }
            TokenType::_TOKEN_21 => { ops.push(ComparisonOp::GreaterThanEqual); i += 1; }
            TokenType::_TOKEN_22 => { ops.push(ComparisonOp::LessThan); i += 1; }
            TokenType::_TOKEN_23 => { ops.push(ComparisonOp::LessThanEqual); i += 1; }
            TokenType::LIKE => {
                // LIKE pat  or  LIKE pat ESCAPE escchar
                let has_escape = children
                    .iter()
                    .skip(i + 1)
                    .take_while(|&&c| !is_logical_break_token(ast, c))
                    .any(|&c| ast.token_type(c) == Some(TokenType::ESCAPE));
                ops.push(if has_escape { ComparisonOp::LikeEscape } else { ComparisonOp::Like });
                i += 1;
            }
            TokenType::NOT => {
                // NOT may prefix LIKE, BETWEEN, or IN.
                let next = ast.token_type(children[i + 1]).expect("NOT must be followed by an operator");
                match next {
                    TokenType::LIKE => {
                        let has_escape = children
                            .iter()
                            .skip(i + 2)
                            .take_while(|&&c| !is_logical_break_token(ast, c))
                            .any(|&c| ast.token_type(c) == Some(TokenType::ESCAPE));
                        ops.push(if has_escape { ComparisonOp::NotLikeEscape } else { ComparisonOp::NotLike });
                        i += 2;
                    }
                    TokenType::BETWEEN => { ops.push(ComparisonOp::NotBetween); i += 2; }
                    TokenType::IN => { ops.push(ComparisonOp::NotIn); i += 2; }
                    _ => i += 1,
                }
            }
            TokenType::BETWEEN => { ops.push(ComparisonOp::Between); i += 1; }
            TokenType::IN => { ops.push(ComparisonOp::In); i += 1; }
            _ => i += 1,
        }
    }
    ops
}

fn is_logical_break_token(ast: &Ast, id: NodeId) -> bool {
    // Used inside `cmp_ops` to bound LIKE … ESCAPE detection: an
    // outer AND (e.g., from BETWEEN) is not a break, since BETWEEN's
    // `AND` is a child of the comparison node — but ESCAPE never
    // appears past it in practice.  This conservative check stops
    // scanning at certain non-comparison-internal tokens.
    matches!(
        ast.token_type(id),
        Some(TokenType::EOF) | Some(TokenType::OR)
    )
}

/// Extract the sequence of additive operators (`+` / `-`) from an
/// `addExpression` node.
pub fn add_ops(ast: &Ast, id: NodeId) -> Vec<AddOp> {
    let mut ops = Vec::new();
    for c in ast.children(id) {
        if let Some(tt) = ast.token_type(c) {
            match tt {
                TokenType::_TOKEN_27 => ops.push(AddOp::Plus),
                TokenType::_TOKEN_28 => ops.push(AddOp::Minus),
                _ => {}
            }
        }
    }
    ops
}

/// Extract the sequence of multiplicative operators
/// (`*` / `/` / `%`) from a `multExpr` node.
pub fn mult_ops(ast: &Ast, id: NodeId) -> Vec<MultExprOp> {
    let mut ops = Vec::new();
    for c in ast.children(id) {
        if let Some(tt) = ast.token_type(c) {
            match tt {
                TokenType::_TOKEN_29 => ops.push(MultExprOp::Star),
                TokenType::_TOKEN_30 => ops.push(MultExprOp::Slash),
                TokenType::_TOKEN_31 => ops.push(MultExprOp::Percent),
                _ => {}
            }
        }
    }
    ops
}

/// Extract the optional unary operator from a `unaryExpr` node.
pub fn unary_op(ast: &Ast, id: NodeId) -> Option<UnaryOp> {
    let children: Vec<NodeId> = ast.children(id).collect();
    if let Some(&first) = children.first() {
        if let Some(tt) = ast.token_type(first) {
            return match tt {
                TokenType::_TOKEN_27 => Some(UnaryOp::Plus),
                TokenType::_TOKEN_28 => Some(UnaryOp::Negate),
                TokenType::NOT => Some(UnaryOp::Not),
                _ => None,
            };
        }
    }
    None
}

/// `true` if `id` is a `unaryExpr` carrying a non-None operator.
/// Source uses this in `is_boolean_expression` to decide whether
/// `unaryExpr` is a pass-through.
pub fn unary_has_operator(ast: &Ast, id: NodeId) -> bool {
    matches!(ast.kind(id), NodeKind::unaryExpr) && unary_op(ast, id).is_some()
}

// =================================================================
// Semantic validation (mirrors source's parser checks).
// =================================================================

fn validate_semantic(ast: &Ast, root: NodeId) -> Result<(), String> {
    // Boolean-root check (source: `validate_boolean_root`).
    if !is_boolean_expression(ast, root) {
        return Err("Expression must be boolean (comparison, logical, or boolean literal)"
            .to_string());
    }
    // Walk the tree and validate every BETWEEN/IN we find.
    validate_recursive(ast, root)
}

fn validate_recursive(ast: &Ast, id: NodeId) -> Result<(), String> {
    if let NodeKind::comparisonExpression = ast.kind(id) {
        validate_comparison_ops(ast, id)?;
    }
    for c in ast.children(id) {
        validate_recursive(ast, c)?;
    }
    Ok(())
}

fn validate_comparison_ops(ast: &Ast, id: NodeId) -> Result<(), String> {
    // Walk children sequentially and validate any BETWEEN / NOT BETWEEN /
    // IN / NOT IN clause we encounter.
    let children: Vec<NodeId> = ast.children(id).collect();
    let mut i = 0;
    while i < children.len() {
        let c = children[i];
        let tt = ast.token_type(c);
        match tt {
            Some(TokenType::BETWEEN) => {
                // Operands at i+1 (low) and i+3 (high), separated by AND at i+2.
                let low = children[i + 1];
                let high = children[i + 3];
                validate_between_bounds(ast, low, high)?;
                i += 4;
            }
            Some(TokenType::NOT) => {
                let next = ast.token_type(children[i + 1]);
                match next {
                    Some(TokenType::BETWEEN) => {
                        let low = children[i + 2];
                        let high = children[i + 4];
                        validate_between_bounds(ast, low, high)?;
                        i += 5;
                    }
                    Some(TokenType::IN) => {
                        // NOT IN ( elem , elem ... )
                        let elem_start = i + 3;
                        let elem_end = find_matching_rparen(ast, &children, i + 2)?;
                        validate_in_elements(ast, &children[elem_start..elem_end])?;
                        i = elem_end + 1;
                    }
                    _ => i += 1,
                }
            }
            Some(TokenType::IN) => {
                let elem_start = i + 2;
                let elem_end = find_matching_rparen(ast, &children, i + 1)?;
                validate_in_elements(ast, &children[elem_start..elem_end])?;
                i = elem_end + 1;
            }
            _ => i += 1,
        }
    }
    Ok(())
}

fn find_matching_rparen(
    ast: &Ast,
    children: &[NodeId],
    lparen_idx: usize,
) -> Result<usize, String> {
    // Children at lparen_idx should be `(`, scan forward to the matching `)`.
    for j in lparen_idx + 1..children.len() {
        if ast.token_type(children[j]) == Some(TokenType::_TOKEN_26) {
            return Ok(j);
        }
    }
    Err("missing closing ) in IN list".to_string())
}

fn validate_between_bounds(ast: &Ast, low: NodeId, high: NodeId) -> Result<(), String> {
    // 1. Each bound must be a literal (allowed: signed numeric or string literal).
    let low_kind = literal_kind(ast, low)
        .ok_or_else(|| describe_non_literal_bound(ast, low))?;
    let high_kind = literal_kind(ast, high)
        .ok_or_else(|| describe_non_literal_bound(ast, high))?;

    // 2. Same type-category check.
    if low_kind.is_string() != high_kind.is_string() {
        let low_text = bound_image(ast, low);
        let high_text = bound_image(ast, high);
        let low_name = if low_kind.is_string() { "string" } else { "integer" };
        let high_name = if high_kind.is_string() { "string" } else { "integer" };
        return Err(format!(
            "BETWEEN bounds must be the same type (both numeric or both string): \
             found {} ('{}') and {} ('{}')",
            low_name, low_text, high_name, high_text,
        ));
    }
    // 3. Lower <= upper.
    if low_kind.is_numeric() {
        let low_val = parse_numeric(&strip_sign(&bound_image(ast, low)).0)
            .map_err(|_| format!("Invalid numeric literal in BETWEEN: '{}'", bound_image(ast, low)))?;
        let high_val = parse_numeric(&strip_sign(&bound_image(ast, high)).0)
            .map_err(|_| format!("Invalid numeric literal in BETWEEN: '{}'", bound_image(ast, high)))?;
        let low_signed = signed_value(ast, low, low_val);
        let high_signed = signed_value(ast, high, high_val);
        if low_signed > high_signed {
            return Err(format!(
                "BETWEEN lower bound ({}) must not exceed upper bound ({})",
                bound_image(ast, low), bound_image(ast, high),
            ));
        }
    } else {
        let low_text = bound_image(ast, low);
        let high_text = bound_image(ast, high);
        // Strip surrounding quotes (we only get here if both are STRING_LITERAL).
        let low_inner = &low_text[1..low_text.len() - 1];
        let high_inner = &high_text[1..high_text.len() - 1];
        if low_inner > high_inner {
            return Err(format!(
                "BETWEEN lower bound ({}) must not exceed upper bound ({})",
                low_text, high_text,
            ));
        }
    }
    Ok(())
}

fn describe_non_literal_bound(ast: &Ast, id: NodeId) -> String {
    let id_skipped = skip(ast, id);
    // Find a representative inner token to identify what was found.
    let inner_text = leaf_image(ast, id_skipped);
    match find_first_token_type(ast, id_skipped) {
        Some(TokenType::ID) => "BETWEEN bounds must be literal values, not variables".to_string(),
        Some(TokenType::TRUE) | Some(TokenType::FALSE) => format!(
            "BETWEEN bounds may not be boolean values, found '{}'",
            inner_text,
        ),
        Some(TokenType::NULL) => format!(
            "BETWEEN bounds may not be NULL, found '{}'",
            inner_text,
        ),
        _ => format!(
            "BETWEEN bounds must be literal values (numeric or string), found '{}'",
            inner_text,
        ),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LiteralKind {
    Integer,
    Float,
    StringLit,
}

impl LiteralKind {
    fn is_string(self) -> bool { matches!(self, LiteralKind::StringLit) }
    fn is_numeric(self) -> bool { matches!(self, LiteralKind::Integer | LiteralKind::Float) }
    fn name(self) -> &'static str {
        match self {
            LiteralKind::Integer => "integer",
            LiteralKind::Float => "float",
            LiteralKind::StringLit => "string",
        }
    }
}

/// Return Some(LiteralKind) if `id` (after pass-through skipping) is
/// a literal node that BETWEEN/IN accepts (number, string, or signed
/// number).  Returns None if it's a variable, expression, etc.
fn literal_kind(ast: &Ast, id: NodeId) -> Option<LiteralKind> {
    let id = skip(ast, id);
    // Allowed shapes:
    //   literal -> Token(numeric or string or TRUE/FALSE/NULL)
    //   primaryExpr -> literal -> ...
    //   unaryExpr (+/-) wrapping a literal
    //   inElement -> literal  OR  inElement -> +/- literal
    match ast.kind(id) {
        NodeKind::inElement => {
            // `+ literal`, `- literal`, or just `literal`.  Find the literal
            // operand (the only non-token child).
            let operand = ast.children(id).find(|&c| !is_token(ast, c))?;
            literal_kind(ast, operand)
        }
        NodeKind::unaryExpr => {
            let op = unary_op(ast, id)?;
            if !matches!(op, UnaryOp::Plus | UnaryOp::Negate) {
                return None;
            }
            let children: Vec<NodeId> = ast.children(id).collect();
            // operand is the non-token child after the operator
            let operand = children.iter().find(|&&c| !is_token(ast, c))?;
            literal_kind(ast, *operand)
        }
        NodeKind::primaryExpr => {
            let inner: Vec<NodeId> = ast.children(id).collect();
            if inner.len() == 1 {
                literal_kind(ast, inner[0])
            } else {
                None // parenthesized expression — not a literal
            }
        }
        NodeKind::literal => {
            let inner: Vec<NodeId> = ast.children(id).collect();
            // literal contains either a numeric/keyword token (TRUE/FALSE/NULL)
            // OR a stringLitteral node.
            if inner.is_empty() {
                return None;
            }
            // If first child is a Token, classify it.
            if let NodeKind::Token(_) = ast.kind(inner[0]) {
                match ast.token_type(inner[0])? {
                    TokenType::DECIMAL_LITERAL => {
                        let txt = ast.text(inner[0]);
                        if txt.contains('.') {
                            Some(LiteralKind::Float)
                        } else {
                            Some(LiteralKind::Integer)
                        }
                    }
                    TokenType::HEX_LITERAL | TokenType::OCTAL_LITERAL => Some(LiteralKind::Integer),
                    TokenType::FLOATING_POINT_LITERAL => Some(LiteralKind::Float),
                    TokenType::TRUE | TokenType::FALSE | TokenType::NULL => None,
                    _ => None,
                }
            } else if matches!(ast.kind(inner[0]), NodeKind::stringLitteral) {
                Some(LiteralKind::StringLit)
            } else {
                None
            }
        }
        NodeKind::stringLitteral => Some(LiteralKind::StringLit),
        _ => None,
    }
}

/// Return the textual image of a (possibly signed) literal bound.
/// Source's `get_literal_image` — recurses through wrappers and
/// preserves a leading `-` if present.
fn bound_image(ast: &Ast, id: NodeId) -> String {
    let id = skip(ast, id);
    match ast.kind(id) {
        NodeKind::unaryExpr => {
            let op = unary_op(ast, id);
            let operand_id = ast.children(id).find(|&c| !is_token(ast, c)).unwrap();
            let inner = bound_image(ast, operand_id);
            match op {
                Some(UnaryOp::Negate) => format!("-{}", inner),
                _ => inner,
            }
        }
        _ => leaf_image(ast, id),
    }
}

/// Strip a leading +/- and return (rest, is_negative).
fn strip_sign(s: &str) -> (String, bool) {
    if let Some(rest) = s.strip_prefix('-') { (rest.to_string(), true) }
    else if let Some(rest) = s.strip_prefix('+') { (rest.to_string(), false) }
    else { (s.to_string(), false) }
}

/// Adjust `value` based on whether the bound has a leading `-`.
fn signed_value(ast: &Ast, id: NodeId, value: f64) -> f64 {
    let id = skip(ast, id);
    if let NodeKind::unaryExpr = ast.kind(id) {
        if unary_op(ast, id) == Some(UnaryOp::Negate) {
            return -value;
        }
    }
    value
}

/// Parse a numeric literal image (decimal, hex, or octal) to f64.
/// Mirrors source's `parse_numeric_literal`.
fn parse_numeric(image: &str) -> Result<f64, String> {
    let image = image
        .strip_suffix('L')
        .or_else(|| image.strip_suffix('l'))
        .unwrap_or(image);
    if let Some(hex) = image.strip_prefix("0x").or_else(|| image.strip_prefix("0X")) {
        i64::from_str_radix(hex, 16).map(|i| i as f64).map_err(|e| e.to_string())
    } else if image.starts_with('0')
        && image.len() > 1
        && image[1..].chars().all(|c| ('0'..='7').contains(&c))
    {
        let oct = &image[1..];
        i64::from_str_radix(oct, 8).map(|i| i as f64).map_err(|e| e.to_string())
    } else {
        image.parse::<f64>().map_err(|e| e.to_string())
    }
}

/// Find the first non-EOF token type in this sub-tree.
fn find_first_token_type(ast: &Ast, id: NodeId) -> Option<TokenType> {
    if let NodeKind::Token(_) = ast.kind(id) {
        return ast.token_type(id);
    }
    for d in ast.descendants(id) {
        if let NodeKind::Token(_) = ast.kind(d) {
            if let Some(tt) = ast.token_type(d) {
                if tt != TokenType::EOF {
                    return Some(tt);
                }
            }
        }
    }
    None
}

fn validate_in_elements(ast: &Ast, slice: &[NodeId]) -> Result<(), String> {
    // `slice` contains the element nodes interleaved with comma tokens.
    let mut elems: Vec<NodeId> = Vec::new();
    for &c in slice {
        if !is_token(ast, c) {
            elems.push(c);
        }
    }
    if elems.is_empty() {
        return Err("IN list elements must be literal values (string, integer, or float), found nothing".to_string());
    }
    let mut first_kind: Option<LiteralKind> = None;
    for elem in &elems {
        // Each element must be a string or numeric literal (signed
        // numeric is OK).  Booleans and NULL are explicitly disallowed
        // with their own error message.
        if let Some(tt) = find_first_token_type(ast, *elem) {
            match tt {
                TokenType::TRUE | TokenType::FALSE => {
                    return Err("Boolean is not allowed in IN list elements".to_string());
                }
                TokenType::NULL => {
                    return Err("NULL is not allowed in IN list elements".to_string());
                }
                _ => {}
            }
        }
        let kind = literal_kind(ast, *elem).ok_or_else(|| format!(
            "IN list elements must be literal values (string, integer, or float), found '{}'",
            leaf_image(ast, *elem),
        ))?;
        match first_kind {
            None => first_kind = Some(kind),
            Some(first) => {
                let compatible = (first.is_string() && kind.is_string())
                    || (first == LiteralKind::Integer && kind == LiteralKind::Integer)
                    || (first == LiteralKind::Float && kind == LiteralKind::Float);
                if !compatible {
                    return Err(format!(
                        "IN list elements must all be the same type: first element is {}, but found {} '{}'",
                        first.name(), kind.name(), leaf_image(ast, *elem),
                    ));
                }
            }
        }
    }
    Ok(())
}

// =================================================================
// is_boolean_expression — port of source's `is_boolean_expression`.
// =================================================================

pub fn is_boolean_expression(ast: &Ast, id: NodeId) -> bool {
    match ast.kind(id) {
        NodeKind::JmsSelector => {
            // Pass-through to the orExpression child.
            let kids = operand_children(ast, id);
            kids.first().map_or(false, |&c| is_boolean_expression(ast, c))
        }
        NodeKind::orExpression | NodeKind::andExpression => {
            let kids = operand_children(ast, id);
            if kids.len() > 1 { return true; }
            if kids.len() == 1 { return is_boolean_expression(ast, kids[0]); }
            false
        }
        NodeKind::equalityExpression => {
            if !eq_ops(ast, id).is_empty() { return true; }
            let kids = operand_children(ast, id);
            if kids.len() == 1 { return is_boolean_expression(ast, kids[0]); }
            false
        }
        NodeKind::comparisonExpression => {
            if !cmp_ops(ast, id).is_empty() { return true; }
            let kids = operand_children(ast, id);
            if kids.len() == 1 { return is_boolean_expression(ast, kids[0]); }
            false
        }
        NodeKind::addExpression => {
            let kids = operand_children(ast, id);
            if kids.len() == 1 && add_ops(ast, id).is_empty() {
                return is_boolean_expression(ast, kids[0]);
            }
            false
        }
        NodeKind::multExpr => {
            let kids = operand_children(ast, id);
            if kids.len() == 1 && mult_ops(ast, id).is_empty() {
                return is_boolean_expression(ast, kids[0]);
            }
            false
        }
        NodeKind::unaryExpr => {
            let op = unary_op(ast, id);
            if op == Some(UnaryOp::Not) { return true; }
            if op.is_none() {
                // Pass-through unary: descend.
                let kids: Vec<NodeId> = ast.children(id).collect();
                if kids.len() == 1 { return is_boolean_expression(ast, kids[0]); }
            }
            false // unary +/- is arithmetic
        }
        NodeKind::primaryExpr => {
            // Single-child primaryExpr is a pass-through.  Two-child
            // primaryExpr is `( orExpression )` — boolean iff the
            // orExpression is boolean.  Three-child primaryExpr is
            // `( … )` parenthesizing an expression.
            let all: Vec<NodeId> = ast.children(id).collect();
            if all.len() == 1 {
                return is_boolean_expression(ast, all[0]);
            }
            // Parenthesized — the orExpression is the middle operand.
            let inner = operand_children(ast, id);
            if let Some(&c) = inner.first() {
                return is_boolean_expression(ast, c);
            }
            false
        }
        NodeKind::variable => true, // Variables are assumed boolean when standalone.
        NodeKind::literal => {
            // TRUE / FALSE only.
            let kids: Vec<NodeId> = ast.children(id).collect();
            if let Some(&first) = kids.first() {
                if let NodeKind::Token(_) = ast.kind(first) {
                    return matches!(
                        ast.token_type(first),
                        Some(TokenType::TRUE) | Some(TokenType::FALSE),
                    );
                }
            }
            false
        }
        _ => false,
    }
}
