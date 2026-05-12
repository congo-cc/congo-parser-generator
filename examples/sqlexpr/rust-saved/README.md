# SQL Expression Parser

A Rust parser for SQL boolean expressions (JMS selector dialect), generated
by [CongoCC](https://github.com/congo-cc/congo-parser-generator) from the
`SqlExprParser.ccc` grammar.

## Purpose

The grammar accepts the SQL fragment that appears inside a JMS message
selector or a SQL `WHERE` clause: boolean combinations of comparison
expressions over typed literals and variables.  It is designed to be used
as a front end for query filters, rule engines, and similar embedded
expression languages.

Supported language features:

- **Logical operators**: `AND`, `OR`, `NOT`
- **Equality**: `=`, `<>`, `!=`
- **Null tests**: `IS NULL`, `IS NOT NULL`
- **Comparison**: `>`, `>=`, `<`, `<=`
- **Pattern matching**: `LIKE`, `NOT LIKE`, with optional `ESCAPE` clause
- **Range**: `BETWEEN … AND …`, `NOT BETWEEN … AND …`
- **Set membership**: `IN (…)`, `NOT IN (…)` (with sign-prefixed numeric or
  string literals)
- **Arithmetic**: `+`, `-`, `*`, `/`, `%` and unary `+`/`-`
- **Literals**: decimal (with optional `L` suffix), hex (`0xFF`), octal
  (`0777`), floating-point (`1.5e-10`), strings (with `''` escapes), and
  the keywords `TRUE`, `FALSE`, `NULL`
- **Keywords are case-insensitive**: `AND`, `and`, `aNd` all parse identically
- **Comments**: line (`-- …`) and block (`/* … */`)

The parser produces an arena-based AST.  Test helpers in
`tests/parser_test_support/` also implement the semantic type-checking that
JMS selectors require (boolean-root enforcement, BETWEEN bound type/order
checks, IN-list type uniformity).

## Supported Syntax

```
-- Boolean operators
x > 5 AND y < 10
a = 1 OR b = 2
NOT active

-- Comparison operators
price >= 100
name <> 'admin'

-- Arithmetic in comparisons
(a + b) * c > 100

-- LIKE with wildcards (% and _) and optional ESCAPE
name LIKE 'John%'
code LIKE 'A_B%' ESCAPE '\'

-- BETWEEN (inclusive, bounds checked at parse time)
age BETWEEN 18 AND 65
temp BETWEEN -10.5 AND 100.0

-- IN lists (type-homogeneous)
status IN ('active', 'pending', 'completed')
code IN (100, 200, 300)

-- NULL checks
value IS NULL
value IS NOT NULL

-- Literals: integers, hex, octal, floats, scientific notation, long suffix
x = 42
flags = 0xFF
perms = 0755
rate = 3.14e-2
big = 1000000L

-- String literals with escaped quotes
name = 'It''s a test'

-- Identifiers with $ and _
$variable > 0
_internal = TRUE

-- Comments
x > 5 -- line comment
x /* block comment */ > 5
```

## File Structure

```
rust-sqlparser/
  Cargo.toml         # Crate configuration (generated)
  lib.rs             # Crate root + public API re-exports (generated)
  ast.rs             # Arena-based AST types (generated)
  parser.rs          # Recursive descent parser (generated)
  lexer.rs           # NFA-based lexer (generated)
  tokens.rs          # TokenType enum + LexicalState (generated)
  error.rs           # ParseError type (generated)
  visitor.rs         # Visitor + AstMapper traits (generated)
  pretty.rs          # Wadler-Lindig pretty-printer (generated)
  inject.rs          # Translated INJECT-block code + FIXME placeholders (generated)
  FIXME.md           # INJECT block reference (generated)
  README.md          # This file (hand-written, restored from ../rust-saved/)
  tests/
    parse_files.rs                    # File-based parse tests (generated)
    lexer_test.rs                     # 62 tests for the public Lexer/Token API (hand-written)
    pretty_parse_test.rs              # 12 pretty-print snapshot tests (hand-written)
    parser_test.rs                    # 164 parser tests (hand-written)
    parser_test2.rs                   # 155 parser tests (hand-written)
    parser_type_checking_tests.rs     # 97 BETWEEN/IN type-check tests (hand-written)
    parser_test_support/
      mod.rs                          # Shared helpers + semantic validators (hand-written)
```

Every file marked **hand-written** is preserved in
`../rust-saved/` and copied back over the generated tree by
`ant rust-gen` so regeneration doesn't clobber them.

## Building

From the `rust-sqlparser/` directory:

```bash
cargo build              # Debug build
cargo build --release    # Optimized build
```

The crate has no required cargo features.  Tests pull the parser via the
crate's public re-exports (`parser::Parser`, `parser::ast::Ast`,
`parser::tokens::TokenType`, etc.); examples are documented in the
`pretty_parse_test.rs` and `parser_test.rs` files.

## Running the Test Suite

```bash
cargo test                              # All tests, summary output only
cargo test -- --nocapture               # All tests, with AST dumps and SQL inputs
cargo test --test parser_test           # Run one test file
cargo test test_between_negative_numbers -- --nocapture  # Run one test
```

The five hand-written test files run **490 tests** total:

| Test file | # tests | Focus |
|---|---|---|
| `lexer_test.rs` | 62 | Public Lexer/Token/TokenType/LexicalState API: positive + negative + edge cases |
| `pretty_parse_test.rs` | 12 | Wadler-Lindig pretty-printer snapshot regression |
| `parser_test.rs` | 164 | Parser AST shape: literals, operators, precedence, parens, complex expressions, parse errors |
| `parser_test2.rs` | 155 | Broader parser feature smoke tests (boolean/relational/LIKE/BETWEEN/IN, comments, whitespace, real-world expressions) |
| `parser_type_checking_tests.rs` | 97 | BETWEEN-bound and IN-element type checking (literal-only, same-type, ordering, NULL/Boolean rejection, error-message quality) |

`tests/parse_files.rs` is regenerated by `congocc.jar`; it requires a
`test-data/` directory next to `Cargo.toml`.  None is provided for this
example.

## Regenerating the Parser

This crate is rebuilt from the grammar by the example's
[`build.xml`](../build.xml).  From the repo root:

```bash
ant -Drust.enabled=true -f examples/sqlexpr/build.xml rust-gen      # regenerate only
ant -Drust.enabled=true -f examples/sqlexpr/build.xml test-rust     # regenerate + cargo test
```

or, from the example directory:

```bash
cd examples/sqlexpr
ant -Drust.enabled=true rust-gen
ant -Drust.enabled=true test-rust
```

Either form invokes `java -jar ../../congocc.jar -n -lang rust -d
rust-sqlparser SqlExprParser.ccc` and then copies everything in
`../rust-saved/` over `rust-sqlparser/` with `overwrite="true"`.  The
hand-written files (README, tests, support module) survive regeneration
because of that restore step.

If you ever need to do the restore by hand (e.g. after running
`congocc.jar` directly without ant):

```bash
cd examples/sqlexpr
cp -r rust-saved/. rust-sqlparser/
cd rust-sqlparser && cargo test
```

## Editing Workflow

Whenever you change a hand-written file in `rust-sqlparser/`, **also copy
the updated file back into `../rust-saved/`**.  Otherwise the next
`ant rust-gen` will restore the older version from `rust-saved/` and your
edits will appear to vanish.

## Library API

The crate re-exports all public types from the crate root:

```rust
use parser::ast::{Ast, NodeId, NodeKind};
use parser::parser::Parser;
use parser::pretty::{DefaultPrettyPrinter, PrettyPrinter};
use parser::tokens::TokenType;
```

### Parsing

```rust
let ast = Parser::parse("name = 'foo' AND age > 18", Some("input.sql"))
    .expect("parse failed");
let root = ast.root().expect("ast must have a root");
println!("Root kind: {:?}", ast.kind(root));
println!("Node count: {}", ast.node_count());
```

### Pretty-printing

```rust
let output = DefaultPrettyPrinter.pretty_print(&ast);
println!("{}", output);
```

### Error Handling

```rust
match Parser::parse("a =", Some("test")) {
    Ok(ast) => println!("Parsed: {}", ast.node_count()),
    Err(e) => eprintln!("Parse error: {}", e),
}
```

Note: the parser itself only enforces *syntax*.  Semantic constraints
(boolean-root, BETWEEN bound types/order, IN-element types) are
implemented in `tests/parser_test_support/mod.rs` because the source
grammar's Java INJECT block expressing those rules does not currently
translate to Rust.  Callers who need the same semantic checks at runtime
can copy the validators from that module.

## Grammar Summary

```
JmsSelector             : orExpression <EOF>
orExpression            : andExpression ( <OR> andExpression )*
andExpression           : equalityExpression ( <AND> equalityExpression )*
equalityExpression      : comparisonExpression
                          ( ( "=" | "<>" | "!=" ) comparisonExpression
                          | <IS> <NULL>
                          | <IS> <NOT> <NULL>
                          )*
comparisonExpression    : addExpression
                          ( ( ">" | ">=" | "<" | "<=" ) addExpression
                          | <LIKE> stringLitteral [ <ESCAPE> stringLitteral ]
                          | <NOT> <LIKE> stringLitteral [ <ESCAPE> stringLitteral ]
                          | <BETWEEN> addExpression <AND> addExpression
                          | <NOT> <BETWEEN> addExpression <AND> addExpression
                          | <IN> "(" inElement ( "," inElement )* ")"
                          | <NOT> <IN> "(" inElement ( "," inElement )* ")"
                          )*
addExpression           : multExpr ( ( "+" | "-" ) multExpr )*
multExpr                : unaryExpr ( ( "*" | "/" | "%" ) unaryExpr )*
unaryExpr               : "+" unaryExpr
                        | "-" unaryExpr
                        | <NOT> unaryExpr
                        | primaryExpr
primaryExpr             : literal | variable | "(" orExpression ")"
literal                 : stringLitteral
                        | <DECIMAL_LITERAL> | <HEX_LITERAL> | <OCTAL_LITERAL>
                        | <FLOATING_POINT_LITERAL>
                        | <TRUE> | <FALSE> | <NULL>
inElement               : literal | "+" literal | "-" literal
stringLitteral          : <STRING_LITERAL>
variable                : <ID>
```

Tokens: see `tokens.rs` for the complete `TokenType` enum.  Whitespace
(space, tab, newline, CR, form-feed) is preserved in the token stream as
`UNPARSED` tokens but ignored by the parser; line and block comments are
`SKIP`ped entirely.

## Acknowledgments

Anthropic's Claude Opus 4.7 was used to generate most of the code and
documentation for the Rust support in this example.
