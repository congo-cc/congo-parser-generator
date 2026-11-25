# Rust target: a working parser for Rust.ccc

## Summary

CongoCC's Rust target (`-lang rust`) now generates a Rust parser from
`examples/rust/Rust.ccc` (with `RustLexer.ccc`) that:

* builds exactly the same tree as the generated Java parser for all 106 files
  of `examples/rust/testfiles`.  For the 12 files of the new
  `examples/rust/invalid-testfiles`, it gives the same result as the Java
  parser: a syntax error at the same line and column, or, for the one file
  that both parsers accept, the same tree;
* is about twice as fast as the Java parser (see [Performance](#performance));
* is generated with every piece of the grammar's Java code translated, so
  nothing is stubbed out, and builds without warnings.

The Java, Python and C# targets are not changed.  With the same grammars, the
new generator writes exactly the same Java, Python and C# files as before for
all 135 grammar/language pairs in the repository, and every test suite gives
the same result as before (see [Verification](#verification)).

Before this change, generating Rust.ccc for Rust reported success, but the
crate did not compile.  With the compile errors patched by hand, it parsed 2
of the 106 test files, and for some inputs it reported success with a wrong
tree.

## Approach

This implements the "hybrid" approach of the Phase 1 analysis:

1. **Fail loud.**  Java code that the Rust target cannot translate is never
   silently replaced.  If it can change how the input is parsed, it is an
   error: the generator exits with status 1, and the generated code contains
   a `compile_error!` with the message.  Otherwise it is a warning, and the
   Java code is kept as a comment.  The new flag `-rust-allow-stubs` turns the
   errors into warnings and replaces the code by documented stubs, for
   bringing up a grammar.
2. **Java parity in the generated runtime.**  The parser, lexer and tree
   builder templates are ports of the Java templates, and they make the same
   decisions:
   * lookahead and scan limits;
   * the token-type activation and lexical-state handling, with lazy
     re-lexing;
   * the tree-building rules;
   * lexical actions and `INJECT LEXER_CLASS`.
3. **A typed translator for the Java that grammars use.**  It covers what
   Rust.ccc and RustLexer.ccc need, plus a small general subset (listed in
   the generated `FIXME.md`).  Feature grammars run every construct of that
   subset in both parsers, and code just outside it is checked to be
   reported.  Raw Rust code (`{R% ... %}`) is the escape hatch for anything
   outside the subset.
4. **Tests that compare with the Java parser.**  Tree dumps are compared for
   Rust.ccc and for eight small feature grammars.  The diagnostics are
   checked word for word.  A golden-output comparison guards the other
   targets.

Out of scope, and not changed:
* the handling of a relative `-d` (see [Pre-existing issues](#pre-existing-issues-found-not-changed));
* the grammars Rust.ccc and RustLexer.ccc;
* the shared `Translator` base class.

## Background: what was wrong

The Phase 1 analysis tried the Rust parser on short Rust snippets.  The Java
parser accepts every one of them:

| Snippet | Rust parser before | Cause |
|---|---|---|
| `fn main() {}` | fails | the loop guard of `BlockExpression` was replaced by `true` |
| `println!("hi")`, `#[derive(Debug)]` | fails | the guard of `DelimTokenTree` was replaced by `true`; it ran to the end of the input |
| `if a == b { c }` | fails | `isInConditionButNotParenthesized` was replaced by `true` |
| `1.max(2)` | fails: `1.` is lexed as a float | the lexical action that truncates a float was dropped |
| `0..10` | "OK", but the tokens are `0.` (a float), `.` and `10`, parsed as tuple indexing | the same |
| `r#"hi"#;` | fails | the lexer stayed in `IN_HASH_RAW_STRING` |
| `t.0.1` | fails | `DEACTIVATE_TOKENS` was lost when re-tokenizing |
| `/* c */ const X: i32 = 1;` | "OK", but the tree is one EOF token | the lexer stayed in `IN_MULTILINE_COMMENT` |
| `a.b(c)` | "OK", but `MethodCall` holds only `CallParams` | definite node counts were ignored |

The causes were systemic:

* **A.**  A substring deny-list in `TemplateGlobals` silently replaced
  predicates by `true` and commented out actions.
* **B.**  The generated runtime had no counterpart of the Java parser's API:
  `getToken(n)`, `getTokenType(n)`, `checkNextTokenType`,
  `stackIteratorBackward`, `lastConsumedToken`.
* **C.**  Lexical actions and `INJECT LEXER_CLASS` were dropped.  The only
  exception was a hack in the template for Lua.
* **D.**  Definite and `+n` node annotations became `true`.  Smart node
  creation was disabled for every grammar, because `" > "` was compared with
  `startsWith(">")`.
* **E.**  Re-tokenizing re-lexed the whole rest of the input twice per token
  activation, and lost deactivations.
* **F.**  None of this was reported.

The other grammars were affected the same way.  Before this change, the Rust
output silently stubbed predicates in Java.ccc (35), CSharp.ccc (37),
Python.ccc (3) and Rust.ccc (20), and left 106, 85, 5 and 30 pieces of Java
code untranslated in them.

## Changes

### Generator

| File | Change |
|---|---|
| `src/java/org/congocc/codegen/rust/RustCodeEmitter.java` (new) | Translates all the Java code of a grammar for the Rust target, as called by the Rust templates.  This covers code actions, semantic lookaheads, assertions, tree-building conditions, production parameters and arguments, lexical actions, and the members injected into the parser and lexer classes.  It applies the fail-loud policy, writes the diagnostics, and builds the translation report for `FIXME.md`.  It knows the grammar: token types, token classes, lexical states, injected members and production locals. |
| `src/java/org/congocc/codegen/rust/RustJavaTranslator.java` (new) | The typed Java-to-Rust translator.  It works on the Java syntax tree and tracks a type for every expression, so it can apply Java's numeric promotion and its casts to `int` and `long`, and translate `instanceof` on token classes (a test of the token's type against the class's token types), `switch`, chained and compound assignments, `++`/`--` and `do`/`while`.  It keeps Java's left-to-right evaluation where Rust's differs: arguments with side effects are evaluated into temporaries in order, and a field is read before a later operand that may change it (a comparison of a `TokenType` or `Token` field, a compound assignment to a field, `charAt` on a `Token` field).  Code it cannot translate exactly raises `RustTranslationException`; it never guesses. |
| `src/java/org/congocc/codegen/rust/RustType.java` (new) | The Java types of the supported subset, their Rust counterparts, the types that fields can have, and their names in diagnostics. |
| `src/java/org/congocc/codegen/rust/RustTranslationException.java` (new) | Carries the reason a construct cannot be translated and the syntax node it is about, so that the diagnostic can name the grammar location. |
| `src/java/org/congocc/codegen/rust/RustTranslator.java` | Removed the generation of the parser's injected fields (`getParserFieldStructFields` and the field-name mapping), which `RustCodeEmitter` now does with types. |
| `src/java/org/congocc/codegen/TemplateGlobals.java` | Removed the Rust deny-list heuristics (`translateExpressionSafe`, `looksLikeUntranslatableJava`, `looksLikeBadRustTranslation`, `translateCodeBlockForRust`, `translateParserClassInjection` and the related helpers).  Added the `rust*` methods that the Rust templates call, which delegate to `RustCodeEmitter`.  The code used by the other targets is unchanged: in `translateCodeBlock`, removing the Rust branch only un-nests the Java/Python/C# path. |
| `src/java/org/congocc/app/AppSettings.java` | New setting `rustAllowStubs`. |
| `src/java/org/congocc/app/Main.java` | Changes for the new flag and the Rust target, listed below the table. |

The changes in `Main.java`:
* New flag `-rust-allow-stubs` (also `--rust-allow-stubs`), with help text.
  Used with another language, it is ignored with a message.
* A new overload of `mainProgram` takes the flag; the existing signatures are
  kept and delegate to it.
* For the Rust target only, when code generation reported errors, the
  generator prints "Parser generated with N error(s); the generated code is
  incomplete (see below)." instead of the success message.  The other targets
  print exactly what they printed before.

**Console messages.**  An error or warning names the grammar location, the
construct, the reason and the remedies, for example:

```text
Parser generated with 1 error(s); the generated code is incomplete (see below).
Error: examples/java/JavaLexer.ccc:15:20:Cannot translate the lexical action '{matchedToken.setType(TokenType.EOF);}' to Rust: the Token method 'setType' is not supported by the Rust target. The generated Rust code contains a compile_error! here. Possible remedies: rewrite the code using the subset of Java that the Rust target translates (it is described in the FIXME.md file of the generated crate); or provide Rust code for this site in a raw code block ({R% ... %}), inside '#if __rust__ ... #endif' if the grammar also targets other languages; or generate with -rust-allow-stubs to replace it with a stub.
```

The generator classifies untranslatable code as follows:

| Untranslatable code | Without `-rust-allow-stubs` | With it |
|---|---|---|
| semantic lookahead, assertion, tree-building condition, lexical action, production argument | error; `compile_error!` in the code | warning; a documented stub (`true`, or the code is skipped) |
| code action that runs in lookahead, assigns a variable read by the above, calls a parsing API that changes state, or transfers control | error | warning; the code is skipped |
| other code action (for example one that decorates nodes) | warning; the Java code is kept as a comment | the same |
| an injected member that cannot be translated | an error if parse-relevant code uses it, otherwise a warning; the member is not generated | warning |
| the message or location of an `ASSERT`/`ENSURE`, or the message of a `FAIL` | warning; the Java source of the message is used as the message text, and a failure whose location cannot be translated is reported at the next token | the same |
| `TRY`/`CATCH`, `ATTEMPT`/`RECOVER` | error | warning; `FIXME` comment |
| features not implemented by the Rust target (below) | warning | warning |

Names follow fixed rules:
* Java names become snake_case Rust names.
* Rust keywords are escaped (`type` becomes `r#type`; `self`/`Self` become
  `self_`).
* Characters that a Rust identifier cannot contain become `_` (`a$b` becomes
  `a_b`, and `$` becomes `__`).
* Injected members whose Rust names collide with each other are all
  rejected, and so are members whose Rust names are generated names of the
  `Parser` or `Lexer` (the reserved names are checked against the templates
  by a test).
* A `Token` passed to an injected method is a `&mut Token` in Rust, so the
  argument must be a variable; the error says how to rewrite the call.

### Templates

| File | Change |
|---|---|
| `src/templates/rust/parser.rs.ctl` | Rewritten as a port of `ParserProductions.java.ctl`, `LookaheadRoutines.java.ctl`, the lexical-state handling of `CommonUtils.java.ctl`, and the runtime in `Parser.java.ctl`, `ErrorHandling.java.ctl` and `TreeBuildingCode.java.ctl`.  The details are listed below the table. |
| `src/templates/rust/lexer.rs.ctl` | Rewritten as a port of `Lexer.java.ctl`, `NfaCode.java.ctl` and `TokenSource.java.ctl`.  The details are listed below the table. |
| `src/templates/rust/tokens.rs.ctl` | `TokenTypeSet`, a `Copy` bitset that replaces `HashSet<TokenType>`: the lexer checks the set once per NFA match, and saving and restoring the active set costs a few word copies.  Also: token ordinals that match Java's (`ordinal`, `from_ordinal`, `ALL`), `is_ignore_case`, and the set of contextual token types. |
| `src/templates/rust/ast.rs.ctl` | Deferred node scopes: a node is allocated only when its scope is closed and the node is kept, so the nodes that the tree-building rules leave out are never stored, and `Ast::node_count` counts only the tree's nodes.  As a consequence, node ids are in post-order.  New `Ast::set_root`. |
| `src/templates/rust/lib.rs.ctl` | Allows the lints that generated code triggers (`unused_assignments`, `unused_labels`, `unused_braces`), and the lints about the Greek sigma (Σ) used in generated names, which cannot collide with grammar names. |
| `src/templates/rust/inject.rs.ctl` | Holds only the code injected into node classes.  Code injected into the parser and lexer classes is now translated into the `Parser` and `Lexer` structs. |
| `src/templates/rust/FIXME.md.ctl` | Adds the translation report, the supported Java subset, and how to write raw Rust code: what `self` is, the names of fields, parameters and locals, the parser and lexer API, and `{R% ... %}#`. |

The port in `parser.rs.ctl` covers:
* the same lookahead routines and scan limits, `SCAN n`, `=>||`, `=>|+n`,
  nested lookahead, and lookbehind (`\Block`, `\...\X`, `~\...\X`, `/Root/...`);
* `ASSERT`/`ENSURE` with messages, and `FAIL`;
* `ACTIVATE_TOKENS`/`DEACTIVATE_TOKENS` and `LEXICAL_STATE`;
* the call stacks, `isInProduction`, `getToken(n)`, `getTokenType(n)` and
  `checkNextTokenType`;
* every tree-building annotation: definite nodes, `+n`/`-n`, arity
  conditions, boolean and int conditions, `#void`, smart node creation,
  `NODE_DEFAULT_VOID` and `TOKENS_ARE_NODES`.

Tokens are lexed on demand.  When the set of active token types changes, only
the tokens already lexed after the current position are dropped and lexed
again.  As in Java, a token lexed ahead whose type is no longer active is
thrown away together with the tokens after it.

The port in `lexer.rs.ctl` covers:
* the same longest-match and lowest-ordinal rule;
* `SKIP`, `MORE` and `UNPARSED`;
* lexical actions, which run for every matched type, including `MORE` and
  `SKIP`;
* `INJECT LEXER_CLASS` fields and methods;
* the same input munging (tabs, line endings, `ENSURE_FINAL_EOL`, Java
  unicode escapes);
* contextual keywords and `DEACTIVATE_TOKENS` in the settings.

The side tables take 8 bytes per character: a `char` and a `u32` byte
offset.

### Examples and build files

| File | Change |
|---|---|
| `examples/rust/build.xml` | New Rust targets.  `rust-gen` generates the crate into `rust-rustparser/`, without `-rust-allow-stubs`, so any untranslated code fails the build.  `compile-rust` builds the crate and the harness.  `test-rust` runs the harness's unit tests, then compares the Java and Rust trees for `testfiles/` and `invalid-testfiles/`.  `bench` benchmarks both parsers.  The new `test-all` runs both `test` and `test-rust`.  `clean` also removes the Rust crate, the harness's build directory, the tree dumps and the compiled `TreeDump` classes.  The other existing targets are unchanged. |
| `examples/rust/TreeDump.java` (new) | Writes the Java parser's tree dumps (format `congocc-tree 1`, below) and benchmarks the Java parser. |
| `examples/rust/rust-harness/` (new crate) | `dump`, `compare` and `bench` for the Rust parser, with unit tests of its own helpers. |
| `examples/rust/invalid-testfiles/` (new, 12 files) | Inputs that are not valid Rust.  Each file starts with a comment that says what is wrong.  11 of them must fail at the same line and column in both parsers.  `invalid_extra_hash_after_raw_string.rs` is accepted by both, because of the lexer issue described under [Pre-existing issues](#pre-existing-issues-found-not-changed). |
| `examples/{java,csharp,python}/build.xml` | The `rust-gen` targets pass `-rust-allow-stubs`, with a comment saying why: these grammars use Java types and APIs that the Rust target does not translate. |
| `examples/sqlexpr/SqlExprParser.ccc` | `SMART_NODE_CREATION=false`, for the Rust target only (`#if __rust__`).  The hand-written Rust tests of this example expect a node for every production.  The old Rust target always behaved as if this were set; it now honors the setting, as the Java target does.  The alternative was to rewrite 85 of the example's 164 parser tests to the smart-node trees.  This example is Rust-only (its build has only Rust targets), and its Java, Python and C# output changes only in grammar line numbers. |
| `examples/arithmetic/rust-saved/arith2/tests/{pretty,synthetic_struct2,astmapper_struct}_test.rs` | Updated to the trees that the Java parser builds, with smart node creation: for example, the operand `2` of `2+3*4` is now a token directly under `AdditiveExpression`.  The tests now check exact output where they used to check loose properties. |
| `build.xml` | New targets, listed below the table. |
| `.gitignore` | Ignores the generated `examples/rust/org/` and `rust-rustparser/`, the harness's `target/` and `Cargo.lock`, and `examples/rust/tree-dumps/`. |

The new targets in `build.xml`:
* `test-rust-codegen` runs the Rust-target tests in `tests/rust-codegen`.
* The root `test-rust` target now also calls `test-rust-codegen`.  It still
  runs only with `-Drust.on=true`, so `ant test` is unchanged.
* `snapshot-generated-output`, `compare-generated-output` and
  `test-generated-output-tool` run the golden-output tool.

### Tests and tools

| Path | Content |
|---|---|
| `tests/rust-codegen/` (new) | `RustCodegenTest` and its grammars.  Diagnostics grammars are checked word for word, with file, line and column, including code just outside the translated subset; eight feature grammars compare Java and Rust trees, one of them (`javasubset.ccc`) for the parts of the subset that the others do not use.  See `tests/rust-codegen/README.md`. |
| `tests/generated-output/` (new) | `GeneratedOutputSnapshot`, the golden-output tool, with a `self-test` command.  See `tests/generated-output/README.md`. |

## Standalone Rust Parser for the Rust Language

As mentioned above, the `rust-gen` build target of `examples/rust/build.xml` generates the `rust-rustparser` crate in its own directory.  This source code directory has its own Cargo.toml for standalone use or for incorporatation into other projects.  

For quick testing, run the parser in place.  Issue the following command in the `rust-rustparser` directory to compile the parser and the `tests/parse_files.rs` program, and to run the test program:

```
cargo test --release -- --nocapture
```

The parse_files program looks for a `test-data` subdirectory in `rust-rustparser` and attempts to recursively parse all **.rs** files under that subdirectory.  The `test-data` directory is not supplied, so either a new directory or a symbolic link to an existing directory needs to be created with that name.

## How the generated Rust parser differs from the Java parser

The Rust target behaves like the Java target except for the points below.
None of them changes the trees of the Rust test files.

* **Offsets**, and the indexes of `charAt` in translated code, count code
  points; Java counts UTF-16 units.  They differ only after a character
  outside the Basic Multilingual Plane.  The tree dumps convert the Java
  offsets.
* **An unmatchable character** produces one `INVALID` token per code point.
  Java merges a run of them into one token.
* **`line_column`** counts a tab as one column; Java uses tab stops.  This
  matters only with `PRESERVE_TABS`.
* **Re-lexing.**  The Java parser starts from a dummy token that has no
  place in the token cache.  This gives two differences at the start of the
  input:
  * Java lexes the first token again each time the parser asks for it from
    the start (for example in each lookahead from the start); Rust lexes it
    once and keeps it while its type is active.
  * When a `LEXICAL_STATE` construct ends before any token was consumed,
    Java switches back to the previous state but keeps the tokens that were
    lexed ahead in the construct's state; Rust lexes them again in the
    previous state.

  And one anywhere in the input: when a token that was lexed ahead turns out
  to have an inactive type, Java lexes again from the end of the token
  before it, which may be an unparsed token such as a comment; Rust lexes
  again from the end of the last regular token, so the unparsed tokens in
  between are lexed again too.

  The results differ only for grammars whose lexical state or active token
  types differ between those points.  For Rust.ccc they do not.
* **`getToken(n)`** returns `Token::dummy()` where Java returns `null`.
* **After a syntax error in a production that builds a node**, Java's
  `clearNodeScope()` empties the node scope but leaves it open; Rust also
  closes it.  This could matter only to code that goes on parsing after a
  syntax error (`ATTEMPT`/`RECOVER`, `FAULT_TOLERANT`), which the Rust target
  does not implement.
* **Integer arithmetic in translated code** uses Rust's operators.  An
  overflow of `+`, `-`, `*` or unary `-` panics in a debug build and wraps
  around in a release build; `MIN_VALUE / -1` and `MIN_VALUE % -1` panic in
  both.  Java always wraps around (the last two give `MIN_VALUE` and 0).
  Shifts mask the distance as Java does.

## Not implemented in the Rust target

Each of these is reported when a grammar uses it.  The generated parser
behaves as described in the table:

| Feature | Report | Generated parser |
|---|---|---|
| `FAULT_TOLERANT` | warning | stops at the first syntax error |
| `TRY`/`CATCH` and `ATTEMPT`/`RECOVER` blocks | error (warning with `-rust-allow-stubs`) | with the flag, the nested expansion is parsed without the `CATCH`/`FINALLY` code or the recovery, after a `FIXME` comment |
| Repetition cardinality (`&m:n&`) | warning | accepts any number of repetitions |
| `UNPARSED_TOKENS_ARE_NODES` | warning | no unparsed tokens in the tree |
| Synthetic nodes (`JTB_PARSE_TREE`, `SYNTHETIC_NODES_ENABLED`) | warning | only the nodes named in the grammar |
| Hook methods (parser and lexer token hooks, reset token hooks, open and close node scope hooks) | warning | not called; the Lua-style long-bracket lexer hook is emulated |
| Assigning a parsed element (`x = NonTerminal`, `t = <TOKEN>`, `@property := ...`), except a token assigned to a local variable of type `Token` | error if parse-relevant code reads the variable, otherwise warning | the assignment is not made |
| Code injected into node classes | listed in `FIXME.md` | kept as comments in `inject.rs` |

For a grammar that has other targets too, a feature used only inside
`#if !__rust__` is not reported (tested).

## Pre-existing issues found (not changed)

* **`Errors.getWarningCount()` always returns 0**, because `warningCount` is
  never incremented.  So for every target, the generator prints "Parser
  generated successfully." even when it printed warnings.
* **`Expansion.hasGlobalSemanticActions()`**
  (`src/java/org/congocc/core/Expansion.java:247`, with its own FIXME) counts
  only code blocks.  As a result, raw code that also runs in lookahead
  (`{J% ... %}#`) in an expansion that is a single token is left out of the
  lookahead, for every target.  The Rust target does the same, and
  `tests/rust-codegen/features/rawcode.ccc` documents it.
* **RustLexer.ccc, the action of `RAW_STRING_LITERAL_HASH`**, computes
  `setEndOffset(index2 - (numTrailingHashes - numStartingHashes))`.  `index2`
  is one past the first character that is not `#`, so the token ends one
  character too late, and `r#"a"##` lexes as a single token.  Both parsers
  accept `examples/rust/invalid-testfiles/invalid_extra_hash_after_raw_string.rs`
  for this reason.
* **The numbers of the NFA states in the generated lexers depend on identity
  hash codes**: the NFA state sets in `org.congocc.core.nfa` are hash sets of
  objects without `hashCode()`.  The same jar always gives the same output,
  but any change to the generator can renumber the states of every lexer
  without changing what the lexer does.  The golden-output tool therefore
  runs the generator with constant identity hash codes (see
  `tests/generated-output/README.md`).
* **A relative `-d`** is resolved against the grammar's directory by
  `AppSettings`, but `Main` creates it relative to the working directory.
  Use an absolute `-d`.
* **An `INJECT` followed by a raw code block** (the `RawCodeInjection`
  production of `src/grammars/CongoCC.ccc`) is parsed, but no code action
  registers it with the grammar, so every target ignores it.
* **`ant test-rust-formatter`** fails: `tests/rust-formatter/RustFormatterTest.java:30`
  calls `RustFormatter.format(Crate)`, which does not exist.
* **Generation fails for some grammar/language pairs, with or without this
  change.**  Examples are Python and C# for `CSharpLexer.ccc` (an exception in
  `translateLexerImports`), grammar fragments, and the negative cardinality
  grammars.

## Verification

### No change for Java, Python and C#

`tests/generated-output/GeneratedOutputSnapshot.java` generates every grammar
of the repository (45 grammars) for every language and compares the output
byte for byte, including the generator's messages and exit status.  It runs
the generator with constant identity hash codes, so that the comparison
measures what the generator does (see the issue above).

1. **The new generator on the baseline's grammars** against **the baseline
   generator on the same grammars**: 135 of 135 Java, Python and C# outputs
   are identical.  Five of them are generator crashes that also happen
   before this change; their logs differ only in the line numbers of
   CongoCC's own stack trace frames (`Main.java:303` became `Main.java:336`),
   because those files were edited.
2. **The new generator and grammars** against the baseline: the only
   differences are the Java and Python output of `SqlExprParser.ccc`, whose
   grammar gained 10 lines.  A script shifted the grammar line numbers in the
   baseline output by 10; after that, the output is identical (733
   locations).  The C# generator fails for this grammar both before and after
   the change.

With the JVM's default identity hash codes, comparison 2 shows 66
different outputs.  In 65 of them, the lexer's NFA states are numbered and
emitted in a different order: once the digits are masked, each lexer file
has exactly the same lines as before, in another order.  The remaining
differences are SqlExpr's grammar line shift (its parser, and the
generator's error messages for Python and C#).  The test suites below run
with the default identity hash codes.

### Test suites

Every suite ran in a copy of the repository, before the change (the
baseline) and after it, each Ant target in its own invocation: the suites of
the top-level `test` target, then those that `ant test` does not run.

| Suite | Before | After |
|---|---|---|
| `ant jar` | pass | pass |
| `examples/json`: `test-all` | pass | pass |
| `examples/cics`: `test-all`, `test-rust` | pass | pass |
| `examples/preprocessor`: `test` | pass | pass |
| `examples/lua`: `test-all` | pass | pass |
| `examples/csharp`: `test-all` | pass | pass |
| `examples/java`: `test-all` | pass | pass |
| `examples/python`: `test-all` | pass | pass |
| `examples/arithmetic`: `test`, `test-rust` | pass | pass |
| `examples/sqlexpr`: `test-rust` | pass | pass |
| `ant -Drust.on=true test-rust` (after the change, it also runs Rust.ccc's comparison and `test-rust-codegen`) | pass | pass |
| `ant -Drust.on=true test-rust-formatter` | fails (see [Pre-existing issues](#pre-existing-issues-found-not-changed)) | fails, for the same reason |
| `examples/rust`: `test` | pass | pass |
| `ant test-cardinality-oracles-java` | pass | pass |
| `ant full-test` (rebuild with the new jar, test again) | pass | pass |

The suites ran before the last edits to the Rust templates, which change
only the documentation examples and the `FIXME.md` of the generated crates.
After those edits, the generated-output comparison above, the Rust tests
below and the `test-rust` targets of `examples/rust` and `examples/json` were
run again with the final generator.

### The Rust parser of Rust.ccc

`ant test-rust` in `examples/rust`, with the final generator:

* the generator reports no error and no warning, and the crate and the
  harness build without warnings;
* the harness's own tests pass (8 unit tests and 2 doctests);
* 118 of 118 files have identical tree dumps: the 106 files of `testfiles`,
  and the 12 files of `invalid-testfiles` (11 syntax errors at the same line
  and column, and the one file that both parsers accept).

### The Rust target's tests

`ant test-rust-codegen` passes all 44 tests.  They include:

* every error and warning for the 10 grammars in `tests/rust-codegen/diagnostics`;
* the trees of the eight feature grammars for 108 inputs: 45 that must parse
  and 63 that must fail at the same place in both parsers.

The documentation examples of the generated crates run as doctests in the
examples' `test-rust` targets (15 per crate).  Six more are marked `ignore`,
because they use the names of one particular grammar or items private to
the crate.

### Performance

Both parsers parse the 106 files of `examples/rust/testfiles` (3,492,011
bytes) from memory on one thread and build their trees, 20 rounds after 20
warm-up rounds: `java TreeDump -bench 20 testfiles` and
`rust-harness/target/release/rust-harness bench 20 testfiles` (`ant bench`
runs 10).  Two runs of each:

| Parser | Median round | Fastest round | Throughput at the median |
|---|---|---|---|
| Java | 395.6 ms, 407.9 ms | 390.1 ms, 394.9 ms | 8.8 MB/s, 8.6 MB/s |
| Rust | 185.2 ms, 186.3 ms | 182.3 ms, 180.4 ms | 18.9 MB/s, 18.7 MB/s |

The Rust parser takes 46% to 47% of the Java parser's time: it is 2.1 to
2.2 times as fast.

Machine: AMD Ryzen 9 9950X (16 cores, 32 threads), 186 GB of memory, Linux;
OpenJDK 25.0.2; rustc 1.99.1, with the harness's release profile
(`lto = true`, `codegen-units = 1`).

## How to run

The Rust tests need `cargo` on the `PATH`; the C# suites need `dotnet`.

```sh
ant jar                       # build the generator

ant test-rust-codegen         # the Rust target's own tests (tests/rust-codegen)
ant -Drust.on=true test-rust  # the same, plus the Rust tests of the examples,
                              # Rust.ccc's included (see below)

cd examples/rust              # Rust.ccc (this regenerates examples/rust/org/)
ant test-rust                 # generate and build the Rust parser, then compare
                              # its trees with the Java parser's
ant test-all                  # the Java parser's tests and test-rust
ant bench                     # benchmark both parsers (10 rounds)
cd ../..

# Golden output of the Java, Python and C# targets: one snapshot with the
# generator before the change, one with the generator after it, then compare.
ant snapshot-generated-output -Dgenerated-output.dir=/tmp/before
ant snapshot-generated-output -Dgenerated-output.dir=/tmp/after
ant compare-generated-output -Dgenerated-output.baseline=/tmp/before \
                             -Dgenerated-output.candidate=/tmp/after
```

## Notes for the reviewer

* Nothing is committed or staged.
* Not part of this change, in the working tree:
  * `docs/claude_prompts.md` holds the task description; it was edited by
    hand, not by this change.
  * `examples/rust/rustparser/` is left over from the Phase 1 analysis: the
    crate of the old Rust target, with its compile errors patched by hand.
    Delete it before committing.
  * `examples/rust/org/` is the Java parser that the build of
    `examples/rust` generates; `.gitignore` now ignores it.
* The golden-output tool runs the generator with the experimental JVM
  options `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`; they affect only
  the generator runs of the tool.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
