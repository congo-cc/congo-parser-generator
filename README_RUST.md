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

### AstMapper Pattern

In addition to the read-only `Visitor` trait, the generated `visitor.rs`
provides an `AstMapper` trait for **functional, bottom-up AST
transformation**.  Use it whenever you need to produce a *new* AST that is
derived from an existing one — either by changing node values or by
restructuring the tree.

`AstMapper` walks the source AST in post-order: children are mapped first,
and the result `NodeId`s are passed up to each parent's `map_*` method along
with a mutable `AstBuilder` for emitting new nodes.  Each method returns a
`MappedNode` describing what the source node becomes:

| Variant | Meaning |
|---------|---------|
| `MappedNode::Node { kind, children }` | Emit a node with the given kind and children. Use the source kind + `mapped_children` for an identity map; change either to transform. |
| `MappedNode::Splice(Vec<NodeId>)` | Remove this node and splice the supplied nodes directly into the parent's child list. |
| `MappedNode::Remove` | Drop this node and all its descendants from the output. |

By default every `map_*` method is an identity map, so an `AstMapper` impl
only needs to override the methods relevant to its transformation.

#### Modifying Existing Node Values (no structural change)

The simplest use of `AstMapper` is rewriting the value of individual leaf
nodes — typically tokens — without altering the tree shape.  Override
`map_token` (or any other relevant `map_*` method), inspect the source
node, and emit a replacement node with new content via the `builder`.

```rust
use my_parser::ast::{Ast, AstBuilder, NodeId, NodeKind};
use my_parser::tokens::TokenType;
use my_parser::visitor::{AstMapper, MappedNode};

struct UppercaseIdents;

impl AstMapper for UppercaseIdents {
    fn map_token(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        builder: &mut AstBuilder,
    ) -> MappedNode {
        // Only rewrite IDENTIFIER tokens; everything else passes through.
        if let Some(TokenType::IDENTIFIER) = source.token_type(source_id) {
            // Add a fresh token to the builder pointing at the desired
            // text in the source string (or in an extended source — see
            // the structural example for details).
            let node = source.node(source_id);
            let new_tid = builder.add_token(
                TokenType::IDENTIFIER,
                node.begin_offset as usize,
                node.end_offset as usize,
                false,
            );
            return MappedNode::Node {
                kind: NodeKind::Token(new_tid),
                children: vec![],
            };
        }
        MappedNode::Node {
            kind: source.kind(source_id).clone(),
            children: vec![],
        }
    }
}

let mut mapper = UppercaseIdents;
let new_ast = mapper.map(&ast); // bottom-up traversal, returns a new Ast
```

When to use it: search-and-replace passes, constant folding of leaves,
unit conversions, value normalization — anything that touches node
*content* but leaves the *shape* of the tree alone.

> Note: because the arena AST stores token text as byte offsets into the
> source string, "rewriting" a token's text really means pointing at a
> different range of an existing string.  See the structural example below
> for the two-phase pattern that synthesizes new text not present in the
> original source.

#### Modifying AST Structure (insertion / deletion)

For structural transformations, override the `map_*` method for an
*interior* node (e.g., a list or expression node) and return a `MappedNode`
whose `children` vector contains the desired sequence — including any
freshly-built nodes added via the `builder`.

The trickiest part of *adding* tokens is that they need text in the
source string.  The recommended pattern is two-phase:

1. **Map phase** — your `AstMapper` calls `builder.add_token(kind, start,
   end, false)` with byte offsets that point past the end of the original
   source, into a position where an *extended* source string will have the
   token text.  Use the result to build the new node.
2. **Rebuild phase** — copy the mapped AST's nodes/tokens into a fresh
   `AstBuilder` and call `builder.build(extended_source, source_name)` to
   produce a final `Ast` whose token offsets resolve against the longer
   string.

To *delete* a node, return `MappedNode::Remove` from its `map_*` method.
To flatten a wrapper node away, return `MappedNode::Splice(mapped_children)`
so its children are absorbed into the parent's child list.

```rust
use my_parser::visitor::{AstMapper, MappedNode};
use my_parser::ast::{Ast, AstBuilder, NodeId, NodeKind};

struct DropDebugComments;

impl AstMapper for DropDebugComments {
    fn map_comment(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        _mapped_children: &[NodeId],
        _builder: &mut AstBuilder,
    ) -> MappedNode {
        if source.text(source_id).starts_with("// DEBUG") {
            MappedNode::Remove
        } else {
            MappedNode::Node {
                kind: source.kind(source_id).clone(),
                children: _mapped_children.to_vec(),
            }
        }
    }
}
```

When to use it: AST rewriting (lowering, desugaring), code-mod tools,
optimization passes, dead-code elimination, macro expansion — anything
that changes which nodes appear in the tree.

For complete worked examples (including the two-phase rebuild trick), see
[`examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs)
and
[`examples/arithmetic/rust-arith2/tests/astmapper_struct2_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_struct2_test.rs).

### Pretty Printing

The generated `pretty.rs` exposes a `PrettyPrinter` trait with two
convenience methods.  `pretty_print(&ast)` renders at the default target
line width (`pretty::DEFAULT_WIDTH = 120`); `pretty_print_to_width(&ast,
n)` renders at an explicit width.

```rust
use my_parser::pretty::{DefaultPrettyPrinter, PrettyPrinter};

// Render the entire AST at the default width (120 columns).
let output = DefaultPrettyPrinter.pretty_print(&ast);
println!("{}", output);

// Render at an explicit narrower width to force more line breaks.
let narrow = DefaultPrettyPrinter.pretty_print_to_width(&ast, 40);
println!("{}", narrow);
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

## Building and Running the Rust Examples

Each example directory has Ant targets for Rust.  The easiest way to build
and exercise every supported grammar is from the project root:

```bash
# Build congocc.jar, regenerate every Rust parser, run all cargo tests.
ant test-rust
```

Or per-grammar:

```bash
cd examples/json && ant test-rust
cd examples/lua && ant test-rust
cd examples/cics && ant test-rust
cd examples/arithmetic && ant test-rust
```

Each example's `ant rust-gen` target invokes `congocc.jar` to (re)generate
the Rust crate(s) and `ant test-rust` then runs `cargo test` inside each
crate.  The arithmetic example also restores hand-written files from
`examples/arithmetic/rust-saved/` after regeneration.

To work with a single crate directly:

```bash
# Generate once via ant, then iterate with cargo:
cd examples/arithmetic && ant rust-gen
cd rust-arith2
cargo build
cargo test
cargo test --test visitor_test -- --nocapture
cargo run -- "(2 + 3) * 4"
```

### AST Traversal Examples

The arithmetic example contains hand-written tests that demonstrate each
of the AST-traversal patterns described above.  They are short, runnable,
and assert their results, so they double as living documentation:

| Pattern | Example test |
|---------|--------------|
| **Visitor** (read-only walk) | [`examples/arithmetic/rust-arith2/tests/visitor_test.rs`](examples/arithmetic/rust-arith2/tests/visitor_test.rs) |
| **AstMapper — modifying node values** | [`examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs) |
| **AstMapper — modifying AST structure** | [`examples/arithmetic/rust-arith2/tests/astmapper_struct2_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_struct2_test.rs) |

Run any one of them with output:

```bash
cd examples/arithmetic/rust-arith2
cargo test --test visitor_test -- --nocapture
cargo test --test astmapper_value_test -- --nocapture
cargo test --test astmapper_struct2_test -- --nocapture
```

## Additional Documentation

These per-crate / per-directory READMEs go into more detail than this
top-level guide:

| File | Content |
|------|---------|
| [`examples/arithmetic/rust-arith1/README.md`](examples/arithmetic/rust-arith1/README.md) | The parse-only arithmetic example (`Arithmetic1.ccc`): grammar, CLI, parsing API, why `evaluate()` returns `EvalError::Unsupported`. |
| [`examples/arithmetic/rust-arith2/README.md`](examples/arithmetic/rust-arith2/README.md) | The full arithmetic evaluator (`Arithmetic2.ccc`): grammar, CLI, evaluation API, all six test suites, and detailed `Visitor` / `AstMapper` / `PrettyPrinter` walkthroughs with worked examples. |
| [`examples/arithmetic/rust-saved/README.md`](examples/arithmetic/rust-saved/README.md) | The hand-written file inventory for `rust-arith1/` and `rust-arith2/`, plus the workflow for restoring them after `ant rust-gen` overwrites the generated tree. |

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
- Rust 1.89+ (2024 edition, for generated code)

## Acknowledgments

Anthropic's Claude Opus 4.6 was used to generate most of the code and documentation for Rust support in this project.

