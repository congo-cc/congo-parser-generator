//! AstMapper structure test: demonstrates modifying AST structure using
//! the AstMapper trait and AstBuilder.
//!
//! NOTE: For most structural editing use cases, the synthetic-token API 
//! allows creating new AST nodes without relying on source text offsets, 
//! which is a simpler, more robust approach.  See the synthetic_struct2_test 
//! and synthetic_struct3_test testsfor example usage of that API.
//!
//! Uses the `AstMapper` trait to append "+1" to the end of each
//! expression.  For example, "2+3*4" becomes "2+3*4+1".  The mapper
//! modifies the top-level `AdditiveExpression` by adding a PLUS token
//! and a NUMBER(1) token as new children, effectively appending an
//! additive operand.
//!
//! Since the arena-based AST uses byte offsets into the source string
//! for its text representation, and the AstMapper produces a new AST
//! that shares the same source string, we take a practical approach:
//! build the modified expression as a string, parse it, and evaluate.
//! This demonstrates the structural transformation concept while
//! producing verifiable results.
//!
//! Run with output:
//!     cargo test --test astmapper_struct_test -- --nocapture

use ex2::ast::{Ast, AstBuilder, NodeId, NodeKind};
use ex2::parser::Parser;
use ex2::tokens::TokenType;
use ex2::visitor::{AstMapper, MappedNode};

/// The three standard test expressions and their expected values.
///
/// After appending "+1" to each expression:
///   "2+3*4"                    → "2+3*4+1"                    → 14 + 1 = 15
///   "(2+3)*4"                  → "(2+3)*4+1"                  → 20 + 1 = 21
///   "((1+2)*(3+4))/(5-(6-7))" → "((1+2)*(3+4))/(5-(6-7))+1"  → 3.5 + 1 = 4.5
const TEST_CASES: &[(&str, f64, f64)] = &[
    ("2+3*4",                    14.0, 15.0),
    ("(2+3)*4",                  20.0, 21.0),
    ("((1+2)*(3+4))/(5-(6-7))",   3.5,  4.5),
];

/// An AstMapper that appends "+1" to the top-level additive expression.
///
/// The mapper intercepts the `AdditiveExpression` at the outermost level
/// (the one directly under Root) and adds a PLUS token and NUMBER(1)
/// token to its children.  Nested AdditiveExpressions (inside parens)
/// are left unchanged.
struct AppendPlusOne {
    /// Tracks whether we've already modified the top-level expression.
    modified: bool,
}

impl AppendPlusOne {
    fn new() -> Self {
        Self { modified: false }
    }
}

impl AstMapper for AppendPlusOne {
    /// Map AdditiveExpression: at the top level, append "+1" children.
    fn map_additiveexpression(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        // Only modify the top-level AdditiveExpression (the one that is the root
        // or whose parent is Root).  In Arithmetic2 grammars, the parser sets the
        // AdditiveExpression itself as the AST root (no wrapping Root node).
        let is_top_level = match source.parent(source_id) {
            None => true, // This node IS the root of the AST.
            Some(p) => matches!(source.kind(p), NodeKind::Root),
        };

        if is_top_level && !self.modified {
            self.modified = true;

            // The current mapped_children represent the already-mapped children
            // of this AdditiveExpression.  We need to append:
            //   1. A PLUS token
            //   2. A MultiplicativeExpression containing NUMBER(1)

            // Create a PLUS token.  We use offset 0 as placeholder since the
            // source text won't contain this token.
            let plus_tid = builder.add_token(TokenType::PLUS, 0, 0, false);
            let plus_node = builder.add_node(NodeKind::Token(plus_tid), 0, 0);

            // Create a NUMBER(1) token.
            let num_tid = builder.add_token(TokenType::NUMBER, 0, 0, false);
            let num_node = builder.add_node(NodeKind::Token(num_tid), 0, 0);

            // Wrap the NUMBER in a MultiplicativeExpression (matching the grammar
            // structure where AdditiveExpression children alternate between
            // MultiplicativeExpressions and operators).
            let mult_node = builder.add_node(NodeKind::MultiplicativeExpression, 0, 0);
            builder.add_child_to(mult_node, num_node);

            // Build the extended children list.
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

#[test]
fn astmapper_append_plus_one() {
    // Verify the AstMapper produces a valid AST with extra nodes.
    let input = "2+3*4";
    let ast = Parser::parse(input, Some("test")).unwrap();

    let original_count = ast.node_count();
    let mut mapper = AppendPlusOne::new();
    let mapped_ast = mapper.map(&ast);

    assert!(mapped_ast.root().is_some(), "mapped AST should have a root");

    // The mapped AST should have more nodes than the original (we added 3:
    // PLUS token, NUMBER token, and MultiplicativeExpression wrapper).
    assert!(
        mapped_ast.node_count() > original_count,
        "mapped AST should have more nodes: {} vs {}",
        mapped_ast.node_count(),
        original_count
    );

    println!("Original node count: {}", original_count);
    println!("Mapped node count:   {}", mapped_ast.node_count());
    println!("Mapped AST dump:");
    println!("{}", mapped_ast.dump(mapped_ast.root().unwrap()));
}

#[test]
fn astmapper_structure_modification() {
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

        // Append "+1" to the expression string and parse/evaluate the result.
        // This is the practical approach since the mapped AST's text offsets
        // reference the original source which doesn't contain "+1".
        let modified_expr = format!("{}+1", input);
        let modified_ast = Parser::parse(&modified_expr, Some("modified"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", modified_expr, e));

        let modified_value = modified_ast.evaluate_root().unwrap();
        println!(
            "{:<30} {:>10.4}  {:<35} {:>10.4}",
            input, original_value, modified_expr, modified_value
        );

        assert!(
            (modified_value - expected_modified).abs() < 1e-10,
            "modified '{}': expected {} but got {}",
            modified_expr,
            expected_modified,
            modified_value
        );

        // Also verify the AstMapper structurally modifies the AST.
        let mut mapper = AppendPlusOne::new();
        let mapped_ast = mapper.map(&ast);
        assert!(
            mapped_ast.node_count() > ast.node_count(),
            "AstMapper should add nodes for '+1'"
        );
    }
}
