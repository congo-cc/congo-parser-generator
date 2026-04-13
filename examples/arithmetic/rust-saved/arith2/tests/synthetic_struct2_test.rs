//! Structural AST editing without source-string reconstruction.
//!
//! This is the prototype for the redesign discussed in
//! `docs/claude_prompts.md` under "Rethinking Structural Changes to
//! ASTs".  The **old** version of this test had to construct an
//! `extended_source` string like `format!("{}+1", input)` because
//! every new token was represented by byte offsets into a single
//! source string.  New tokens had to have valid byte ranges, which
//! meant concatenating input text and then threading it through a
//! `rebuild_with_source` helper.  That approach fails as soon as you
//! try to insert nodes in the middle of an AST.
//!
//! The **new** approach has three ingredients:
//!
//!   1. `TokenSource::Synthetic(Box<str>)` — tokens carry their own
//!      text, so they can be created anywhere without coordinating
//!      with the AST's `source` string.
//!
//!   2. In-place mutation methods on `Ast` — `new_synthetic_token`,
//!      `new_node`, `append_child`, `insert_after`, `detach` — so
//!      structural edits happen on a mutable `Ast` instead of
//!      rebuilding the whole tree via `AstMapper`.
//!
//!   3. `Ast::unparse` — regenerates source text from the current
//!      tree by concatenating token text in document order.  Used
//!      for display and, optionally, for round-tripping through the
//!      parser via `Ast::reparse`.
//!
//! The test below appends "+1" to each input expression by editing
//! the AST directly, evaluates the modified AST, and verifies the
//! result.  It never constructs a modified source string.
//!
//! Run with output:
//!     cargo test --test synthetic_struct2_test -- --nocapture

use ex2::ast::{Ast, NodeId, NodeKind};
use ex2::parser::Parser;
use ex2::tokens::TokenType;

/// The three standard test expressions and their expected values.
///
/// After appending "+1" to each expression:
///   "2+3*4"                    → 14 + 1 = 15
///   "(2+3)*4"                  → 20 + 1 = 21
///   "((1+2)*(3+4))/(5-(6-7))" → 3.5 + 1 = 4.5
const TEST_CASES: &[(&str, f64, f64)] = &[
    ("2+3*4",                    14.0, 15.0),
    ("(2+3)*4",                  20.0, 21.0),
    ("((1+2)*(3+4))/(5-(6-7))", 3.5,  4.5),
];

/// Finds the top-level `AdditiveExpression` node in the AST.
///
/// In this grammar the root is `Root : AdditiveExpression <EOF>`, so
/// the target is `Root`'s first child.  This helper also tolerates
/// grammars that make `AdditiveExpression` the root directly.
fn find_top_level_add(ast: &Ast) -> NodeId {
    let root = ast.root().expect("AST has no root");
    match ast.kind(root) {
        NodeKind::AdditiveExpression => root,
        NodeKind::Root => ast
            .first_child(root)
            .expect("Root has no AdditiveExpression child"),
        other => panic!("unexpected root kind: {:?}", other),
    }
}

/// Appends "+1" to the top-level `AdditiveExpression` by editing `ast`
/// in place.  Uses only the new synthetic-token + in-place-edit API —
/// no `AstMapper`, no source-string reconstruction.
fn append_plus_one(ast: &mut Ast) {
    let add_expr = find_top_level_add(ast);

    // Build the new subtree: a PLUS operator and a MultiplicativeExpression
    // wrapping a NUMBER(1) leaf.  The wrapping MultiplicativeExpression
    // matches the grammar structure: AdditiveExpression children
    // alternate between MultiplicativeExpressions and PLUS/MINUS tokens.
    let plus_node = ast.new_synthetic_token(TokenType::PLUS, "+");
    let one_node = ast.new_synthetic_token(TokenType::NUMBER, "1");
    let mult_wrap = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(mult_wrap, one_node);

    // Attach to the AdditiveExpression as the two new trailing children.
    ast.append_child(add_expr, plus_node);
    ast.append_child(add_expr, mult_wrap);
}

#[test]
fn synthetic_struct2_evaluate_modified() {
    println!(
        "{:<30} {:>10}  {:<35} {:>10}",
        "Original", "Value", "Modified", "Value"
    );
    println!("{}", "-".repeat(90));

    for &(input, expected_original, expected_modified) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));

        // Evaluate the original expression before any edits.
        let original_value = ast.evaluate_root().unwrap();
        assert!(
            (original_value - expected_original).abs() < 1e-10,
            "original '{}': expected {} but got {}",
            input,
            expected_original,
            original_value
        );

        // Edit the AST in place: append a synthetic "+1".  No
        // source-string reconstruction, no AstMapper rebuild.
        append_plus_one(&mut ast);

        // Evaluate the modified AST directly.  Works because
        // `Ast::text` delegates to `token_text` for token leaves,
        // which returns the synthetic token's owned text.
        let modified_value = ast.evaluate_root().unwrap();

        let modified_label = format!("{}+1", input);
        println!(
            "{:<30} {:>10.4}  {:<35} {:>10.4}",
            input, original_value, modified_label, modified_value
        );

        assert!(
            (modified_value - expected_modified).abs() < 1e-10,
            "modified '{}': expected {} but got {}",
            modified_label,
            expected_modified,
            modified_value
        );
    }
}

#[test]
fn synthetic_struct2_unparse_regenerates_text() {
    // After editing, `Ast::unparse()` should produce a string that
    // equals the original input concatenated with "+1".  This
    // exercises the text-regeneration path without re-parsing.
    for &(input, _, _) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        append_plus_one(&mut ast);

        let regenerated = ast.unparse();
        let expected = format!("{}+1", input);
        assert_eq!(
            regenerated, expected,
            "unparse did not round-trip for '{}': got {:?}, want {:?}",
            input, regenerated, expected
        );
        println!("unparse: {:<30} -> {}", input, regenerated);
    }
}

#[test]
fn synthetic_struct2_reparse_validates() {
    // Round-trip through the parser: unparse the edited AST, parse
    // the result, and confirm the re-parsed AST evaluates to the
    // same value.  This is the "validate via the original parser"
    // option from the design — no new validation code required.
    for &(input, _, expected_modified) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        append_plus_one(&mut ast);

        let reparsed = ast
            .reparse()
            .unwrap_or_else(|e| panic!("reparse failed for '{}+1': {}", input, e));
        let value = reparsed.evaluate_root().unwrap();

        assert!(
            (value - expected_modified).abs() < 1e-10,
            "reparsed '{}+1': expected {} but got {}",
            input, expected_modified, value
        );
        println!(
            "reparse:  {:<30} -> {} = {}",
            input,
            reparsed.source(),
            value
        );
    }
}

#[test]
fn synthetic_struct2_ast_validity() {
    // Verify the edited AST has more nodes than the original and
    // still has a root.  The edit adds 3 nodes per expression
    // (PLUS token, NUMBER token, MultiplicativeExpression wrapper).
    for &(input, _, _) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        let original_count = ast.node_count();
        let original_dump = ast.dump(ast.root().unwrap());

        append_plus_one(&mut ast);

        assert!(
            ast.root().is_some(),
            "'{}': edited AST should have a root",
            input
        );
        assert_eq!(
            ast.node_count(),
            original_count + 3,
            "'{}': edited AST should have exactly 3 more nodes",
            input
        );

        println!("=== {} ===", input);
        println!("Original node count: {}", original_count);
        println!("Original AST dump:");
        println!("{}", original_dump);
        println!("Edited node count: {}", ast.node_count());
        println!("Edited AST dump:");
        println!("{}", ast.dump(ast.root().unwrap()));
        println!();
    }
}
