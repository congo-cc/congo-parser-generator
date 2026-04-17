//! Visitor test: demonstrates the read-only Visitor trait.
//!
//! Implements a custom `Visitor` that traverses the AST and prints
//! information about each arithmetic operator node encountered.
//! For each `AdditiveExpression` or `MultiplicativeExpression`, it
//! prints the operator symbol and the number of children, indented
//! to reflect the node's depth in the tree.
//!
//! Run with output:
//!     cargo test --test visitor_test -- --nocapture

use ex2::ast::{Ast, NodeId};
use ex2::parser::Parser;
use ex2::tokens::TokenType;
use ex2::visitor::Visitor;

/// The three standard test expressions used across all traversal tests.
const INPUTS: &[&str] = &[
    "2+3*4",
    "(2+3)*4",
    "((1+2)*(3+4))/(5-(6-7))",
];

/// A visitor that prints operator information at each expression node.
///
/// For AdditiveExpression and MultiplicativeExpression nodes, it extracts
/// the operator symbol from the child tokens and prints it along with the
/// node's child count.  Indentation reflects the depth in the AST.
struct OperatorPrinter {
    /// Current depth in the AST (used for indentation).
    depth: usize,
    /// Collected output lines (for assertions).
    lines: Vec<String>,
}

impl OperatorPrinter {
    fn new() -> Self {
        Self {
            depth: 0,
            lines: Vec::new(),
        }
    }

    /// Extracts the operator symbol(s) from an expression node's children.
    /// Operator tokens (PLUS, MINUS, TIMES, DIVIDE) appear between operands.
    fn extract_operators(&self, ast: &Ast, id: NodeId) -> Vec<&'static str> {
        let mut ops = Vec::new();
        let count = ast.child_count(id);
        // Operators are at odd indices: child(1), child(3), etc.
        let mut i = 1;
        while i < count {
            let symbol = if ast.child_is_token(id, i, TokenType::PLUS) {
                "+"
            } else if ast.child_is_token(id, i, TokenType::MINUS) {
                "-"
            } else if ast.child_is_token(id, i, TokenType::TIMES) {
                "*"
            } else if ast.child_is_token(id, i, TokenType::DIVIDE) {
                "/"
            } else {
                "?"
            };
            ops.push(symbol);
            i += 2;
        }
        ops
    }

    /// Prints and records a line with the operator information.
    fn record_operator(&mut self, ast: &Ast, id: NodeId) {
        let ops = self.extract_operators(ast, id);
        let children = ast.child_count(id);
        let indent = "  ".repeat(self.depth);
        for op in &ops {
            let line = format!("{}operator: {}, children: {}", indent, op, children);
            println!("{}", line);
            self.lines.push(line);
        }
    }
}

impl Visitor for OperatorPrinter {
    /// AdditiveExpression contains + or - operators between operands.
    fn visit_additiveexpression(&mut self, ast: &Ast, id: NodeId) {
        self.record_operator(ast, id);
        // Continue traversal into children to find nested operators.
        self.depth += 1;
        self.visit_children(ast, id);
        self.depth -= 1;
    }

    /// MultiplicativeExpression contains * or / operators between operands.
    fn visit_multiplicativeexpression(&mut self, ast: &Ast, id: NodeId) {
        self.record_operator(ast, id);
        // Continue traversal into children to find nested operators.
        self.depth += 1;
        self.visit_children(ast, id);
        self.depth -= 1;
    }

    /// ParentheticalExpression: increase depth and recurse through children.
    fn visit_parentheticalexpression(&mut self, ast: &Ast, id: NodeId) {
        self.depth += 1;
        self.visit_children(ast, id);
        self.depth -= 1;
    }

    /// Root: just recurse into children (the top-level expression).
    fn visit_root(&mut self, ast: &Ast, id: NodeId) {
        self.visit_children(ast, id);
    }

    /// Token leaves: no action needed (operators are handled by their parents).
    fn visit_token(&mut self, _ast: &Ast, _id: NodeId) {
        // Leaf tokens (numbers, parens) have no operator info to print.
    }
}

#[test]
fn visitor_operator_traversal() {
    for input in INPUTS {
        let ast = Parser::parse(input, Some("test"))
            .unwrap_or_else(|e| panic!("parse failed for '{}': {}", input, e));

        println!("--- Input: {} ---", input);
        println!("AST structure:");
        println!("{}", ast.dump(ast.root().unwrap()));

        let mut printer = OperatorPrinter::new();
        printer.visit(&ast, ast.root().unwrap());
        println!();

        // Every test expression should have at least one operator.
        assert!(
            !printer.lines.is_empty(),
            "expected at least one operator in '{}'",
            input
        );
    }
}

#[test]
fn visitor_counts_operators() {
    // "2+3*4" has one + (in AdditiveExpression) and one * (in MultiplicativeExpression).
    let ast = Parser::parse("2+3*4", Some("test")).unwrap();
    let mut printer = OperatorPrinter::new();
    printer.visit(&ast, ast.root().unwrap());

    let plus_lines: Vec<_> = printer.lines.iter().filter(|l| l.contains("operator: +")).collect();
    let times_lines: Vec<_> = printer.lines.iter().filter(|l| l.contains("operator: *")).collect();
    assert_eq!(plus_lines.len(), 1, "expected one + operator in '2+3*4'");
    assert_eq!(times_lines.len(), 1, "expected one * operator in '2+3*4'");
}

#[test]
fn visitor_nested_has_all_four_operators() {
    // "((1+2)*(3+4))/(5-(6-7))" has +, *, +, /, and -.
    let ast = Parser::parse("((1+2)*(3+4))/(5-(6-7))", Some("test")).unwrap();
    let mut printer = OperatorPrinter::new();
    printer.visit(&ast, ast.root().unwrap());

    let has_plus = printer.lines.iter().any(|l| l.contains("operator: +"));
    let has_times = printer.lines.iter().any(|l| l.contains("operator: *"));
    let has_divide = printer.lines.iter().any(|l| l.contains("operator: /"));
    let has_minus = printer.lines.iter().any(|l| l.contains("operator: -"));
    assert!(has_plus, "expected + in nested expression");
    assert!(has_times, "expected * in nested expression");
    assert!(has_divide, "expected / in nested expression");
    assert!(has_minus, "expected - in nested expression");
}
