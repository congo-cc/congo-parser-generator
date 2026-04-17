/// Example: Using a CongoCC-generated Rust parser for JSON.
///
/// This program demonstrates how to:
///   1. Parse a JSON string into an AST
///   2. Navigate the AST using parent/child/sibling relationships
///   3. Use the Visitor trait to traverse all nodes
///   4. Pretty-print the AST
///
/// To build:
///   cd examples/json && ant rust-gen
///   cp rust-example/main.rs rust-jsonparser/examples/json_demo.rs
///   cd rust-jsonparser && cargo run --example json_demo

use org_parsers_json::parser::Parser;
use org_parsers_json::ast::{Ast, NodeId, NodeKind};
use org_parsers_json::tokens::TokenType;
use org_parsers_json::visitor::Visitor;

fn main() {
    let input = r#"{
        "name": "CongoCC",
        "version": 1.0,
        "features": ["java", "python", "csharp", "rust"],
        "active": true,
        "nested": {"key": "value"}
    }"#;

    // Parse the JSON input
    let ast = Parser::parse(input, Some("example.json"))
        .expect("Failed to parse JSON");

    // Print basic statistics
    let root = ast.root().expect("AST has no root node");
    println!("Parsed {} nodes from {} characters", ast.node_count(), input.len());
    println!("Root node: {:?}", ast.kind(root));

    // Walk the AST and print all string literal tokens
    println!("\nString literals found:");
    for i in 0..ast.node_count() {
        let nid = NodeId(i as u32);
        if let NodeKind::Token(tid) = ast.kind(nid) {
            if ast.token(*tid).kind == TokenType::STRING_LITERAL {
                println!("  {}", ast.text(nid));
            }
        }
    }

    // Reconstruct original text from the AST
    let reconstructed = ast.text(root);
    println!("\nReconstructed text length: {} chars", reconstructed.len());

    // Count nodes by kind using a visitor
    let mut counter = NodeCounter { count: 0 };
    counter.visit(&ast, root);
    println!("Total nodes visited: {}", counter.count);
}

/// A simple visitor that counts all nodes in the AST.
/// The default `visit` dispatches to kind-specific methods, which
/// by default call `visit_children` to recurse.  By overriding
/// `visit_children` we can intercept every node.
struct NodeCounter {
    count: usize,
}

impl Visitor for NodeCounter {
    fn visit_children(&mut self, ast: &Ast, id: NodeId) {
        self.count += 1;
        // Continue recursion into children
        for child in ast.children(id) {
            self.visit(ast, child);
        }
    }
}
