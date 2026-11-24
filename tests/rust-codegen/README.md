# Tests of the Rust target

These tests check CongoCC's Rust code generation (`-lang rust`): the
diagnostics it gives for Java code that cannot be translated to Rust, the
`-rust-allow-stubs` flag, and, for a set of small grammars that each exercise
one area of CongoCC, that the generated Rust parser builds the same tree as the
generated Java parser (or reports a syntax error at the same place).

## Running the tests

The tests need a JDK (17 or later), `cargo` and `rustc`.  From the repository
root:

```sh
ant test-rust-codegen                                          # all the tests
ant test-rust-codegen -Drust-codegen.args="-only features/lexing"   # some of them
ant test-rust -Drust.on=true                  # these tests and the Rust example tests
```

`ant test-rust-codegen` builds `congocc.jar` first.  If `rustc` is not on the
`PATH`, the target prints a warning and does nothing.  The tests can also be
run by hand, after `ant jar`:

```sh
javac --release 17 -cp congocc.jar -d build/rust-codegen-tests/classes \
      tests/rust-codegen/org/congocc/codegen/rust/RustCodegenTest.java
java -cp build/rust-codegen-tests/classes:congocc.jar \
     org.congocc.codegen.rust.RustCodegenTest [--only <text>]...
```

`--only` (or `-only`) runs the tests whose name contains the text; it can be
repeated.  The program prints one `PASS` or `FAIL` line per test, each failure
with what was expected, what was found, and the line of `RustCodegenTest.java`
that checked it, and exits with status 1 if a test failed (2 for a usage
error).  The generated parsers, Cargo builds and tree dumps stay in
`build/rust-codegen-tests` for investigation; `ant clean` removes them.

## What is tested

| Tests | What they check |
|---|---|
| `reserved-names/parser`, `reserved-names/lexer` | The names that injected code may not use in Rust (`RustCodeEmitter.RESERVED_PARSER_NAMES` and `RESERVED_LEXER_NAMES`) are exactly the fields and methods that `parser.rs.ctl` and `lexer.rs.ctl` define. |
| `names/snake-case` | How Java names become Rust names: snake case, Rust keywords, and characters that Rust identifiers cannot contain, such as `$`. |
| `names/types-with-articles` | The type names in diagnostics take the right article ("an int", "a boolean"), for every `RustType`. |
| `cli/...` | The generator's exit status and messages, and `-rust-allow-stubs` (ignored for the other languages, also spelled `--rust-allow-stubs`). |
| `diagnostics/...` | The grammars in `diagnostics/`: every error and warning is checked word for word, with its file, line and column, together with the generated code (`compile_error!` or stub) and whether the crate builds. |
| `features/<name>/java-parser` | The Java parser of `features/<name>.ccc` is generated and compiled, parses every `ok-*` input of `features/<name>/` and rejects every `bad-*` input. |
| `features/<name>/rust-generation` | The Rust parser of the same grammar is generated without any diagnostic, and contains the text listed in `features/<name>/rust-code.expect`, if that file exists. |
| `features/harness-build` | One Rust program (`harness/main.rs`) that can parse with every feature's Rust parser builds without warnings. |
| `features/<name>/trees` | For every input, the Rust parser's tree dump is identical to the Java parser's. |

The feature grammars:

| Grammar | Area |
|---|---|
| `trees.ccc` | Tree building annotations: definite nodes, `+n`/`-n`, arity conditions, boolean and int conditions, `#void`, smart node creation. |
| `lookahead.ccc` | `SCAN n`, syntactic and semantic lookahead, `=>||` and `=>|+n`, lookbehind, `ASSERT`/`ENSURE` with messages, `FAIL`, nested lookahead, choices of many tokens. |
| `lexing.ccc` | Token activation and deactivation with re-lexing, `LEXICAL_STATE` in the grammar, lexical actions (`truncate`, `replaceType`, `setBeginOffset`, `setEndOffset`, `switchTo`, dropping a token), `MORE`, `UNPARSED`, contextual keywords, `INJECT LEXER_CLASS`. |
| `parsercode.ccc` | Production parameters of every supported type, arguments with side effects, locals used by later lookahead and annotations, `instanceof` on token classes, `switch`, code that runs during lookahead (`{...}#`). |
| `options.ccc` | `SMART_NODE_CREATION=false` and `TOKENS_ARE_NODES=false`. |
| `voidnodes.ccc` | `NODE_DEFAULT_VOID=true`. |
| `rawcode.ccc` | Raw code blocks for one language (`{J% ... %}`, `{R% ... %}`, `{R% ... %}#`) and `#if __rust__ ... #else ... #endif`. |
| `javasubset.ccc` | The rest of the translated Java subset: `do`/`while`, `continue`, compound assignments, casts, numeric promotion, long and hex literals, shifts and bit operators, the call stack iterators in both directions, `TokenType` and `Token` fields, `static final` constants, `ordinal()` on token types, `null` token types, `EXTRA_TOKENS`, `MORE` with `tokenBeginOffset` and `matchStart`, the 15 `Character` predicates, `matchedToken` null checks, `isUnparsed()`, and Java's left-to-right evaluation where Rust's would differ (a field compared with a call, compound assignment to a field, `charAt` on a field). |

## Tree dumps

Both parsers' trees are written in the format of `examples/rust/TreeDump.java`:

```text
congocc-tree 1
content <number of code points> <FNV-1a 64-bit hash of the UTF-8 text>
<depth> <node name> <begin> <end>          a node, depth first; the root has depth 0
<depth> Token <token type> <begin> <end>   a token
```

Offsets count code points (the Java parser's UTF-16 offsets are converted).
When the parse fails, the node lines are replaced by `error <line>:<column>`,
the location the parser reports, or `crash <message>` for an exception or a
panic, which always fails the test.

## Adding a feature grammar

1. Write `features/<name>.ccc`.  It must set
   `PARSER_PACKAGE=rcgtest.<name>;`, `PARSER_CLASS=FeatureParser;` and
   `LEXER_CLASS=FeatureLexer;`, and its start production must be `Root`.
   Start the file with a comment that says what each construct checks.
   Token names that are also CongoCC keywords (`FAIL`, `SCAN`, `TOKEN`,
   `FLAG`, ...) do not work: the existing grammars use a `KW_` prefix for
   keyword tokens.
2. Put inputs in `features/<name>/`: files named `ok-*` must parse, files
   named `bad-*` must not; there must be at least one of each.  Choose inputs
   whose trees show what the grammar computes, and check the dumps in
   `build/rust-codegen-tests/features/<name>/rust-dumps` once.
3. Optionally, list text that the generated Rust code must contain in
   `features/<name>/rust-code.expect`, one `<file>: <text>` per line (`#`
   starts a comment).

Every Java construct in a feature grammar must translate to Rust: a feature
grammar with a diagnostic fails `rust-generation`.

## Adding a diagnostics test

Put the grammar in `diagnostics/`, add a method to `RustCodegenTest` that
generates it and checks every diagnostic with `checkDiagnostics` (which
requires an exact match of the lists of errors and warnings), and register the
method in `runAll`.  The expected messages contain line and column numbers, so
say at the top of the grammar that the test depends on them.
