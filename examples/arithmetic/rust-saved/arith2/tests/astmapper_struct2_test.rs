//! AstMapper structure test 2: direct AST modification with evaluation.
//!
//! Uses the `AstMapper` trait to structurally modify each expression's
//! AST by appending "+1", then **evaluates the modified AST directly**
//! (without re-parsing a string).  This is the key difference from
//! `astmapper_struct_test.rs`, which modified the source string and
//! re-parsed it.
//!
//! Direct evaluation of a structurally modified AST is non-trivial with
//! arena-based ASTs because token text is represented as byte offsets
//! into the original source string.  New tokens added by the mapper
//! have no corresponding text in the original source.  This test
//! demonstrates a two-phase approach:
//!
//!   1. **Map phase:** Use `AstMapper` to insert new nodes (PLUS and
//!      NUMBER(1)) into the top-level `AdditiveExpression`, with token
//!      offsets pointing to positions in an *extended* source string.
//!
//!   2. **Rebuild phase:** Copy the mapped AST's nodes and tokens into
//!      a new `AstBuilder`, then call `build()` with the extended source
//!      string so that the new tokens' offsets resolve to valid text.
//!
//! After rebuilding, `evaluate_root()` works correctly on the modified
//! AST because every token's byte range maps to real text.
//!
//! Run with output:
//!     cargo test --test astmapper_struct2_test -- --nocapture

use ex2::ast::{Ast, AstBuilder, NodeId, NodeKind, TokenId};
use ex2::parser::Parser;
use ex2::tokens::TokenType;
use ex2::visitor::{AstMapper, MappedNode};

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

// ============================================================================
// AstMapper: insert "+1" at the end of the top-level AdditiveExpression
// ============================================================================

/// An AstMapper that appends "+1" to the top-level additive expression.
///
/// New tokens are created with byte offsets that point past the end of
/// the original source string — into positions where the extended source
/// string (original + "+1") will have the "+" and "1" characters.
struct AppendPlusOne {
    /// Length of the original source string.  The new PLUS token will
    /// be at offset `source_len` and the NUMBER(1) at `source_len + 1`.
    source_len: usize,
    /// Tracks whether we've already modified the top-level expression.
    modified: bool,
}

impl AppendPlusOne {
    fn new(source_len: usize) -> Self {
        Self {
            source_len,
            modified: false,
        }
    }
}

impl AstMapper for AppendPlusOne {
    /// Map AdditiveExpression: at the top level, append "+1" children
    /// with offsets pointing into the extended source string.
    fn map_additiveexpression(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        // Only modify the top-level AdditiveExpression.  In Arithmetic2
        // grammars, the parser sets AdditiveExpression as the AST root
        // (no wrapping Root node), so the top-level node has no parent.
        let is_top_level = match source.parent(source_id) {
            None => true,
            Some(p) => matches!(source.kind(p), NodeKind::Root),
        };

        if is_top_level && !self.modified {
            self.modified = true;

            // Create a PLUS token at the position where "+" will appear
            // in the extended source string: immediately after the
            // original source text.
            let plus_start = self.source_len;
            let plus_end = self.source_len + 1;
            let plus_tid = builder.add_token(TokenType::PLUS, plus_start, plus_end, false);
            let plus_node = builder.add_node(
                NodeKind::Token(plus_tid),
                plus_start as u32,
                plus_end as u32,
            );

            // Create a NUMBER(1) token at the next position.
            let num_start = self.source_len + 1;
            let num_end = self.source_len + 2;
            let num_tid = builder.add_token(TokenType::NUMBER, num_start, num_end, false);
            let num_node = builder.add_node(
                NodeKind::Token(num_tid),
                num_start as u32,
                num_end as u32,
            );

            // Wrap the NUMBER in a MultiplicativeExpression to match the
            // grammar structure (AdditiveExpression children alternate
            // between MultiplicativeExpressions and operator tokens).
            let mult_node = builder.add_node(
                NodeKind::MultiplicativeExpression,
                num_start as u32,
                num_end as u32,
            );
            builder.add_child_to(mult_node, num_node);

            // Build the extended children list: original children + new nodes.
            let mut new_children = mapped_children.to_vec();
            new_children.push(plus_node);
            new_children.push(mult_node);

            MappedNode::Node {
                kind: NodeKind::AdditiveExpression,
                children: new_children,
            }
        } else {
            // Nested AdditiveExpressions (inside parens) pass through unchanged.
            MappedNode::Node {
                kind: source.kind(source_id).clone(),
                children: mapped_children.to_vec(),
            }
        }
    }
}

// ============================================================================
// Rebuild helper: copy a mapped AST into a new Ast with a different source
// ============================================================================

/// Rebuilds an AST with a new source string.
///
/// Copies all tokens, nodes, and parent-child relationships from `ast`
/// into a fresh `AstBuilder`, preserving all `NodeId` and `TokenId`
/// indices.  Then calls `build()` with `new_source` so that token byte
/// offsets resolve against the new string.
///
/// This is the key to making structurally modified ASTs evaluable:
/// the mapper inserts tokens whose offsets point past the original
/// source, and this function provides the extended source that covers
/// those offsets.
///
/// Called from both tests in this file after `AppendPlusOne::map()` produces
/// a mapped AST with tokens whose byte offsets extend beyond the original
/// source string.  Each call site constructs `extended_source` as
/// `format!("{}+1", input)` so the new PLUS and NUMBER tokens resolve to
/// valid text at positions `input.len()` and `input.len() + 1`.
fn rebuild_with_source(ast: &Ast, new_source: &str) -> Ast {
    let mut builder = AstBuilder::new();

    // Copy tokens in index order so TokenId values are preserved.
    for i in 0..ast.token_count() {
        let tid = TokenId(i as u32);
        let tok = ast.token(tid);
        builder.add_token(tok.kind, tok.start, tok.end, tok.is_unparsed);
    }

    // Copy nodes in index order so NodeId values are preserved.
    for i in 0..ast.node_count() {
        let nid = NodeId(i as u32);
        let node = ast.node(nid);
        builder.add_node(node.kind.clone(), node.begin_offset, node.end_offset);
    }

    // Rebuild parent-child relationships.  Since NodeId values are
    // preserved, the children() iterator returns IDs that are valid
    // in the new builder.
    for i in 0..ast.node_count() {
        let nid = NodeId(i as u32);
        for child in ast.children(nid) {
            builder.add_child_to(nid, child);
        }
    }

    // Set the root.
    if let Some(root) = ast.root() {
        builder.set_root(root);
    }

    builder.build(new_source.to_string(), ast.source_name().to_string())
}

// ============================================================================
// Tests
// ============================================================================

#[test]
fn astmapper_struct2_evaluate_modified() {
    println!(
        "{:<30} {:>10}  {:<35} {:>10}",
        "Original", "Value", "Modified", "Value"
    );
    println!("{}", "-".repeat(90));

    for &(input, expected_original, expected_modified) in TEST_CASES {
        let ast = Parser::parse(input, Some("test"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));

        // Evaluate the original expression.
        let original_value = ast.evaluate_root().unwrap();
        assert!(
            (original_value - expected_original).abs() < 1e-10,
            "original '{}': expected {} but got {}",
            input,
            expected_original,
            original_value
        );

        // Phase 1: Use AstMapper to structurally append "+1".
        // Token offsets are set for the extended source string.
        let mut mapper = AppendPlusOne::new(input.len());
        let mapped_ast = mapper.map(&ast);

        // Phase 2: Rebuild with the extended source so that the new
        // tokens' byte offsets resolve to "+" and "1".
        let extended_source = format!("{}+1", input);
        let modified_ast = rebuild_with_source(&mapped_ast, &extended_source);

        // Evaluate the modified AST directly — no re-parsing needed.
        let modified_value = modified_ast.evaluate_root().unwrap();

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
fn astmapper_struct2_ast_validity() {
    // Verify the modified AST is structurally valid and has the expected
    // extra nodes (PLUS token, NUMBER token, MultiplicativeExpression)
    // for every test expression.
    for &(input, _, _) in TEST_CASES {
        let ast = Parser::parse(input, Some("test")).unwrap();
        let original_count = ast.node_count();

        let mut mapper = AppendPlusOne::new(input.len());
        let mapped_ast = mapper.map(&ast);
        let extended_source = format!("{}+1", input);
        let modified_ast = rebuild_with_source(&mapped_ast, &extended_source);

        assert!(
            modified_ast.root().is_some(),
            "'{}': modified AST should have a root",
            input
        );

        // The mapper added 3 nodes: PLUS token, NUMBER token, and
        // MultiplicativeExpression wrapper.
        assert!(
            modified_ast.node_count() > original_count,
            "'{}': modified AST should have more nodes: {} vs {}",
            input,
            modified_ast.node_count(),
            original_count
        );

        println!("=== {} ===", input);
        println!("Original node count: {}", original_count);
        println!("Original AST dump:");
        println!("{}", ast.dump(ast.root().unwrap()));
        println!();
        println!("Modified node count: {}", modified_ast.node_count());
        println!("Modified AST dump:");
        println!("{}", modified_ast.dump(modified_ast.root().unwrap()));
        println!();
    }
}
