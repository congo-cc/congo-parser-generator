//! In-place AST number-literal replacement with synthesized subtrees.
//!
//! Demonstrates a more involved use of the synthetic-token and
//! in-place mutation API: every occurrence of a numeric literal is
//! replaced by a parenthesized expression that evaluates to the same
//! value.  Specifically:
//!
//!   - `5`  →  `(2+3)`
//!   - `6`  →  `(2*3)`
//!
//! Because the replacement expressions evaluate to the same numbers
//! they replace, the overall expression value is unchanged.  The test
//! verifies this invariant, dumps the AST before and after, and
//! round-trips through unparse/reparse.
//!
//! Run with output:
//!     cargo test --test synthetic_struct3_test -- --nocapture

use ex2::ast::{Ast, NodeId, NodeKind};
use ex2::parser::Parser;
use ex2::tokens::TokenType;

/// Test expressions and their expected values.
///
/// Because 2+3 = 5 and 2*3 = 6, the value is the same before and
/// after replacement.
///
///   "5+6*(2*5)"      = 5 + 60 = 65
///   "2*6-(5+1)/3"    = 12 - 2  = 10
const TEST_CASES: &[(&str, f64)] = &[
    ("5+6*(2*5)",    65.0),
    ("2*6-(5+1)/3",  10.0),
];

/// Expected unparse output after replacement.
const EXPECTED_UNPARSE: &[&str] = &[
    "(2+3)+(2*3)*(2*(2+3))",
    "2*(2*3)-((2+3)+1)/3",
];

// ── Helpers: find NUMBER nodes by text ──────────────────────────────

/// Collects all NUMBER token node IDs whose text equals `target`.
fn find_numbers(ast: &Ast, target: &str) -> Vec<NodeId> {
    let mut result = Vec::new();
    if let Some(root) = ast.root() {
        collect_numbers(ast, root, target, &mut result);
    }
    result
}

fn collect_numbers(ast: &Ast, id: NodeId, target: &str, out: &mut Vec<NodeId>) {
    if let NodeKind::Token(_) = ast.kind(id) {
        if ast.text(id) == target {
            out.push(id);
        }
    }
    for child in ast.children(id) {
        collect_numbers(ast, child, target, out);
    }
}

// ── Helpers: build synthetic subtrees ───────────────────────────────

/// Replaces `target` (a NUMBER token node) with a synthetic `(2+3)`
/// subtree.
///
/// In this grammar, bare NUMBER tokens are direct children of
/// MultiplicativeExpression — they are NOT wrapped in a
/// ParentheticalExpression.  The replacement must therefore be a
/// complete ParentheticalExpression node that takes the NUMBER's
/// place in its parent's child list.
///
/// Synthesized tree structure:
///
/// ```text
/// ParentheticalExpression        ← replaces the NUMBER node
///   ├── OPEN_PAREN  "("
///   ├── AdditiveExpression
///   │   ├── MultiplicativeExpression
///   │   │   └── NUMBER  "2"
///   │   ├── PLUS  "+"
///   │   └── MultiplicativeExpression
///   │       └── NUMBER  "3"
///   └── CLOSE_PAREN  ")"
/// ```
fn replace_with_two_plus_three(ast: &mut Ast, target: NodeId) {
    let open  = ast.new_synthetic_token(TokenType::OPEN_PAREN, "(");
    let close = ast.new_synthetic_token(TokenType::CLOSE_PAREN, ")");

    let two   = ast.new_synthetic_token(TokenType::NUMBER, "2");
    let me_2  = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(me_2, two);

    let plus  = ast.new_synthetic_token(TokenType::PLUS, "+");

    let three = ast.new_synthetic_token(TokenType::NUMBER, "3");
    let me_3  = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(me_3, three);

    let add   = ast.new_node(NodeKind::AdditiveExpression);
    ast.append_child(add, me_2);
    ast.append_child(add, plus);
    ast.append_child(add, me_3);

    let paren = ast.new_node(NodeKind::ParentheticalExpression);
    ast.append_child(paren, open);
    ast.append_child(paren, add);
    ast.append_child(paren, close);

    ast.insert_after(target, paren);
    ast.detach(target);
}

/// Replaces `target` (a NUMBER token node) with a synthetic `(2*3)`
/// subtree.  See `replace_with_two_plus_three` for rationale.
///
/// Synthesized tree structure:
///
/// ```text
/// ParentheticalExpression        ← replaces the NUMBER node
///   ├── OPEN_PAREN  "("
///   ├── AdditiveExpression
///   │   └── MultiplicativeExpression
///   │       ├── NUMBER  "2"
///   │       ├── TIMES  "*"
///   │       └── NUMBER  "3"
///   └── CLOSE_PAREN  ")"
/// ```
fn replace_with_two_times_three(ast: &mut Ast, target: NodeId) {
    let open  = ast.new_synthetic_token(TokenType::OPEN_PAREN, "(");
    let close = ast.new_synthetic_token(TokenType::CLOSE_PAREN, ")");

    let two   = ast.new_synthetic_token(TokenType::NUMBER, "2");
    let times = ast.new_synthetic_token(TokenType::TIMES, "*");
    let three = ast.new_synthetic_token(TokenType::NUMBER, "3");

    let mult  = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(mult, two);
    ast.append_child(mult, times);
    ast.append_child(mult, three);

    let add   = ast.new_node(NodeKind::AdditiveExpression);
    ast.append_child(add, mult);

    let paren = ast.new_node(NodeKind::ParentheticalExpression);
    ast.append_child(paren, open);
    ast.append_child(paren, add);
    ast.append_child(paren, close);

    ast.insert_after(target, paren);
    ast.detach(target);
}

/// Replaces all occurrences of `5` with `(2+3)` and `6` with `(2*3)`
/// in the given AST.
///
/// Node IDs are collected before any mutation begins so that the tree
/// walk is not affected by structural changes.
fn replace_fives_and_sixes(ast: &mut Ast) {
    let fives = find_numbers(ast, "5");
    let sixes = find_numbers(ast, "6");

    for id in fives {
        replace_with_two_plus_three(ast, id);
    }
    for id in sixes {
        replace_with_two_times_three(ast, id);
    }
}

// ── Tests ───────────────────────────────────────────────────────────

#[test]
fn synthetic_struct3_evaluate_and_dump() {
    // For each expression: parse, evaluate the original, dump, replace
    // literals, evaluate the modified AST, dump again, and verify the
    // value is unchanged (since 2+3=5 and 2*3=6).
    println!(
        "\n{:<20} {:>8}  {:<30} {:>8}",
        "Original", "Value", "Modified (unparsed)", "Value"
    );
    println!("{}", "-".repeat(72));

    for &(input, expected) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));

        let original_value = ast.evaluate_root().unwrap();
        assert!(
            (original_value - expected).abs() < 1e-10,
            "'{}': expected {} but got {}",
            input, expected, original_value,
        );

        let original_dump = ast.dump(ast.root().unwrap());
        println!("\n=== {} (original) ===", input);
        println!("{}", original_dump);

        replace_fives_and_sixes(&mut ast);

        let modified_value = ast.evaluate_root().unwrap();
        let modified_text = ast.unparse();

        println!("=== {} (modified) ===", modified_text);
        println!("{}", ast.dump(ast.root().unwrap()));

        println!(
            "{:<20} {:>8.4}  {:<30} {:>8.4}",
            input, original_value, modified_text, modified_value,
        );

        assert!(
            (modified_value - expected).abs() < 1e-10,
            "after replacement '{}' → '{}': expected {} but got {}",
            input, modified_text, expected, modified_value,
        );
    }
}

#[test]
fn synthetic_struct3_unparse_produces_expected_text() {
    for (i, &(input, _)) in TEST_CASES.iter().enumerate() {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        replace_fives_and_sixes(&mut ast);

        let unparsed = ast.unparse();
        println!("unparse: {} → {}", input, unparsed);

        assert_eq!(
            unparsed, EXPECTED_UNPARSE[i],
            "'{}': unparse expected {:?}, got {:?}",
            input, EXPECTED_UNPARSE[i], unparsed,
        );
    }
}

#[test]
fn synthetic_struct3_reparse_validates() {
    // Round-trip: unparse the modified AST, re-parse the text, and
    // verify the reparsed AST evaluates to the same value.
    for &(input, expected) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        replace_fives_and_sixes(&mut ast);

        let reparsed = ast
            .reparse()
            .unwrap_or_else(|e| panic!("reparse failed for '{}': {}", input, e));
        let value = reparsed.evaluate_root().unwrap();

        println!(
            "reparse: {} → {} = {}",
            input,
            reparsed.source(),
            value,
        );

        assert!(
            (value - expected).abs() < 1e-10,
            "reparsed '{}': expected {} but got {}",
            input, expected, value,
        );
    }
}

#[test]
fn synthetic_struct3_structural_validity() {
    // Verify the edited ASTs have correct structure: root exists and
    // the arena grew by exactly the expected number of new nodes.
    //
    // `node_count()` returns the arena size — detached nodes remain,
    // so the delta equals the total number of nodes *created* by all
    // replacements (the originals stay in the arena as garbage).
    //
    // `replace_with_two_plus_three` creates 9 nodes per call:
    //   OPEN_PAREN, CLOSE_PAREN, NUMBER("2"), NUMBER("3"), PLUS,
    //   MultiplicativeExpression×2, AdditiveExpression,
    //   ParentheticalExpression (outer wrapper)
    //
    // `replace_with_two_times_three` creates 8 nodes per call:
    //   OPEN_PAREN, CLOSE_PAREN, NUMBER("2"), NUMBER("3"), TIMES,
    //   MultiplicativeExpression, AdditiveExpression,
    //   ParentheticalExpression (outer wrapper)
    //
    // "5+6*(2*5)"   → two 5s + one 6 = 2×9 + 1×8 = 26
    // "2*6-(5+1)/3" → one 5 + one 6  = 1×9 + 1×8 = 17
    let expected_delta: &[usize] = &[26, 17];

    for (i, &(input, _)) in TEST_CASES.iter().enumerate() {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        let original_count = ast.node_count();

        replace_fives_and_sixes(&mut ast);

        assert!(
            ast.root().is_some(),
            "'{}': edited AST should have a root",
            input,
        );

        let new_count = ast.node_count();
        assert_eq!(
            new_count,
            original_count + expected_delta[i],
            "'{}': expected arena growth of {}, got {}",
            input, expected_delta[i], new_count - original_count,
        );

        println!(
            "{}: original {} nodes → {} nodes (+{})",
            input,
            original_count,
            new_count,
            new_count - original_count,
        );
    }
}
