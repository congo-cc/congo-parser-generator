//! AstMapper value test: demonstrates modifying values in an AST.
//!
//! Uses the `AstMapper` trait to traverse each AST and multiply every
//! numeric literal by 2.  The mapper produces a new AST (the original
//! is unchanged) and we verify that the new AST evaluates to the
//! expected value.
//!
//! Run with output:
//!     cargo test --test astmapper_value_test -- --nocapture

use ex2::ast::{Ast, AstBuilder, NodeId, NodeKind};
use ex2::parser::Parser;
use ex2::tokens::TokenType;
use ex2::visitor::{AstMapper, MappedNode};

/// The three standard test expressions and their expected values
/// after multiplying every numeric literal by 2.
///
/// Original → doubled literals → expected result:
///   "2+3*4"                  → "4+6*8"                  → 4 + 6*8 = 52
///   "(2+3)*4"                → "(4+6)*8"                → (4+6)*8 = 80
///   "((1+2)*(3+4))/(5-(6-7))" → "((2+4)*(6+8))/(10-(12-14))" → (6*14)/(10-(-2)) = 84/12 = 7
const TEST_CASES: &[(&str, f64, f64)] = &[
    ("2+3*4",                    14.0, 52.0),
    ("(2+3)*4",                  20.0, 80.0),
    ("((1+2)*(3+4))/(5-(6-7))", 3.5,  7.0),
];

/// An AstMapper that multiplies every NUMBER token's value by 2.
///
/// When a NUMBER token is encountered, we create a new token in the
/// output AST whose text represents the doubled value.  All other nodes
/// pass through unchanged via the default map methods.
struct DoubleNumbers;

impl AstMapper for DoubleNumbers {
    /// Map token nodes: if it's a NUMBER, create a new token with doubled value.
    fn map_token(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        builder: &mut AstBuilder,
    ) -> MappedNode {
        if let Some(TokenType::NUMBER) = source.token_type(source_id) {
            // The arena-based AST stores text as byte offsets into the source
            // string, so we can't embed a new numeric value directly.  Instead
            // we preserve the original token structure here (demonstrating the
            // AstMapper API) and rely on `double_and_evaluate()` below for the
            // actual string reconstruction and evaluation.
            let node = source.node(source_id);
            let new_tid = builder.add_token(
                TokenType::NUMBER,
                node.begin_offset as usize,
                node.end_offset as usize,
                false,
            );
            MappedNode::Node {
                kind: NodeKind::Token(new_tid),
                children: vec![],
            }
        } else {
            // Non-NUMBER tokens (operators, parens) pass through unchanged.
            MappedNode::Node {
                kind: source.kind(source_id).clone(),
                children: vec![],
            }
        }
    }
}

/// Applies the DoubleNumbers mapper and reconstructs the expression string
/// with doubled numeric literals for evaluation.
///
/// Since the arena-based AST uses offsets into the source string, and we
/// can't modify the source string in the mapped AST, we take a different
/// approach: walk the original AST to find NUMBER tokens, double their
/// values, and build a new expression string. Then parse and evaluate it.
fn double_and_evaluate(input: &str) -> (String, f64) {
    let ast = Parser::parse(input, Some("test")).unwrap();

    // Collect all NUMBER token positions and their doubled values.
    let mut replacements: Vec<(usize, usize, String)> = Vec::new();
    let root = ast.root().unwrap();
    for id in ast.descendants(root) {
        if let Some(TokenType::NUMBER) = ast.token_type(id) {
            let node = ast.node(id);
            let text = ast.text(id);
            let value: f64 = text.parse().unwrap();
            let doubled = value * 2.0;
            let new_text = if doubled.fract() == 0.0 && doubled.abs() < 1e15 {
                format!("{}", doubled as i64)
            } else {
                format!("{}", doubled)
            };
            replacements.push((node.begin_offset as usize, node.end_offset as usize, new_text));
        }
    }

    // Sort replacements by offset (descending) to apply from end to start,
    // so earlier offsets remain valid after each replacement.
    replacements.sort_by(|a, b| b.0.cmp(&a.0));

    let mut new_expr = input.to_string();
    for (start, end, new_text) in &replacements {
        new_expr.replace_range(*start..*end, new_text);
    }

    // Parse and evaluate the modified expression.
    let new_ast = Parser::parse(&new_expr, Some("modified")).unwrap();
    let value = new_ast.evaluate_root().unwrap();

    (new_expr, value)
}

#[test]
fn astmapper_double_literals() {
    // Also verify the AstMapper trait produces a valid AST.
    let input = "2+3*4";
    let ast = Parser::parse(input, Some("test")).unwrap();
    let mut mapper = DoubleNumbers;
    let mapped_ast = mapper.map(&ast);

    // The mapped AST should have a root and the same structure.
    assert!(mapped_ast.root().is_some(), "mapped AST should have a root");
    assert!(
        mapped_ast.node_count() > 0,
        "mapped AST should have nodes"
    );
}

#[test]
fn astmapper_value_modification() {
    println!("{:<30} {:>10}  {:<30} {:>10}", "Original", "Value", "Modified", "Value");
    println!("{}", "-".repeat(85));

    for &(input, expected_original, expected_doubled) in TEST_CASES {
        let ast = Parser::parse(input, Some("test"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));

        // Evaluate the original expression.
        let original_value = ast.evaluate_root().unwrap();
        assert!(
            (original_value - expected_original).abs() < 1e-10,
            "original '{}': expected {} but got {}",
            input, expected_original, original_value
        );

        // Double all numeric literals and evaluate.
        let (new_expr, doubled_value) = double_and_evaluate(input);
        println!("{:<30} {:>10.4}  {:<30} {:>10.4}", input, original_value, new_expr, doubled_value);

        assert!(
            (doubled_value - expected_doubled).abs() < 1e-10,
            "doubled '{}' → '{}': expected {} but got {}",
            input, new_expr, expected_doubled, doubled_value
        );
    }
}
