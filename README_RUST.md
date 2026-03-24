# CongoCC Rust Code Generation

CongoCC can generate complete, self-contained Rust parser crates from `.ccc` grammar files.
Each generated crate includes a lexer, parser, arena-based AST, visitor trait, AST mapper,
and Wadler-Lindig pretty-printer.

## Quick Start

```bash
# Generate a Rust parser from a grammar file
java -jar congocc.jar -n -lang rust -d my-parser examples/json/JSON.ccc

# Build and test
cd my-parser
cargo test
```

## Generated Files

| File | Purpose |
|------|---------|
| `Cargo.toml` | Crate metadata and dependencies (none required) |
| `lib.rs` | Crate root with module declarations and re-exports |
| `tokens.rs` | Token type enum and token struct |
| `lexer.rs` | NFA-based lexer with multi-state support |
| `parser.rs` | Recursive descent parser with lookahead and scan routines |
| `ast.rs` | Arena-based AST with `NodeId`, `Ast`, and `AstBuilder` |
| `error.rs` | `ParseError` type with location and expected-set info |
| `visitor.rs` | `Visitor` trait and `AstMapper` for AST transformation |
| `pretty.rs` | Wadler-Lindig pretty-printer with configurable width |
| `tests/parse_files.rs` | Integration test harness that parses `test-data/` files |

## Usage

### Parsing

```rust
use my_parser::parser::Parser;

// Parse a string, returning an owned AST
let ast = Parser::parse(input, Some("filename.ext"))?;

// The AST owns all nodes; navigate using NodeId handles
let root = ast.root().expect("AST has no root");
println!("Root kind: {:?}", ast.kind(root));
println!("Source text: {}", ast.text(root));
```

### AST Navigation

```rust
use my_parser::ast::{Ast, NodeId, NodeKind};

// Parent, children, siblings
if let Some(parent) = ast.parent(node_id) { /* ... */ }
let children = ast.children(node_id);
if let Some(next) = ast.next_sibling(node_id) { /* ... */ }

// Check token types
use my_parser::tokens::TokenType;
if let NodeKind::Token(tid) = ast.kind(nid) {
    if ast.token(*tid).kind == TokenType::STRING_LITERAL {
        println!("Found string: {}", ast.text(nid));
    }
}
```

### Visitor Pattern

The generated `Visitor` trait has a `visit` method that dispatches to
kind-specific methods (e.g., `visit_root`, `visit_array`). Each kind method
defaults to calling `visit_children` for recursion.

```rust
use my_parser::visitor::Visitor;
use my_parser::ast::{Ast, NodeId};

struct Counter { count: usize }

impl Visitor for Counter {
    fn visit_children(&mut self, ast: &Ast, id: NodeId) {
        self.count += 1;
        for child in ast.children(id) {
            self.visit(ast, child);
        }
    }
}

let mut counter = Counter { count: 0 };
counter.visit(&ast, root);
println!("Total nodes: {}", counter.count);
```

### Pretty Printing

```rust
use my_parser::pretty::{DefaultPrettyPrinter, PrettyPrinter};

let printer = DefaultPrettyPrinter::new(80);  // 80-column width
let output = printer.pretty_print(&ast, root);
println!("{}", output);
```

## Supported Grammars

The following grammars generate fully compiling and tested Rust parsers:

| Grammar | Status | Test Files | Notes |
|---------|--------|------------|-------|
| JSON (`JSON.ccc`) | Working | 4 JSON files | Simplest grammar, good starting point |
| JSONC (`JSONC.ccc`) | Working | 5 JSON files | JSON with comments |
| Lua (`Lua.ccc`) | Working | 39 Lua files | Complete Lua 5.4 parser |
| CICS (`Cics.ccc`) | Working | 5 CICS files | IBM CICS command parser |
| Arithmetic1 (`Arithmetic1.ccc`) | Working | Manual | Simple expression parser |
| Arithmetic2 (`Arithmetic2.ccc`) | Working | Manual | Expression parser variant |

### Complex Grammars (Generate but Need Manual INJECT Fixes)

| Grammar | Status | Notes |
|---------|--------|-------|
| Java (`Java.ccc`) | Generates | Extensive INJECT blocks need manual Rust porting |
| C# (`CSharp.ccc`) | Generates | Extensive INJECT blocks need manual Rust porting |
| Python (`Python.ccc`) | Generates | Extensive INJECT blocks need manual Rust porting |
| Preprocessor (`Preprocessor.ccc`) | Generates | Heavily code-action-driven, needs manual work |

Complex grammars with Java INJECT blocks generate parsers that compile except for
the injected Java code. The translator emits FIXME comments for untranslatable
constructs. To complete these parsers, search for `FIXME(congocc)` in the generated
code and implement the Rust equivalents manually.

## Build System Integration

Each example directory has Ant targets for Rust:

```bash
# Generate and test Rust parsers for all supported grammars
ant test-rust

# Or per-grammar:
cd examples/json && ant test-rust
cd examples/lua && ant test-rust
cd examples/cics && ant test-rust
cd examples/arithmetic && ant test-rust
```

## Architecture

### Arena-Based AST

The AST uses a flat `Vec<Node>` arena indexed by `NodeId(u32)`. This design:
- Avoids recursive `Box<T>` allocations
- Enables safe parent/child/sibling references without lifetimes
- Makes tree traversal cache-friendly
- Supports `O(1)` node count and `O(1)` access by ID

### NFA-Based Lexer

The lexer uses precomputed NFA transition tables generated from the grammar's
token specifications. It supports:
- Multiple lexical states with state switching
- Token activation/deactivation
- Contextual keywords
- Unicode character classes
- Longest-match semantics

### Recursive Descent Parser

The parser uses recursive descent with computed first/follow sets for
disambiguation. Key features:
- Scan-ahead routines for resolving ambiguity
- Lookahead predicates
- Grammar assertions (`ASSERT`/`ENSURE`)
- Tree-building annotations (`#NodeName`)
- Zero-copy token text (references into source string)

## Zero Dependencies

Generated crates have no external dependencies. The `Cargo.toml` requires only
the Rust standard library.

## Requirements

- CongoCC (`congocc.jar`) with Rust support
- Java 8+ (to run CongoCC)
- Rust 1.56+ (2021 edition, for generated code)
