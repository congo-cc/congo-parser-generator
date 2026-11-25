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

/// The test expressions and their expected values.
///
/// After appending "+1" to each expression:
///   "2+3*4"                    → 14 + 1 = 15
///   "(2+3)*4"                  → 20 + 1 = 21
///   "((1+2)*(3+4))/(5-(6-7))" → 3.5 + 1 = 4.5
///   "7"                        → 7 + 1 = 8
///   "(7)"                      → 7 + 1 = 8
///
/// The roots of their trees are, in order, an AdditiveExpression, two
/// MultiplicativeExpressions, a NUMBER token and a ParentheticalExpression
/// (see `append_plus_one`).
const TEST_CASES: &[(&str, f64, f64)] = &[
    ("2+3*4",                    14.0, 15.0),
    ("(2+3)*4",                  20.0, 21.0),
    ("((1+2)*(3+4))/(5-(6-7))", 3.5,  4.5),
    ("7",                        7.0,  8.0),
    ("(7)",                      7.0,  8.0),
];

/// Appends "+1" to the expression by editing `ast` in place, and returns
/// the number of nodes added.  Uses only the synthetic-token + in-place-edit
/// API — no `AstMapper`, no source-string reconstruction.
///
/// The parser creates nodes as the Java parser does, with smart node
/// creation (the grammar's default): a production's node is only created
/// when it has more than one child, so an `AdditiveExpression` node exists
/// only where an expression has a `+` or `-` at that level.  The root of
/// "2+3*4" is an AdditiveExpression, which gets the PLUS and NUMBER(1)
/// tokens as two new trailing children (2 nodes added).  Any other root — the
/// MultiplicativeExpression of "(2+3)*4", the NUMBER token of "7", the
/// ParentheticalExpression of "(7)" — is wrapped in a new
/// AdditiveExpression whose children are the old root, PLUS and NUMBER(1)
/// (3 nodes added): the tree the parser builds for "<expression>+1".
fn append_plus_one(ast: &mut Ast) -> usize {
    let root = ast.root().expect("AST has no root");
    let plus_node = ast.new_synthetic_token(TokenType::PLUS, "+");
    let one_node = ast.new_synthetic_token(TokenType::NUMBER, "1");
    if matches!(ast.kind(root), NodeKind::AdditiveExpression) {
        ast.append_child(root, plus_node);
        ast.append_child(root, one_node);
        2
    } else {
        let sum = ast.new_node(NodeKind::AdditiveExpression);
        ast.append_child(sum, root);
        ast.append_child(sum, plus_node);
        ast.append_child(sum, one_node);
        ast.set_root(sum);
        3
    }
}

/// Returns the node IDs of the tree in pre-order (the nodes reachable from
/// the root), so that the tests count the nodes of the tree rather than the
/// arena entries.
fn tree_nodes(ast: &Ast) -> Vec<NodeId> {
    let mut result = Vec::new();
    let mut stack = vec![ast.root().expect("AST has no root")];
    while let Some(id) = stack.pop() {
        result.push(id);
        let children: Vec<NodeId> = ast.children(id).collect();
        stack.extend(children.into_iter().rev());
    }
    result
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
    // Verify the edited AST has more nodes than the original and still
    // has a root.  The edit adds 2 nodes (PLUS and NUMBER tokens) to an
    // AdditiveExpression root, or 3 nodes (with the AdditiveExpression
    // that wraps it) to any other root; see `append_plus_one`.  The tree
    // and the arena grow alike: the parser stores only the nodes of the
    // tree, and the edit leaves nothing unreachable.
    for &(input, _, _) in TEST_CASES {
        let mut ast = Parser::parse(input, Some("test")).unwrap();
        let original_count = ast.node_count();
        assert_eq!(
            tree_nodes(&ast).len(),
            original_count,
            "'{}': the parser should store only the nodes of the tree",
            input
        );
        let original_dump = ast.dump(ast.root().unwrap());

        let added = append_plus_one(&mut ast);

        assert!(
            ast.root().is_some(),
            "'{}': edited AST should have a root",
            input
        );
        assert_eq!(
            ast.node_count(),
            original_count + added,
            "'{}': edited AST should have exactly {} more nodes",
            input,
            added
        );
        assert_eq!(
            tree_nodes(&ast).len(),
            original_count + added,
            "'{}': every node of the edited AST should be in the tree",
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
