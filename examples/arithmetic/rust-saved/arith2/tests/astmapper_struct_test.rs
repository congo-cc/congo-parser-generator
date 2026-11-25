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
//! adds a PLUS token and a NUMBER(1) token after the top-level
//! expression: as new children of the root when the root is an
//! `AdditiveExpression`, or, for any other root, in a new
//! `AdditiveExpression` that wraps the root.
//!
//! The root is not always an `AdditiveExpression` because the parser
//! creates nodes as the Java parser does, with smart node creation (the
//! grammar's default): a production's node is only created when it has
//! more than one child.  The root of "2+3*4" is an `AdditiveExpression`,
//! the root of "(2+3)*4" is a `MultiplicativeExpression`, and the root of
//! "7" is the NUMBER token itself.
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

/// The test expressions and their expected values.
///
/// After appending "+1" to each expression:
///   "2+3*4"                    → "2+3*4+1"                    → 14 + 1 = 15
///   "(2+3)*4"                  → "(2+3)*4+1"                  → 20 + 1 = 21
///   "((1+2)*(3+4))/(5-(6-7))" → "((1+2)*(3+4))/(5-(6-7))+1"  → 3.5 + 1 = 4.5
///   "7"                        → "7+1"                        → 7 + 1 = 8
///   "(7)"                      → "(7)+1"                      → 7 + 1 = 8
///
/// The roots of their trees are, in order, an AdditiveExpression, two
/// MultiplicativeExpressions, a NUMBER token and a ParentheticalExpression.
const TEST_CASES: &[(&str, f64, f64)] = &[
    ("2+3*4",                    14.0, 15.0),
    ("(2+3)*4",                  20.0, 21.0),
    ("((1+2)*(3+4))/(5-(6-7))",   3.5,  4.5),
    ("7",                         7.0,  8.0),
    ("(7)",                       7.0,  8.0),
];

/// An AstMapper that appends "+1" to the top-level expression.
///
/// When the root is an `AdditiveExpression`, the mapper adds a PLUS
/// token and a NUMBER(1) token to its children.  Any other root (see the
/// module documentation) is wrapped in a new `AdditiveExpression` whose
/// children are the root, the PLUS token and the NUMBER(1) token, the
/// shape that the parser builds for "<expression>+1".  Nested
/// expressions are left unchanged.
struct AppendPlusOne {
    /// Tracks whether we've already modified the top-level expression.
    modified: bool,
}

impl AppendPlusOne {
    fn new() -> Self {
        Self { modified: false }
    }

    /// Whether `source_id` is the top-level expression (the root of the
    /// tree, or a child of a `Root` node) and has not been extended yet.
    fn is_unmodified_top_level(&self, source_id: NodeId, source: &Ast) -> bool {
        let is_top_level = match source.parent(source_id) {
            None => true,
            Some(p) => matches!(source.kind(p), NodeKind::Root),
        };
        is_top_level && !self.modified
    }
}

/// Creates the PLUS and NUMBER(1) token nodes that the mapper appends.  The
/// offsets are 0 because the source text does not contain these tokens.
fn plus_one_tokens(builder: &mut AstBuilder) -> [NodeId; 2] {
    let plus_tid = builder.add_token(TokenType::PLUS, 0, 0, false);
    let plus_node = builder.add_node(NodeKind::Token(plus_tid), 0, 0);
    let num_tid = builder.add_token(TokenType::NUMBER, 0, 0, false);
    let num_node = builder.add_node(NodeKind::Token(num_tid), 0, 0);
    [plus_node, num_node]
}

/// Builds the mapped copy of the node `source_id` (with its mapped
/// children) and wraps it in a new `AdditiveExpression` followed by "+1".
fn wrap_in_plus_one(
    source_id: NodeId,
    source: &Ast,
    mapped_children: &[NodeId],
    builder: &mut AstBuilder,
) -> MappedNode {
    let original = source.node(source_id);
    let copy = builder.add_node(source.kind(source_id).clone(), original.begin_offset, original.end_offset);
    for &child in mapped_children {
        builder.add_child_to(copy, child);
    }
    let [plus_node, num_node] = plus_one_tokens(builder);
    MappedNode::Node {
        kind: NodeKind::AdditiveExpression,
        children: vec![copy, plus_node, num_node],
    }
}

/// The default mapping: the same kind with the mapped children.
fn unchanged(source_id: NodeId, source: &Ast, mapped_children: &[NodeId]) -> MappedNode {
    MappedNode::Node {
        kind: source.kind(source_id).clone(),
        children: mapped_children.to_vec(),
    }
}

impl AstMapper for AppendPlusOne {
    /// A top-level AdditiveExpression gets "+1" appended to its children.
    fn map_additiveexpression(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        if !self.is_unmodified_top_level(source_id, source) {
            return unchanged(source_id, source, mapped_children);
        }
        self.modified = true;
        let mut new_children = mapped_children.to_vec();
        new_children.extend(plus_one_tokens(builder));
        MappedNode::Node {
            kind: NodeKind::AdditiveExpression,
            children: new_children,
        }
    }

    /// A top-level MultiplicativeExpression ("(2+3)*4") is wrapped.
    fn map_multiplicativeexpression(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        if !self.is_unmodified_top_level(source_id, source) {
            return unchanged(source_id, source, mapped_children);
        }
        self.modified = true;
        wrap_in_plus_one(source_id, source, mapped_children, builder)
    }

    /// A top-level ParentheticalExpression ("(7)") is wrapped.
    fn map_parentheticalexpression(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        if !self.is_unmodified_top_level(source_id, source) {
            return unchanged(source_id, source, mapped_children);
        }
        self.modified = true;
        wrap_in_plus_one(source_id, source, mapped_children, builder)
    }

    /// A top-level NUMBER token ("7") is wrapped.  The framework copies the
    /// token for the node it builds from the returned kind; since this
    /// returns a different kind, the token node is built here.
    fn map_token(&mut self, source_id: NodeId, source: &Ast, builder: &mut AstBuilder) -> MappedNode {
        if !self.is_unmodified_top_level(source_id, source) {
            return unchanged(source_id, source, &[]);
        }
        self.modified = true;
        let NodeKind::Token(tid) = source.kind(source_id) else {
            unreachable!("map_token is only called for token nodes");
        };
        let original = source.node(source_id);
        let token = builder.copy_token(source.token(*tid));
        let token_node = builder.add_node(NodeKind::Token(token), original.begin_offset, original.end_offset);
        let [plus_node, num_node] = plus_one_tokens(builder);
        MappedNode::Node {
            kind: NodeKind::AdditiveExpression,
            children: vec![token_node, plus_node, num_node],
        }
    }
}

/// Whether the tree of `ast` ends with the "+1" that AppendPlusOne
/// appends: its root is an AdditiveExpression whose last two children are a
/// PLUS token and a NUMBER token.
fn ends_with_plus_one(ast: &Ast) -> bool {
    let root = ast.root().expect("the mapped AST has a root");
    let children: Vec<NodeId> = ast.children(root).collect();
    matches!(ast.kind(root), NodeKind::AdditiveExpression)
        && children.len() >= 3
        && ast.token_type(children[children.len() - 2]) == Some(TokenType::PLUS)
        && ast.token_type(children[children.len() - 1]) == Some(TokenType::NUMBER)
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

    // The mapped AST should have more nodes than the original (we added
    // the PLUS token and the NUMBER token).
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

        // Also verify the AstMapper structurally modifies the AST: it adds
        // the PLUS and NUMBER tokens (and, when the root is not an
        // AdditiveExpression, the AdditiveExpression that wraps it), and the
        // new root ends with "+1", whatever the kind of the original root.
        let mut mapper = AppendPlusOne::new();
        let mapped_ast = mapper.map(&ast);
        let root_was_additive = matches!(ast.kind(ast.root().unwrap()), NodeKind::AdditiveExpression);
        let added = if root_was_additive { 2 } else { 3 };
        assert_eq!(
            mapped_ast.node_count(),
            ast.node_count() + added,
            "AstMapper should add {} nodes for '+1' to '{}':\n{}",
            added,
            input,
            mapped_ast.dump(mapped_ast.root().unwrap())
        );
        assert!(
            ends_with_plus_one(&mapped_ast),
            "the mapped tree of '{}' should end with '+1':\n{}",
            input,
            mapped_ast.dump(mapped_ast.root().unwrap())
        );
    }
}
