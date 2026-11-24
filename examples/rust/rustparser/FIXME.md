# Code Injection Status

This parser was generated from a grammar that contains INJECT blocks — Java code
embedded in the grammar to add methods and fields to generated AST node classes.
The Rust code generator emits these as FIXME comments in `inject.rs` because the
arena-based AST architecture requires different calling conventions than Java's
per-type class model.

This file is regenerated on each build.  Do not put your implementations here;
instead, create a separate file (see "Where to Put Your Code" below).

## INJECT Block Inventory

| Target Class | Type | Description |
|---|---|---|
| `LiteralExpression` | AST node | Methods/fields for LiteralExpression nodes |
| `ClosureExpression` | AST node | Methods/fields for ClosureExpression nodes |
| `ConstBlock` | AST node | Methods/fields for ConstBlock nodes |
| `BlockExpression` | AST node | Methods/fields for BlockExpression nodes |
| `Placeholder` | AST node | Methods/fields for Placeholder nodes |
| `LabeledBlock` | AST node | Methods/fields for LabeledBlock nodes |
| `MethodCall` | AST node | Methods/fields for MethodCall nodes |
| `FieldExpression` | AST node | Methods/fields for FieldExpression nodes |
| `AwaitExpression` | AST node | Methods/fields for AwaitExpression nodes |
| `TupleIndexingExpression` | AST node | Methods/fields for TupleIndexingExpression nodes |
| `CallExpression` | AST node | Methods/fields for CallExpression nodes |
| `IndexExpression` | AST node | Methods/fields for IndexExpression nodes |
| `ErrorPropagationExpression` | AST node | Methods/fields for ErrorPropagationExpression nodes |
| `ParentheticalExpression` | AST node | Methods/fields for ParentheticalExpression nodes |
| `ArrayExpression` | AST node | Methods/fields for ArrayExpression nodes |
| `UnaryExpression` | AST node | Methods/fields for UnaryExpression nodes |
| `TypeCastExpression` | AST node | Methods/fields for TypeCastExpression nodes |
| `MultiplicativeExpression` | AST node | Methods/fields for MultiplicativeExpression nodes |
| `AdditiveExpression` | AST node | Methods/fields for AdditiveExpression nodes |
| `ShiftExpression` | AST node | Methods/fields for ShiftExpression nodes |
| `AndExpression` | AST node | Methods/fields for AndExpression nodes |
| `CaretExpression` | AST node | Methods/fields for CaretExpression nodes |
| `OrExpression` | AST node | Methods/fields for OrExpression nodes |
| `ComparisonExpression` | AST node | Methods/fields for ComparisonExpression nodes |
| `LazyAndExpression` | AST node | Methods/fields for LazyAndExpression nodes |
| `LazyOrExpression` | AST node | Methods/fields for LazyOrExpression nodes |
| `RangeExpression` | AST node | Methods/fields for RangeExpression nodes |
| `AssignmentExpression` | AST node | Methods/fields for AssignmentExpression nodes |
| `BreakExpression` | AST node | Methods/fields for BreakExpression nodes |
| `ContinueExpression` | AST node | Methods/fields for ContinueExpression nodes |
| `ReturnExpression` | AST node | Methods/fields for ReturnExpression nodes |
| `StructExpression` | AST node | Methods/fields for StructExpression nodes |
| `PathExpression` | AST node | Methods/fields for PathExpression nodes |
| `AsyncBlockExpression` | AST node | Methods/fields for AsyncBlockExpression nodes |
| `UnsafeBlockExpression` | AST node | Methods/fields for UnsafeBlockExpression nodes |
| `ForExpression` | AST node | Methods/fields for ForExpression nodes |
| `IfExpression` | AST node | Methods/fields for IfExpression nodes |
| `IfLetExpression` | AST node | Methods/fields for IfLetExpression nodes |
| `MatchExpression` | AST node | Methods/fields for MatchExpression nodes |
| `WildcardPattern` | AST node | Methods/fields for WildcardPattern nodes |
| `RestPattern` | AST node | Methods/fields for RestPattern nodes |
| `LiteralPattern` | AST node | Methods/fields for LiteralPattern nodes |
| `IdentifierPattern` | AST node | Methods/fields for IdentifierPattern nodes |
| `ReferencePattern` | AST node | Methods/fields for ReferencePattern nodes |
| `PathPattern` | AST node | Methods/fields for PathPattern nodes |
| `StructPattern` | AST node | Methods/fields for StructPattern nodes |
| `TuplePattern` | AST node | Methods/fields for TuplePattern nodes |
| `TupleStructPattern` | AST node | Methods/fields for TupleStructPattern nodes |
| `SlicePattern` | AST node | Methods/fields for SlicePattern nodes |
| `RangePattern` | AST node | Methods/fields for RangePattern nodes |
| `MacroInvocation` | AST node | Methods/fields for MacroInvocation nodes |
| `RustParser` | Parser class | Utility methods (e.g., main) |

## Ast / NodeId API Reference

The Rust parser uses an arena-based AST where all nodes live in a single `Vec<Node>`
inside the `Ast` struct, accessed via typed `NodeId` indices.  This is fundamentally
different from Java's per-type class hierarchy.

### Key Differences from Java

| Java INJECT Pattern | Rust Arena Equivalent |
|---|---|
| `this` (the current node) | `id: NodeId` (passed as parameter) |
| `get(i)` (ith child) | `ast.child_at(id, i)` returns `Option<NodeId>` |
| `size()` (child count) | `ast.child_count(id)` returns `usize` |
| `toString()` (node text) | `ast.text(id)` returns `&str` |
| `get(i) instanceof MINUS` | `ast.child_is_token(id, i, TokenType::MINUS)` |
| `get(i) instanceof Foo` | `matches!(ast.kind(child), NodeKind::Foo)` |
| `Double.parseDouble(s)` | `s.parse::<f64>().unwrap()` |
| Method on subclass | Method on `Ast` taking `NodeId`, dispatched by `NodeKind` |

### Ast Methods

**Node access:**
- `ast.node(id) -> &Node` — access a node by ID
- `ast.kind(id) -> &NodeKind` — get the kind of a node
- `ast.root() -> Option<NodeId>` — get the root node
- `ast.node_count() -> usize` — total number of nodes

**Child navigation:**
- `ast.child_at(id, index) -> Option<NodeId>` — get nth child (0-indexed)
- `ast.child_count(id) -> usize` — number of direct children
- `ast.children(id) -> ChildIter` — iterator over direct children
- `ast.first_child(id) -> Option<NodeId>` — first child
- `ast.last_child(id) -> Option<NodeId>` — last child

**Token queries:**
- `ast.child_is_token(id, index, TokenType::X) -> bool` — check if nth child is a specific token
- `ast.token_type(id) -> Option<TokenType>` — get token type if node is a token leaf
- `ast.first_child_of_type(id, TokenType::X) -> Option<NodeId>` — find first child token of type

**Text access:**
- `ast.text(id) -> &str` — source text spanned by node
- `ast.token_text(tid) -> &str` — source text of a stored token
- `ast.source() -> &str` — full source text

**Tree navigation:**
- `ast.parent(id) -> Option<NodeId>` — parent node
- `ast.next_sibling(id) -> Option<NodeId>` — next sibling
- `ast.prev_sibling(id) -> Option<NodeId>` — previous sibling
- `ast.ancestors(id) -> AncestorIter` — iterator over ancestors
- `ast.descendants(id) -> DescendantIter` — depth-first pre-order traversal
- `ast.children_of_kind(id, |kind| matches!(kind, NodeKind::X)) -> Vec<NodeId>`

**Location:**
- `ast.begin_line(id) -> usize` — 1-based line number
- `ast.begin_column(id) -> usize` — 1-based column number
- `ast.location(id) -> String` — `"file:line:col"` string

**Display:**
- `ast.dump(id) -> String` — indented text dump of subtree (for debugging)

### Example: Translating an INJECT Method

Java INJECT (from Arithmetic grammar):
```java
INJECT AdditiveExpression : {
    public double evaluate() {
        double result = get(0).evaluate();
        for (int i = 1; i < size(); i += 2) {
            boolean subtract = get(i) instanceof MINUS;
            double nextOperand = get(i + 1).evaluate();
            if (subtract) result -= nextOperand;
            else result += nextOperand;
        }
        return result;
    }
}
```

Equivalent Rust implementation using the arena API:
```rust
use crate::ast::{Ast, NodeId, NodeKind};
use crate::tokens::TokenType;

impl Ast {
    pub fn evaluate(&self, id: NodeId) -> f64 {
        match self.kind(id) {
            NodeKind::NUMBER => {
                self.text(id).parse::<f64>().unwrap()
            }
            NodeKind::AdditiveExpression => {
                let mut result = self.evaluate(self.child_at(id, 0).unwrap());
                let count = self.child_count(id);
                let mut i = 1;
                while i < count {
                    let subtract = self.child_is_token(id, i, TokenType::MINUS);
                    let operand = self.evaluate(self.child_at(id, i + 1).unwrap());
                    if subtract { result -= operand; } else { result += operand; }
                    i += 2;
                }
                result
            }
            NodeKind::MultiplicativeExpression => {
                let mut result = self.evaluate(self.child_at(id, 0).unwrap());
                let count = self.child_count(id);
                let mut i = 1;
                while i < count {
                    let divide = self.child_is_token(id, i, TokenType::DIVIDE);
                    let operand = self.evaluate(self.child_at(id, i + 1).unwrap());
                    if divide { result /= operand; } else { result *= operand; }
                    i += 2;
                }
                result
            }
            NodeKind::ParentheticalExpression => {
                self.evaluate(self.child_at(id, 1).unwrap())
            }
            NodeKind::Root => {
                self.evaluate(self.child_at(id, 0).unwrap())
            }
            _ => panic!("evaluate() not implemented for {:?}", self.kind(id)),
        }
    }
}
```

Key patterns:
- **One Rust method replaces N Java methods**: Since there are no per-type structs,
  a single `match` on `NodeKind` dispatches to the appropriate logic.
- **`child_at(id, i)`** replaces Java's `get(i)`.
- **`child_is_token(id, i, TokenType::X)`** replaces Java's `get(i) instanceof X`.
- **`text(id).parse::<f64>()`** replaces Java's `Double.parseDouble(toString())`.
- **Recursive calls** pass the child `NodeId` explicitly.

## Where to Put Your Code

Since `inject.rs` is regenerated on every build, do **not** edit it directly.
Instead, use one of these approaches:

### Approach 1: Wrapper Crate (recommended)

Create a separate crate that depends on the generated parser library:

```
my-calculator/
  Cargo.toml          # depends on the generated parser crate
  src/
    main.rs           # binary entry point
    evaluate.rs       # your evaluate() implementation
```

```toml
# my-calculator/Cargo.toml
[dependencies]
org-parsers-rust = { path = "../org-parsers-rust" }
```

### Approach 2: Grammar Preprocessor

Use `#if !__rust__` to exclude Java-only INJECT blocks from Rust generation:

```
#if !__rust__
INJECT AdditiveExpression : {
    public double evaluate() { /* Java code */ }
}
#endif
```

This suppresses the FIXME comment for that INJECT block in the generated Rust code.

## Test Methodology

### Unit Tests

Write tests that parse known input, then call your implemented methods on the
resulting AST:

```rust
#[test]
fn test_evaluate_addition() {
    let ast = Parser::parse("1 + 2", Some("test")).unwrap();
    let root = ast.root().unwrap();
    assert_eq!(ast.evaluate(root), 3.0);
}

#[test]
fn test_evaluate_precedence() {
    let ast = Parser::parse("2 + 3 * 4", Some("test")).unwrap();
    let root = ast.root().unwrap();
    assert_eq!(ast.evaluate(root), 14.0);  // multiplication before addition
}
```

### AST Dump for Debugging

Use `ast.dump(id)` to visualize the tree structure when a test fails:

```rust
let ast = Parser::parse("1 + 2 * 3", Some("test")).unwrap();
let root = ast.root().unwrap();
println!("{}", ast.dump(root));
// Output:
//   Root
//     AdditiveExpression
//       Token(NUMBER, "1")
//       Token(PLUS, "+")
//       MultiplicativeExpression
//         Token(NUMBER, "2")
//         Token(TIMES, "*")
//         Token(NUMBER, "3")
```

### Comparison with Java

Run both the Java and Rust parsers on the same input to verify equivalent behavior:

```bash
# Java (from the ex2 package):
java -cp . ex2.Calc "2 + 3 * 4"

# Rust (in your wrapper crate):
cargo test
```
