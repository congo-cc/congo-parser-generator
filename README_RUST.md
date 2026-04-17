# CongoCC Rust Code Generation

CongoCC can generate complete, self-contained Rust parser crates from `.ccc` grammar files.
Each generated crate includes a lexer, parser, arena-based AST, visitor trait, AST mapper,
and Wadler-Lindig pretty-printer.

## Building with Rust Support
By default, this project's build.xml files have property `rust.enabled` set to `false`, which 
disables Rust support.  To enable the generation of Rust parsers, first 
[install Rust](https://rust-lang.org/tools/install/), then use `ant -Drust.enabled=true` as shown below:

```bash
ant build -Drust.enabled=true
ant test -Drust.enabled=true
```

## Quick Start
Assuming CongoCC was built with Rust support enabled:
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

// Parser::parse(input, source_name) takes two parameters:
//   input       — &str containing the text to parse
//   source_name — Option<&str> identifying the source for error messages
//                 (e.g., a filename).  Pass None for anonymous input.
let ast = Parser::parse(input, Some("filename.ext"))?;

// The AST owns all nodes; navigate using NodeId handles
let root = ast.root().expect("AST has no root");
println!("Root kind: {:?}", ast.kind(root));
println!("Source text: {}", ast.text(root));
```

The `source_name` appears in error messages and in `ast.location(id)`,
which returns a string like `"filename.ext:1:5"`.  Pass `None` when
the input has no meaningful name; error messages will use `"<unknown>"`.

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

## AST Traversal and Transformation

The generated code supports three levels of AST processing, each with its
own API surface.  Choosing the right one depends on whether you need to
read, rewrite values, or change structure.

| Use Case | API | Result |
|----------|-----|--------|
| **Read-only traversal** | `Visitor` trait, `dump()`, `PrettyPrinter` | No AST changes; analysis, metrics, display |
| **Value modification** | `AstMapper` trait (with `AstBuilder`) | A *new* AST with replaced/removed nodes |
| **Structural modification** | In-place synthetic-token API on `&mut Ast` | The *same* AST mutated, with new nodes and subtrees |

### Read-Only Traversal

#### Visitor Pattern

The generated `Visitor` trait has a `visit` method that dispatches to
kind-specific methods (e.g., `visit_root`, `visit_array`).  Each kind
method defaults to calling `visit_children` for recursion.  Override any
`visit_*` method to intercept processing for that node kind.

```rust
use my_parser::visitor::Visitor;
use my_parser::ast::{Ast, NodeId};

struct Counter { count: usize }

impl Visitor for Counter {
    fn default_visit(&mut self, ast: &Ast, id: NodeId) {
        self.count += 1;
        self.visit_children(ast, id);
    }
}

let mut counter = Counter { count: 0 };
counter.visit(&ast, root);
println!("Total nodes: {}", counter.count);
```

When to use it: analysis passes (counting nodes, collecting identifiers,
computing metrics), extracting information without modifying the tree.

For a worked example, see
[`examples/arithmetic/rust-arith2/tests/visitor_test.rs`](examples/arithmetic/rust-arith2/tests/visitor_test.rs).

#### Pretty Printing

The generated `pretty.rs` implements a Wadler-Lindig pretty-printer.
The `PrettyPrinter` trait exposes two convenience methods:

```rust
use my_parser::pretty::{DefaultPrettyPrinter, PrettyPrinter};

// Render at the default width (120 columns).
let output = DefaultPrettyPrinter.pretty_print(&ast);
println!("{}", output);

// Render at an explicit narrower width to force more line breaks.
let narrow = DefaultPrettyPrinter.pretty_print_to_width(&ast, 40);
println!("{}", narrow);
```

The pretty-printer is grammar-aware: it labels each node by its
`NodeKind`, nests children with indentation, and uses Wadler-Lindig
line-break decisions to fit output within the target width.

#### AST Dump

For quick debugging, `Ast::dump(id)` produces an indented text
representation of the subtree rooted at `id`:

```rust
let root = ast.root().unwrap();
println!("{}", ast.dump(root));
```

Output (for `2+3*4`):

```text
AdditiveExpression
  MultiplicativeExpression
    Token(NUMBER, "2")
  Token(PLUS, "+")
  MultiplicativeExpression
    Token(NUMBER, "3")
    Token(TIMES, "*")
    Token(NUMBER, "4")
```

Unlike the pretty-printer, `dump()` always uses a fixed 2-space indent
with no line-width logic.  It is intended for debugging and test output,
not for producing formatted source code.

### Value Modification with AstMapper

`AstMapper` is a **functional, bottom-up transformation** that produces a
*new* AST from an existing one.  It walks the source AST in post-order
(children first), and for each node, calls a `map_*` method that returns a
`MappedNode` describing what the node becomes in the output:

| Variant | Meaning |
|---------|---------|
| `MappedNode::Node { kind, children }` | Emit a node with the given kind and children.  Use the source kind + `mapped_children` for identity; change either to transform. |
| `MappedNode::Splice(Vec<NodeId>)` | Remove this node and splice children directly into the parent's child list. |
| `MappedNode::Remove` | Drop this node and all its descendants from the output. |

Every `map_*` method defaults to an identity map, so an `AstMapper` only
needs to override the methods relevant to its transformation.  Call
`mapper.map(&source_ast)` to drive the traversal and get the new `Ast`.

`AstMapper` works best for transformations that **change node values or
remove/splice nodes** while preserving the overall tree shape.  Because
the arena AST stores token text as byte offsets into the original source
string, adding entirely new tokens requires careful offset management (see
the note below).

```rust
use my_parser::ast::{Ast, AstBuilder, NodeId, NodeKind};
use my_parser::tokens::TokenType;
use my_parser::visitor::{AstMapper, MappedNode};

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

let mut mapper = DropDebugComments;
let new_ast = mapper.map(&ast);
```

When to use it: node removal, node type changes, tree flattening
(`Splice`), search-and-replace passes, constant folding of leaves — any
transformation that rearranges or filters existing nodes.

> **Limitation for structural additions.**  Because parser-produced tokens
> reference the original source string by byte offset, *adding* tokens
> that don't exist in the source is awkward with `AstMapper` alone — you
> must either construct an extended source string with the new token text
> appended and carefully manage the offsets, or re-parse the output.  For
> a worked example of this approach, see
> [`examples/arithmetic/rust-arith2/tests/astmapper_struct_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_struct_test.rs).
> For most structural editing use cases, the synthetic-token API described
> below is simpler and more powerful.

For a worked example of value modification, see
[`examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs).

### Structural Modification with Synthetic Tokens

For transformations that **insert new nodes, replace subtrees, or
restructure the AST**, the in-place mutation API on `&mut Ast` is the
recommended approach.  It avoids the offset-management burden of
`AstMapper` by letting tokens carry their own text.

The key concept is `TokenSource`:  parser-produced tokens have
`TokenSource::Original { start, end }` — byte offsets into the source
string (zero-copy).  Synthetically created tokens have
`TokenSource::Synthetic(Box<str>)` — they own their text directly.  This
means new tokens can be created anywhere without coordinating with the
source string.

#### Mutation Methods on `&mut Ast`

| Method | Purpose |
|--------|---------|
| `ast.new_synthetic_token(kind, text)` | Create a new token node with owned text.  Returns `NodeId`. |
| `ast.new_node(kind)` | Create a new production node (non-token) with no children.  Returns `NodeId`. |
| `ast.append_child(parent, child)` | Append `child` as the last child of `parent`. |
| `ast.insert_after(sibling, new_node)` | Insert `new_node` after `sibling` in its parent's child list. |
| `ast.detach(id)` | Remove `id` from its parent (the node stays in the arena). |

#### Text Regeneration and Validation

After editing, two methods support text output and round-trip validation:

- **`ast.unparse()`** — Depth-first walk that concatenates all token text
  in document order, producing a string that reflects the AST's current
  state (including synthetic tokens).
- **`ast.reparse()`** — Calls `unparse()` then feeds the result back
  through the parser, producing a fresh AST.  If `reparse()` succeeds,
  the edited AST is syntactically valid.  No custom validation code is
  needed — the original parser provides the validation.

#### Example: Appending "+1" to an Expression

This is the core pattern from
[`synthetic_struct2_test.rs`](examples/arithmetic/rust-arith2/tests/synthetic_struct2_test.rs):

```rust
use my_parser::ast::{Ast, NodeId, NodeKind};
use my_parser::tokens::TokenType;

fn append_plus_one(ast: &mut Ast, add_expr: NodeId) {
    // Create synthetic tokens — they own their text, no source string needed.
    let plus = ast.new_synthetic_token(TokenType::PLUS, "+");
    let one  = ast.new_synthetic_token(TokenType::NUMBER, "1");

    // Wrap the NUMBER in a MultiplicativeExpression to match the grammar.
    let mult = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(mult, one);

    // Attach to the existing AdditiveExpression.
    ast.append_child(add_expr, plus);
    ast.append_child(add_expr, mult);
}

// After editing, the AST can be evaluated, unparsed, or reparsed:
let text = ast.unparse();           // "2+3*4+1"
let value = ast.evaluate_root()?;   // 15.0
let fresh = ast.reparse()?;         // re-parsed AST (validates syntax)
```

#### Example: Replacing Nodes with Synthesized Subtrees

For more complex edits,
[`synthetic_struct3_test.rs`](examples/arithmetic/rust-arith2/tests/synthetic_struct3_test.rs)
demonstrates replacing every occurrence of a number literal with a
parenthesized expression.  The pattern is: collect target nodes before
mutating, build the replacement subtree, then use `insert_after` +
`detach` to swap it in:

```rust
// Replace NUMBER("5") with a synthesized (2+3) subtree.
fn replace_with_two_plus_three(ast: &mut Ast, target: NodeId) {
    let open  = ast.new_synthetic_token(TokenType::OPEN_PAREN, "(");
    let close = ast.new_synthetic_token(TokenType::CLOSE_PAREN, ")");
    let two   = ast.new_synthetic_token(TokenType::NUMBER, "2");
    let plus  = ast.new_synthetic_token(TokenType::PLUS, "+");
    let three = ast.new_synthetic_token(TokenType::NUMBER, "3");

    // Build the inner expression tree matching the grammar structure.
    let me_2 = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(me_2, two);
    let me_3 = ast.new_node(NodeKind::MultiplicativeExpression);
    ast.append_child(me_3, three);
    let add = ast.new_node(NodeKind::AdditiveExpression);
    ast.append_child(add, me_2);
    ast.append_child(add, plus);
    ast.append_child(add, me_3);

    // Wrap in a ParentheticalExpression.
    let paren = ast.new_node(NodeKind::ParentheticalExpression);
    ast.append_child(paren, open);
    ast.append_child(paren, add);
    ast.append_child(paren, close);

    // Swap into the tree: insert after the old node, then detach it.
    ast.insert_after(target, paren);
    ast.detach(target);
}
```

When to use it: AST rewriting (lowering, desugaring), code-mod tools,
macro expansion, optimization passes, instrumentation — any
transformation that inserts, replaces, or rearranges subtrees.

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
constructs. To complete these parsers, consult the `FIXME.md` and `inject.rs` files in the generated
code to determine how and where the Rust translation of Java code needs to be provided.

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
of the traversal and transformation patterns described above.  They are
short, runnable, and assert their results, so they double as living
documentation:

| Use Case | Example test | What it demonstrates |
|----------|--------------|----------------------|
| **Read-only** | [`visitor_test.rs`](examples/arithmetic/rust-arith2/tests/visitor_test.rs) | `Visitor` trait with kind-specific overrides, depth tracking |
| **Value modification** | [`astmapper_value_test.rs`](examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs) | `AstMapper` rewriting NUMBER tokens via `map_token` |
| **Structural (append)** | [`synthetic_struct2_test.rs`](examples/arithmetic/rust-arith2/tests/synthetic_struct2_test.rs) | `new_synthetic_token`, `new_node`, `append_child` to add `+1` |
| **Structural (replace)** | [`synthetic_struct3_test.rs`](examples/arithmetic/rust-arith2/tests/synthetic_struct3_test.rs) | `insert_after` + `detach` to replace literals with subtrees |

Run any one of them with output:

```bash
cd examples/arithmetic/rust-arith2
cargo test --test visitor_test -- --nocapture
cargo test --test astmapper_value_test -- --nocapture
cargo test --test synthetic_struct2_test -- --nocapture
cargo test --test synthetic_struct3_test -- --nocapture
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

