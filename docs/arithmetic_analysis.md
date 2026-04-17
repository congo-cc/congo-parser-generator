# Code Injection Analysis: Arithmetic Grammar INJECT Blocks and Rust Code Generation

## Overview

The Arithmetic grammars (`Arithmetic1.ccc` and `Arithmetic2.ccc`) demonstrate CongoCC's
INJECT mechanism, which allows grammar authors to embed Java code into generated AST node
classes, parser classes, and interfaces. This document analyzes how INJECT blocks flow
through the code generation pipeline and what happens when the target language is Rust.

## Grammar INJECT Blocks

### Arithmetic1.ccc

Defines the base grammar with two INJECT blocks:

**1. INJECT PARSER_CLASS** (lines 47-76) — Adds a `main()` method to the generated parser
class. This method reads an arithmetic expression, parses it, dumps the AST, and calls
`evaluate()` on the root node:

```java
INJECT PARSER_CLASS :
    import java.util.Scanner;
{
    static public void main(String[] args) throws ParseException {
        // ... reads input, creates parser, calls parser.Root(),
        // dumps AST, calls root.evaluate()
    }
}
```

**2. INJECT Node** (lines 78-81) — Adds a default `evaluate()` method to the `Node`
interface. In Java, this becomes a `default` interface method that throws
`UnsupportedOperationException`, providing a base implementation that specific node types
override:

```java
INJECT Node :
{
    default double evaluate() {throw new UnsupportedOperationException();}
}
```

### Arithmetic2.ccc

Includes Arithmetic1.ccc via `INCLUDE "Arithmetic1.ccc"` and then adds INJECT blocks
for five AST node types, each providing an `evaluate()` method that traverses children
to compute the expression value:

| INJECT Target | What It Does |
|---|---|
| `NUMBER` (lines 7-12) | Parses the token text as a `double` via `Double.parseDouble(toString())` |
| `AdditiveExpression` (lines 14-26) | Iterates children pairwise: checks `get(i) instanceof MINUS` to decide +/- |
| `MultiplicativeExpression` (lines 28-40) | Same pattern: checks `get(i) instanceof DIVIDE` to decide */÷ |
| `ParentheticalExpression` (lines 42-47) | Returns `get(1).evaluate()` — the expression between parentheses |
| `Root` (lines 49-54) | Returns `get(0).evaluate()` — delegates to the top-level expression |

Together, the INJECT blocks turn the parser from a pure syntax checker into an
expression evaluator. Arithmetic1 (package `ex1`) has the grammar and main method but
only a stub `evaluate()` that throws. Arithmetic2 (package `ex2`) adds the real
implementations, making `ex2.Calc` a working calculator.

## Code Generation Pipeline

### Stage 1: Parsing — INJECT Blocks Become AST Nodes

When CongoCC parses a `.ccc` file, each INJECT block becomes a `CodeInjection` AST node
(defined in `build/generated-java/org/congocc/parser/tree/CodeInjection.java`). Key
fields:

- `name` — The target class name (e.g., `"Node"`, `"AdditiveExpression"`, `"PARSER_CLASS"`)
- `body` — A `ClassOrInterfaceBody` containing the method/field declarations
- `extendsList` / `implementsList` — Any extends/implements clauses

These are collected in `Grammar.codeInjections` (a `List<Node>`).

### Stage 2: Indexing — CodeInjector Builds a Lookup Table

`CodeInjector` (`src/java/org/congocc/codegen/java/CodeInjector.java`) processes all
collected `CodeInjection` nodes and builds maps keyed by fully-qualified class name:

```
bodyDeclarations:  Map<String, List<ClassOrInterfaceBodyDeclaration>>
extendsLists:      Map<String, Set<ObjectType>>
implementsLists:   Map<String, Set<ObjectType>>
```

For example, after processing Arithmetic2.ccc, the `bodyDeclarations` map would contain
entries like:

- `"ex2.AdditiveExpression"` → `[MethodDeclaration for evaluate()]`
- `"ex2.MultiplicativeExpression"` → `[MethodDeclaration for evaluate()]`
- `"ex2.Root"` → `[MethodDeclaration for evaluate()]`
- etc.

### Stage 3: Translation — RustTranslator Converts Java to Rust

`TemplateGlobals.translateInjectedClass(String name)` (line 239 of `TemplateGlobals.java`)
is the bridge between templates and the translator. It:

1. Calls `translator.startClass(name, false, null)` to set up context
2. Calls `translator.translateInjectedClass(grammar.getInjector(), name)` to do the work
3. Calls `translator.endClass(name, false, null)` to clean up
4. Returns the translated code as a String

`RustTranslator.translateInjectedClass()` (lines 447-516 of `RustTranslator.java`)
iterates over the body declarations for the named class and attempts to translate each
one from Java to Rust:

```java
for (ClassOrInterfaceBodyDeclaration decl : decls) {
    try {
        if (decl instanceof FieldDeclaration) {
            translateStatement(decl, indent, result);
        } else if (decl instanceof MethodDeclaration) {
            translateStatement(decl, indent, result);
        } else {
            throw new UnsupportedOperationException(...);
        }
    } catch (Exception e) {
        // Emit FIXME comment with original Java source
    }
}
```

#### Translation Rules Applied

The `RustTranslator` applies these transformations when translating INJECT Java code:

**Identifiers** (`translateIdentifier`, lines 82-131):
| Java | Rust | Rule |
|---|---|---|
| `this` | `self` | Keyword mapping |
| `null` | `None` | Keyword mapping |
| `PARSER_CLASS` | `Parser` | Well-known class name |
| `camelCase` methods | `snake_case` | Automatic conversion |
| Package-qualified names | Stripped to simple name | e.g., `org.foo.Bar` → `Bar` |

**Type names** (`translateTypeName`, lines 136-173):
| Java | Rust |
|---|---|
| `double` / `Double` | `f64` |
| `int` / `Integer` | `i32` |
| `boolean` / `Boolean` | `bool` |
| `String` | `String` |
| `void` | `()` |
| `List` / `ArrayList` | `Vec` |

**Method calls** (`translateInvocation`, lines 196-270):
| Java | Rust |
|---|---|
| `size()` / `length()` | `len()` |
| `toString()` | `to_string()` |
| `Double.parseDouble(x)` | (attempted, may need manual fix) |
| `get(i)` | `get(i)` |

**Statements** (`internalTranslateStatement`, lines 521+):
| Java | Rust |
|---|---|
| `throw new X()` | `return Err(ParseError::new(...))` |
| `boolean x = expr;` | `let mut x: bool = expr;` |
| `for (int i=1; ...)` | `let mut i: i32 = 1; while i < ... { ... }` |
| `x instanceof MINUS` | (attempted, may need manual fix) |

#### Graceful Degradation

When a Java construct cannot be mechanically translated (e.g., `instanceof` checks,
complex exception handling, anonymous classes), the translator emits a FIXME comment
block and records a warning:

```rust
// FIXME(congocc): The following Java code requires manual translation to Rust.
// [specific error message]
// Please provide a Rust implementation, or use a
// #if __rust__ / #endif block in the grammar file.
//
// Original Java:
//   public double evaluate() {
//       return Double.parseDouble(toString());
//   }
```

### Stage 4: Template Rendering — Where the Gap Is

This is the critical finding: **the Rust templates do not call
`globals::translateInjectedClass()`**.

In Python and C#, the templates iterate over node class names and emit translated INJECT
content:

**Python** (`src/templates/python/parser.py.ctl`, lines 567-572):
```
#list globals.sortedNodeClassNames as node
  #if !injector::hasInjectedCode(node)
class ${node}(BaseNode): pass
  #else
${globals::translateInjectedClass(node)}
  #endif
#endlist
```

**C#** (`src/templates/csharp/Parser.cs.ctl`, lines 517-531):
```
#list globals.sortedNodeClassNames as node
  #if !injector::hasInjectedCode(node)
    public class ${node} : BaseNode {
        public ${node}(Lexer tokenSource) : base(tokenSource) {}
    }
  #else
${globals::translateInjectedClass(node)}
  #endif
#endlist
```

**Rust** — No equivalent code exists in any Rust template. The `ast.rs.ctl` template
generates `NodeKind` enum variants for each node name but does not query or emit injected
code. The generated Rust AST uses an arena-based design (`Ast` struct holding
`Vec<Node>`) rather than per-node-type classes, so there are no per-type class bodies
where injected methods could be placed.

## What Gets Generated for Arithmetic (Rust)

The generated Rust code in `examples/arithmetic/rust-arith2/` contains:

- **`tokens.rs`** — Token type enum (PLUS, MINUS, TIMES, DIVIDE, etc.)
- **`lexer.rs`** — NFA-based lexer
- **`parser.rs`** — Recursive descent parser with production methods
- **`ast.rs`** — Arena-based AST with `NodeKind::AdditiveExpression`, etc.
- **`visitor.rs`** — Visitor trait
- **`pretty.rs`** — Pretty-printer

None of these files contain any trace of the INJECT content: no `evaluate()` methods,
no `main()` function, no FIXME comments. The INJECT blocks are silently ignored.

## Why: Architectural Mismatch

The Rust AST design is fundamentally different from Java/Python/C#:

| Aspect | Java/Python/C# | Rust |
|---|---|---|
| Node representation | Per-type class/struct (e.g., `class AdditiveExpression extends BaseNode`) | Single `Node` struct with a `NodeKind` enum discriminant |
| Method injection target | The per-type class body | No per-type body exists |
| Child access | `get(i)` returns typed node | `ast.children(id)` returns `NodeId` iterators |
| Type checking | `instanceof` | `matches!(ast.kind(id), NodeKind::X)` |
| Ownership model | Object references | Arena indices (`NodeId`) |

In Java, `INJECT AdditiveExpression : { public double evaluate() {...} }` adds a method
to the `AdditiveExpression` class. In Rust, there is no `AdditiveExpression` struct — all
nodes are `Node` values distinguished by their `kind` field. There is no natural place to
inject per-type methods.

## Possible Approaches for Rust INJECT Support

### Option A: Trait-Based Dispatch

Generate a trait (e.g., `Evaluatable`) with a method per node kind, and implement it on
`Ast` or a wrapper. The INJECT code would be translated into match arms:

```rust
impl Ast {
    pub fn evaluate(&self, id: NodeId) -> f64 {
        match self.kind(id) {
            NodeKind::Number => {
                self.text(id).parse::<f64>().unwrap()
            }
            NodeKind::AdditiveExpression => {
                let children: Vec<NodeId> = self.children(id).collect();
                let mut result = self.evaluate(children[0]);
                let mut i = 1;
                while i < children.len() {
                    let is_minus = matches!(self.kind(children[i]), NodeKind::Token(tid)
                        if self.token(*tid).kind == TokenType::MINUS);
                    let operand = self.evaluate(children[i + 1]);
                    if is_minus { result -= operand; } else { result += operand; }
                    i += 2;
                }
                result
            }
            // ... other node kinds
            _ => panic!("evaluate not supported for {:?}", self.kind(id)),
        }
    }
}
```

### Option B: `#if __rust__` Blocks in Grammar

Grammar authors provide Rust-native code alongside Java code using the preprocessor:

```
INJECT AdditiveExpression :
{
    #if __java__ || __python__ || __csharp__
    public double evaluate() {
        double result = get(0).evaluate();
        for (int i=1; i< size(); i+=2) {
            boolean subtract = get(i) instanceof MINUS;
            double nextOperand = get(i+1).evaluate();
            if (subtract) result -= nextOperand;
            else result += nextOperand;
        }
        return result;
    }
    #endif
}
```

With a separate Rust-specific INJECT or a Rust code file that provides the implementation.

### Option C: Generate Extension Trait Stubs

Generate an empty extension trait for each node kind that has INJECT code, with the
original Java source as comments. Grammar users fill in the Rust implementations:

```rust
/// Extension methods for AdditiveExpression nodes.
///
/// Original Java INJECT code (translate manually):
///   public double evaluate() {
///       double result = get(0).evaluate();
///       ...
///   }
pub trait AdditiveExpressionExt {
    fn evaluate(&self, id: NodeId) -> f64;
}
```

## Comparison of Approaches

### Option A: Trait-Based Dispatch (auto-translate Java to Rust match arms)

**Pros:**
- Grammar authors write INJECT blocks once; all languages work automatically.
- Leverages the existing `RustTranslator.translateInjectedClass()` infrastructure.
- Generated code is immediately functional for simple cases (arithmetic, evaluators).

**Cons:**
- The Java-to-Rust translation is lossy. `get(i) instanceof MINUS` has no clean
  equivalent when nodes are arena indices. The translator would need special-case
  mappings for `get(i)` → `ast.child_at(id, i)`, `instanceof X` →
  `matches!(ast.kind(child), NodeKind::X)`, `toString()` → `ast.text(id)`, etc.
- Translated code operates on `(&Ast, NodeId)` pairs, not `self`. Every method call
  chain requires restructuring, not just renaming. `get(0).evaluate()` becomes
  `self.evaluate(self.child_at(id, 0).unwrap())`.
- Different INJECT blocks define `evaluate()` on different types. The translator would
  need to merge them all into one `match` block — requiring whole-program awareness,
  not just per-class translation.
- Complex INJECT blocks (the Java/C#/Python grammars have hundreds of lines with
  exception handling, mutable state, collections) would produce FIXME-heavy output
  that compiles to nothing useful but clutters the generated files.
- Fragile: any new Java idiom in an INJECT block requires adding a translation rule
  to `RustTranslator`.

### Option B: `#if __rust__` Preprocessor Blocks in Grammar

**Pros:**
- Grammar authors write idiomatic Rust that compiles correctly and uses the arena
  API naturally.
- No translator limitations — any valid Rust code works.
- CongoCC already supports `#if` / `#endif` preprocessor blocks and `-p` symbols.
  The `__rust__` symbol is already auto-set by `Grammar.java` line 68:
  `preprocessorSymbols.put("__" + codeLang + "__","1")`.
- Clean separation: Java INJECT and Rust INJECT are independent, so improvements
  to one don't break the other.

**Cons:**
- Grammar authors must write and maintain two (or more) versions of every INJECT
  block. For Arithmetic2 this is 5 small methods; for the Java grammar it's hundreds
  of declarations.
- Requires Rust knowledge from the grammar author.
- No existing grammars have `#if __rust__` blocks today, so this approach provides
  zero value until each grammar is manually updated.

### Option C: Generate Extension Trait Stubs

**Pros:**
- Makes the gap visible — generated code tells you exactly which methods need manual
  Rust implementations.
- The original Java source is preserved as comments for reference.
- Generated parser compiles and works for parsing; evaluation/custom behavior is
  opt-in.

**Cons:**
- The stubs are dead code until someone fills them in.
- Same maintenance burden as Option B: someone must write Rust implementations
  manually.
- Extension traits on `Ast` are awkward — `impl AdditiveExpressionExt for Ast`
  doesn't compose well when multiple INJECT targets define the same method name
  (like `evaluate()`).
- Trait stubs in generated code that users must edit means re-generating the parser
  overwrites their work.

## Recommendation

**Option B** (`#if __rust__` blocks), enhanced with a concrete usability improvement:
**the Rust templates emit FIXME-commented original Java source for any INJECT block
that has no `__rust__` alternative.**

### Rationale

1. **Mechanical translation doesn't work for Rust.** The arena-based AST is too
   different from Java's object model. Options A and C both pretend the gap can be
   papered over, but the calling conventions (`ast.children(id)` vs `this.get(i)`)
   and ownership model (`NodeId` vs object references) are fundamentally
   incompatible. Translated code that looks plausible but doesn't compile is worse
   than no code.

2. **The grammar preprocessor already exists.** CongoCC has `#if`/`#endif` and `-p`
   symbol support. The `__rust__` symbol is automatically defined when `-lang rust`
   is active (Grammar.java line 68). Grammar authors already accept
   language-specific effort — the Python and C# INJECT translations frequently
   produce FIXME blocks for complex grammars anyway.

3. **The Arithmetic grammars are the easy case, not the typical case.** Five small
   `evaluate()` methods translate cleanly. But the Java, C#, and Python grammars
   have hundreds of INJECT declarations with complex logic, collections, exception
   handling, and mutable state. Any approach that relies on auto-translation will
   fail on the grammars that matter most.

4. **FIXME output preserves the author's intent.** When a Rust template encounters
   an INJECT block with no `#if __rust__` alternative, it emits comments showing
   the original Java and a note that a Rust implementation is needed. This makes
   the gap visible without producing broken code.

### Implementation

The implementation adds three new generated artifacts for Rust parsers:

1. **`inject.rs`** — A module containing translated or FIXME-commented INJECT
   blocks. For each INJECT target class, `RustTranslator.translateInjectedClass()`
   is called; its output (either translated Rust or FIXME comments) is emitted
   inside an `impl Ast { ... }` block. This file is always regenerated.

2. **`FIXME.md`** — A human-readable inventory of all INJECT blocks that need
   manual Rust implementations. Includes the Ast/NodeId API reference, a mapping
   from common Java INJECT patterns to their Rust arena equivalents, and a
   recommended test methodology.

3. **Helper methods on `Ast`** — `child_at(id, index)` and
   `child_is_token(id, index, token_type)` are added to `ast.rs` to simplify
   writing INJECT-style code for the arena-based AST.

## Pipeline Status

| Pipeline Stage | Status |
|---|---|
| Parsing INJECT blocks into `CodeInjection` nodes | Working |
| Indexing via `CodeInjector` | Working |
| `RustTranslator.translateInjectedClass()` | Implemented with graceful degradation |
| `TemplateGlobals.translateInjectedClass()` bridge | Available |
| `__rust__` preprocessor symbol | Auto-set by Grammar.java |
| Rust `inject.rs` template with FIXME output | **Implemented** |
| Rust `FIXME.md` inventory and API guide | **Implemented** |
| Helper methods (`child_at`, `child_is_token`) | **Implemented** |
