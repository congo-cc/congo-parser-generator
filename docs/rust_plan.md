# CongoCC Rust Code Generation — Implementation Plan

## Design: Direct Tree with Index-Based Nodes (Design B)

### Design Summary

The parser constructs an AST directly during parsing using contiguous `Vec` storage with typed integer indices (`NodeId`, `TokenId`) instead of pointers.  All nodes live in an `Ast` struct that owns both the node and token vectors.  Traversal passes `&Ast` as context.  A `Visitor` trait enables depth-first walks with per-node-type callbacks.

Key properties:
- **Single-phase parse** — matches the Java/Python/C# mental model
- **`NodeId` is `Copy + Send + Sync + 'static`** — no lifetime complexity
- **Cache-friendly** — depth-first traversal walks the node vec nearly sequentially
- **No `Rc<RefCell<>>` or unsafe code** — idiomatic Rust

---

## Phase 1: CongoCC Integration Points

Modifications to existing CongoCC Java source to register Rust as a target language.

### 1.1 Add `RUST` to `CodeLang` enum

**File:** `src/grammars/CongoCC.ccc` (line 298–300)

Change:
```java
public enum CodeLang {
    JAVA, PYTHON, CSHARP;
}
```
To:
```java
public enum CodeLang {
    JAVA, PYTHON, CSHARP, RUST;
}
```

This propagates through the bootstrap parser regeneration into `build/generated-java/org/congocc/parser/Node.java`.

### 1.2 Register `"rust"` in CLI

**File:** `src/java/org/congocc/app/Main.java` (line 119–122)

Change:
```java
private static final String[] otherSupportedLanguages = new String[] {
    "python",
    "csharp"
};
```
To:
```java
private static final String[] otherSupportedLanguages = new String[] {
    "python",
    "csharp",
    "rust"
};
```

The usage message auto-generates from this array.

### 1.3 Auto-define `__rust__` preprocessor symbol

**File:** `src/java/org/congocc/app/AppSettings.java` or `src/java/org/congocc/core/Grammar.java`

When `-lang rust` is specified, automatically define the preprocessor symbol `__rust__` (just as `__java__`, `__python__`, and `__csharp__` should be defined for their respective targets).  This enables grammar authors to write language-specific INJECT blocks:

```
#if __rust__
INJECT PARSER_CLASS : {
    // Rust-specific code, written directly in Rust syntax
    pub fn custom_method(&self) -> bool { true }
}
#endif
#if __java__
INJECT PARSER_CLASS : {
    // Java-specific code
    public boolean customMethod() { return true; }
}
#endif
```

Implementation: in the grammar initialization path, after `setCodeLangString()` is called, add to the preprocessor symbols map:

```java
if (codeLang == RUST) {
    preprocessorSymbols.put("__rust__", "");
}
```

This follows the same pattern that would apply for the other languages.

### 1.4 Create `RustTranslator`

**File:** `src/java/org/congocc/codegen/rust/RustTranslator.java` (new)

Extends `Translator`.  Handles Java→Rust translation for INJECT code blocks and semantic actions.

Constructor settings:
```java
public RustTranslator(Grammar grammar) {
    super(grammar);
    methodIndent = 4;
    fieldIndent = 4;
    isTyped = true;
}
```

#### Translatable patterns

| Method | Java → Rust Mapping |
|---|---|
| `translateIdentifier()` | `null`→`None`, `true`→`true`, `this`→`self`, `thisProduction`→`this_production`, `lastConsumedToken`→`last_consumed_token`, `currentLookaheadToken`→`current_lookahead_token`, camelCase→snake_case for variables/methods, PascalCase for types |
| `translateOperator()` | `\|\|`→`\|\|`, `&&`→`&&` (same), `!`→`!` (same); no changes needed for most operators |
| `translateTypeName()` | `String`→`String`, `List<T>`→`Vec<T>`, `Map<K,V>`→`HashMap<K,V>`, `boolean`→`bool`, `int`→`i32`, `long`→`i64`, `Integer`→`i32`, `HashSet`→`HashSet` |
| `translateInstanceofExpression()` | Java `x instanceof Foo` → Rust `matches!(x, NodeKind::Foo(_))` or pattern match |
| `translateInvocation()` | `size()`→`.len()`, `get(i)`→`[i]` or `.get(i)`, `charAt(i)`→`.chars().nth(i)`, `equals()`→`==`, `toString()`→`.to_string()`, `contains()`→`.contains()`, `add()`→`.push()`, `peekNode()`→`peek_node()`, `popNode()`→`pop_node()`, `pushNode()`→`push_node(n)`, `nodeArity()`→`node_arity()` |
| `translateModifiers()` | `public`→`pub`, `private`→(omit), `protected`→`pub(crate)`, `final`→(omit, Rust immutable by default), `static`→(context-dependent) |
| `translateFormals()` | Add `&self` parameter, `&` references for non-Copy types |

#### Semantic action built-in variables

Generated parse methods provide these variables for use in `{ code }` blocks within productions:

| Java name | Rust equivalent | Type |
|---|---|---|
| `lastConsumedToken` | `last_consumed_token` | `TokenId` |
| `currentLookaheadToken` | `current_lookahead_token` | `usize` (index) |
| `thisProduction` / `THIS` | `this_production` | `NodeId` |
| `peekNode()` / `THAT` | `self.peek_node()` | `Option<NodeId>` |
| `popNode()` | `self.pop_node()` | `Option<NodeId>` |
| `pushNode(n)` | `self.push_node(n)` | `()` |
| `nodeArity()` | `self.node_arity()` | `usize` |

#### Graceful degradation for untranslatable code

Not all Java patterns can be mechanically translated to safe Rust.  Patterns that are problematic include:

- Mutable aliasing (e.g., `node.getParent().getChildren().add(newChild)`)
- Exception-based control flow (`try`/`catch` used for logic, not error handling)
- Complex generics with wildcards (`List<? extends Node>`)
- Anonymous inner classes and lambdas with captured mutable state
- Implicit null semantics across chained method calls

When the `RustTranslator` encounters a Java construct it cannot safely translate, it:

1. **Emits the original Java code as a commented-out block** at the location where the Rust code would go, with an explanation:

```rust
    // FIXME(congocc): The following Java code requires manual translation to Rust.
    // Java patterns involving mutable aliasing cannot be mechanically translated
    // to safe Rust.  Please provide a Rust implementation, or use a
    // #if __rust__ / #endif block in the grammar file to supply Rust-specific code.
    //
    // Original Java:
    //   Node parent = peekNode().getParent();
    //   parent.getChildren().add(0, newChild);
    //   parent.setDirty(true);
```

2. **Reports a warning** through CongoCC's `Errors` facility at generation time, identifying the grammar file location and the problematic code:

```
Warning: [JSON.ccc:42] INJECT PARSER_CLASS contains Java code that could not
  be translated to Rust (mutable aliasing pattern). Manual intervention required.
  See generated file parser.rs for details.
```

This ensures that:
- The generated crate will **not silently produce incorrect code** — untranslated sections are comments, so `cargo build` will fail with a clear missing-implementation error if the section is required for compilation, or will simply be inert if it was in a non-critical code path.
- The **user knows exactly what needs attention** — both from the CongoCC warning at generation time and the `FIXME` comment in the generated source.
- **Simple grammars work out of the box** — grammars with no INJECT blocks or only simple injections (like JSON, Lua) produce fully functional Rust parsers with no warnings.

The `RustTranslator` tracks untranslatable patterns via a `translationWarnings` list, which `FilesGenerator` checks after generation and reports to the user.

### 1.5 Register translator in factory

**File:** `src/java/org/congocc/codegen/Translator.java` (line 704–715)

Add before the default return:
```java
else if (codeLang == RUST) {
    return new RustTranslator(grammar);
}
```

### 1.6 Add Rust case to `FilesGenerator.generateAll()`

**File:** `src/java/org/congocc/codegen/FilesGenerator.java` (after line 141)

```java
case RUST -> {
    String[] paths = new String[]{
        "tokens.rs",
        "lexer.rs",
        "parser.rs",
        "ast.rs",
        "error.rs",
        "visitor.rs",
        "pretty.rs",
        "lib.rs",
        "Cargo.toml",
        "tests/parse_files.rs",
    };
    for (String p : paths) {
        generate(parserOutputDirectory, p);
    }
}
```

### 1.7 Add `outputRustFile()` post-processor

**File:** `src/java/org/congocc/codegen/FilesGenerator.java` (new method)

Initial implementation: write raw template output without AST-based post-processing (CongoCC does not currently have a Rust parser for reformatting).  Format with `rustfmt` externally if desired.

```java
void outputRustFile(String code, Path outputFile) throws IOException {
    try (Writer out = Files.newBufferedWriter(outputFile)) {
        out.write(code);
    }
}
```

Add the dispatch in `generate()` alongside the existing Python/C#/Java cases.

### 1.8 Template name resolution

**File:** `src/java/org/congocc/codegen/FilesGenerator.java`, `getTemplateName()` method

For Rust, append `.ctl` to the output filename (same pattern as Python):
```java
// In getTemplateName(), for RUST case:
return outputFilename + ".ctl";
```

---

## Phase 2: CTL Templates

All templates go in `src/templates/rust/`.  Each generates one Rust source file.

### 2.1 `tokens.rs.ctl` — Token types and lexical states

Generates:

```rust
/// Token types for this grammar.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u16)]
pub enum TokenType {
    // One variant per regexp.label from lexerData.regularExpressions
    // Plus DUMMY and INVALID sentinels
}

/// Lexical states for the lexer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LexicalState {
    // One variant per lexerData.lexicalStates
}
```

Template iterates `lexerData.regularExpressions` and `lexerData.lexicalStates` (same as C# `Tokens.cs.ctl`).

### 2.2 `lexer.rs.ctl` — Lexical analysis

Generates the `Lexer` struct and tokenization logic.

```rust
pub struct Lexer {
    input: Vec<char>,        // source as chars for Unicode correctness
    source_name: String,     // input file name
    cursor: usize,           // current byte offset
    line_offsets: Vec<usize>, // byte offset of each line start
    tokens: Vec<Token>,      // all produced tokens (cached)
    current_lexical_state: LexicalState,
}
```

Public API:
```rust
impl Lexer {
    pub fn new(input: &str, source_name: &str) -> Lexer
    pub fn tokenize(&mut self) -> Result<(), ParseError>

    // Location queries (used by error messages and node display)
    pub fn line_from_offset(&self, offset: usize) -> usize
    pub fn column_from_offset(&self, offset: usize) -> usize
    pub fn get_text(&self, begin: usize, end: usize) -> &str
    pub fn get_line_text(&self, line: usize) -> &str
    pub fn input_source(&self) -> &str
}
```

The template iterates `lexerData` to generate:
- NFA-based token recognition per lexical state
- Keyword recognition (longest-match)
- String literal, number literal, and comment scanning
- Skip-token handling (whitespace, comments)
- Lexical state transitions
- Token activation/deactivation support
- Token caching and cache invalidation

#### Token activation/deactivation

Some grammars use `DEACTIVATE_TOKENS` in settings and `ACTIVATE_TOKENS`/`DEACTIVATE_TOKENS` in productions to enable context-sensitive tokenization (e.g., treating `record` as a keyword only in specific contexts).

The lexer maintains a set of active token types:

```rust
pub struct Lexer {
    // ... other fields ...
    active_token_types: HashSet<TokenType>,
}

impl Lexer {
    /// Activate a token type so the lexer will recognize it.
    pub fn activate_token_type(&mut self, tt: TokenType)

    /// Deactivate a token type so the lexer ignores it during matching.
    pub fn deactivate_token_type(&mut self, tt: TokenType)

    /// Save the current set of active tokens (for restore after a production).
    pub fn save_active_tokens(&self) -> HashSet<TokenType>

    /// Restore a previously saved set of active tokens and reset the
    /// token cache from the given position.
    pub fn restore_active_tokens(&mut self, saved: HashSet<TokenType>, from: usize)
}
```

The parser template generates save/restore calls around productions that use `ACTIVATE_TOKENS` or `DEACTIVATE_TOKENS`:

```rust
let saved_active = self.lexer.save_active_tokens();
self.lexer.activate_token_type(TokenType::RECORD);
// ... parse production ...
self.lexer.restore_active_tokens(saved_active, self.current);
```

### 2.3 `ast.rs.ctl` — Core data structures

This is the heart of Design B.  Generates the typed AST infrastructure.

```rust
/// Opaque handle to a node in the AST.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(transparent)]
pub struct NodeId(u32);

/// Opaque handle to a token in the AST.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(transparent)]
pub struct TokenId(u32);

/// A token with its type, source span, and AST links.
#[derive(Debug, Clone)]
pub struct Token {
    pub token_type: TokenType,
    pub begin_offset: u32,
    pub end_offset: u32,
    pub is_unparsed: bool,
}

/// Discriminated union of all node kinds in this grammar.
#[derive(Debug, Clone)]
pub enum NodeKind {
    // One variant per grammar.nodeNames, e.g.:
    //   Root { ... },
    //   Expression { ... },
    //   Statement { ... },
    // Plus a Token variant for leaf tokens:
    Token(TokenId),
}

/// A node in the AST with parent/child/sibling links.
#[derive(Debug, Clone)]
pub struct Node {
    pub kind: NodeKind,
    pub parent: Option<NodeId>,
    pub first_child: Option<NodeId>,
    pub last_child: Option<NodeId>,
    pub next_sibling: Option<NodeId>,
    pub prev_sibling: Option<NodeId>,
    pub begin_offset: u32,
    pub end_offset: u32,
}

/// Owns all nodes and tokens produced by a parse.
pub struct Ast {
    nodes: Vec<Node>,
    tokens: Vec<Token>,
    source: String,
    source_name: String,
    line_offsets: Vec<usize>,
}
```

Public API on `Ast`:

```rust
impl Ast {
    // Node access
    pub fn root(&self) -> NodeId
    pub fn node(&self, id: NodeId) -> &Node
    pub fn token(&self, id: TokenId) -> &Token

    // AST navigation
    pub fn parent(&self, id: NodeId) -> Option<NodeId>
    pub fn children(&self, id: NodeId) -> ChildIter<'_>
    pub fn first_child(&self, id: NodeId) -> Option<NodeId>
    pub fn last_child(&self, id: NodeId) -> Option<NodeId>
    pub fn next_sibling(&self, id: NodeId) -> Option<NodeId>
    pub fn prev_sibling(&self, id: NodeId) -> Option<NodeId>
    pub fn child_count(&self, id: NodeId) -> usize
    pub fn ancestors(&self, id: NodeId) -> AncestorIter<'_>
    pub fn descendants(&self, id: NodeId) -> DescendantIter<'_>

    // Source text access
    pub fn text(&self, id: NodeId) -> &str
    pub fn token_text(&self, id: TokenId) -> &str
    pub fn begin_line(&self, id: NodeId) -> usize
    pub fn begin_column(&self, id: NodeId) -> usize
    pub fn end_line(&self, id: NodeId) -> usize
    pub fn end_column(&self, id: NodeId) -> usize
    pub fn location(&self, id: NodeId) -> String  // "file:line:col"
    pub fn source_name(&self) -> &str

    // Typed queries
    pub fn kind(&self, id: NodeId) -> &NodeKind
    pub fn is_token(&self, id: NodeId) -> bool
    pub fn token_type(&self, id: NodeId) -> Option<TokenType>
    pub fn first_child_of_type(&self, id: NodeId, tt: TokenType) -> Option<NodeId>
    pub fn children_of_kind(&self, id: NodeId, f: impl Fn(&NodeKind) -> bool) -> Vec<NodeId>

    // Display
    pub fn dump(&self, id: NodeId) -> String  // indented AST dump
}
```

The template iterates `grammar.nodeNames` to generate `NodeKind` variants, and `grammar.parserProductions` for production-specific typed fields within each variant's struct.

#### Named children and child lists

CongoCC grammars support named child annotations (`/name/` and `/[list_name]/`) that assign labels to specific parts of a production.  For example:

```
KeyValuePair : /key/ <STRING_LITERAL> ":" /value/ Value ;
FunctionCall : /name/ <IDENTIFIER> "(" /[args]/ Expression ("," /[args]/ Expression)* ")" ;
```

In the generated Rust AST, named children are exposed as typed accessor methods on `Ast`:

```rust
impl Ast {
    // ... existing navigation methods ...

    // Named child access (generated per production that uses /name/ annotations)
    pub fn key_value_pair_key(&self, id: NodeId) -> Option<NodeId>
    pub fn key_value_pair_value(&self, id: NodeId) -> Option<NodeId>
    pub fn function_call_name(&self, id: NodeId) -> Option<NodeId>
    pub fn function_call_args(&self, id: NodeId) -> Vec<NodeId>
}
```

Named children are stored as additional data within the `Node` struct, using a compact representation:

```rust
pub struct Node {
    // ... existing fields ...

    /// Index into the Ast's named_children table, or u32::MAX if none.
    pub named_children_start: u32,
    pub named_children_count: u16,
}
```

The `Ast` holds a side table of `(NameId, NodeId)` pairs, where `NameId` is a generated enum of all named child labels in the grammar.  This avoids HashMap overhead while supporting arbitrary named children.

#### Tree building annotations

The template handles `#NodeName`, `#void`, `#abstract`, and `#interface` annotations:

- **`#NodeName`** — a variant is generated in the `NodeKind` enum.  The parser creates a node of this kind when the production matches.
- **`#void`** — no node is created.  The production's children are inlined into the parent node's child list.
- **`#abstract`** — the variant exists in `NodeKind` for type-checking purposes but is never directly instantiated.  Serves as a common supertype for related concrete node kinds.
- **`#interface`** — similar to `#abstract`, defines a grouping.  In Rust, both `#abstract` and `#interface` generate helper predicates: `ast.is_literal(id) -> bool`.

Conditional annotations like `#NodeName(>expr)` are translated to Rust boolean expressions via `RustTranslator` and evaluated at parse time to decide whether to create the node.

### 2.4 `parser.rs.ctl` — Recursive descent parser

Generates the `Parser` struct and a `parse_<production>()` method for each grammar production.

```rust
pub struct Parser {
    lexer: Lexer,
    tokens: Vec<Token>,       // consumed token stream
    current: usize,           // index into tokens
    ast_builder: AstBuilder,  // accumulates nodes during parse
    call_stack: Vec<&'static str>,  // production names for error reporting

    // Lookahead/scan state
    current_lookahead_token: usize,   // position during lookahead scan
    remaining_lookahead: i32,         // token budget for current scan
    hit_failure: bool,                // scan failed flag
    passed_predicate: bool,           // scan passed predicate threshold
    lookahead_routine_nesting: u32,   // recursion depth (0 = parsing, >0 = scanning)
    lookahead_stack: Vec<usize>,      // saved positions during nested scans
}
```

Public API:
```rust
impl Parser {
    pub fn new(input: &str, source_name: &str) -> Result<Parser, ParseError>

    /// Parse the input starting from the grammar's TEST_PRODUCTION.
    pub fn parse(input: &str, source_name: &str) -> Result<Ast, ParseError>
}
```

The template generates for each `grammar.parserProductions`:
- A `parse_<name>(&mut self) -> Result<NodeId, ParseError>` method
- Token consumption: `expect(TokenType)`, `match_token(TokenType)`, `peek()`

#### Lookahead and Scan Support

CongoCC grammars use `SCAN` predicates to resolve ambiguities between alternatives in a choice.  The generated Rust parser fully supports these semantics, following the same patterns as the C#, Python, and Java targets.

**Choosing between alternatives** — for each `ExpansionChoice`, the template generates:

- **Single-token lookahead (fast path):** When the choice can be resolved by examining one token, the parser calls `peek()` and matches against the first-set of each alternative.  If the first-set is small (< 5 tokens), this is an inline `matches!()` check; otherwise it uses a precomputed `HashSet<TokenType>` constant.
- **Multi-token / syntactic lookahead:** When a `SCAN` predicate specifies a pattern or numerical depth > 1, the template generates a `scan_<name>()` method that tentatively advances through tokens without building AST nodes.

**Scan routines** — generated for each expansion that requires syntactic lookahead:

```rust
/// Tentatively scan tokens to check if this alternative matches.
/// Returns true if the pattern matches, false otherwise.
/// Does not modify the parse position or AST.
fn scan_<name>(&mut self, scan_to_end: bool) -> bool {
    let passed_predicate_threshold =
        self.remaining_lookahead - <lookahead_amount>;
    self.lookahead_routine_nesting += 1;

    // 1. Evaluate semantic predicate (if any):
    //    SCAN { expression } => ...
    //    If the expression is false, return false immediately.

    // 2. Evaluate look-behind (if any):
    //    Check that the parser's call stack matches the required context.

    // 3. Scan the syntactic pattern:
    //    Walk the expansion, calling scan_token() for terminals
    //    and scan_<production>() for non-terminals.

    self.lookahead_routine_nesting -= 1;
    if self.remaining_lookahead <= passed_predicate_threshold {
        self.passed_predicate = true;
    }
    true
}
```

**`scan_token()` — the core lookahead primitive:**

```rust
/// Advance the lookahead position by one token if it matches.
/// Decrements the remaining lookahead budget.
fn scan_token(&mut self, expected: &[TokenType]) -> bool {
    let peeked = self.next_token(self.current_lookahead_token);
    if !expected.iter().any(|tt| self.type_matches(*tt, peeked)) {
        self.hit_failure = true;
        return false;
    }
    self.remaining_lookahead -= 1;
    self.current_lookahead_token = peeked;
    true
}
```

**Backtracking** — when a scanned alternative fails, the parser restores `current_lookahead_token`, `remaining_lookahead`, and `hit_failure` to their saved values before trying the next alternative.

**Scan limits (`=>||` and `=>|+N`)** — the "up-to-here" marker resets the lookahead budget at a specific point in the expansion, preventing unbounded scanning:

```rust
if !scan_to_end && self.lookahead_stack.len() <= 1 {
    if self.lookahead_routine_nesting == 0 {
        self.remaining_lookahead = <scan_limit_plus>;
    }
}
```

**Negated scans (`SCAN ~Pattern =>`)** — the scan result is inverted: the alternative is chosen only if the pattern does *not* match.

**Semantic predicates (`SCAN { expr } =>`)** — the expression is translated to Rust via `RustTranslator` and evaluated inline.  If false, the alternative is skipped without scanning further tokens.

#### Semantic actions (`{ code }` blocks in productions)

Grammars can embed Java code blocks within productions that execute during parsing:

```
Value #JSONValue :
    <STRING_LITERAL> { thisProduction.setStringValue(lastConsumedToken.toString()); }
    | <NUMBER> { thisProduction.setNumericValue(lastConsumedToken.toString()); }
    | ...
;
```

These code blocks are translated to Rust via `RustTranslator` and emitted inline in the generated `parse_*` methods.  The parser provides the following built-in variables and methods within these blocks:

```rust
// Available in every parse method:
let last_consumed_token: TokenId = /* ... */;  // most recently consumed token
let this_production: NodeId = /* ... */;       // node being built

// Parser methods callable from semantic actions:
self.peek_node() -> Option<NodeId>    // top of node stack
self.pop_node() -> Option<NodeId>     // remove and return top of stack
self.push_node(id: NodeId)            // push node onto stack
self.node_arity() -> usize            // number of nodes on stack in current scope
```

Code blocks prefixed with `%` (`{% code %}`) are also executed during lookahead scanning, not just during actual parsing.  The template distinguishes these by checking `expansion.insideLookahead`.

If a semantic action contains Java code that `RustTranslator` cannot translate, the graceful degradation mechanism (section 1.4) applies: the Java code is emitted as a comment with a `FIXME` annotation, and a warning is reported.

#### ASSERT and ENSURE

`ASSERT` and `ENSURE` are runtime assertions within productions:

- **`ASSERT { condition : "message" }`** — evaluated during parsing only.  If the condition is false, parsing fails with the given message.
- **`ENSURE { condition : "message" }`** — evaluated during both parsing and lookahead.  If false during lookahead, the alternative is rejected; if false during parsing, parsing fails.

The condition expression is translated to Rust via `RustTranslator`:

```rust
// ASSERT { !isModule : "No wildcard in module import" }
if is_module {
    return Err(ParseError::new(
        "No wildcard in module import",
        self.current_location(),
    ));
}
```

Expansion-form assertions (`ASSERT ( expansion )`) check whether a syntactic pattern can match at the current position, reusing the scan-routine infrastructure.

#### Production parameters and return types

Productions can declare parameters and return types:

```
TypeArgument(boolean inWildcard) :
    SCAN { !inWildcard } => ReferenceType
    | Wildcard
;
```

Parameters are translated to Rust function parameters:

```rust
fn parse_type_argument(&mut self, in_wildcard: bool) -> ParseResult<NodeId> {
    if !in_wildcard {
        // SCAN for ReferenceType...
    }
    // ...
}
```

Type translation follows the `RustTranslator` type mapping (`boolean` → `bool`, `String` → `&str` or `String`, etc.).

`AstBuilder` is an internal helper:
```rust
struct AstBuilder {
    nodes: Vec<Node>,
    tokens: Vec<Token>,
    // Methods: start_node(), finish_node(), add_token(), etc.
}
```

After parsing completes, `AstBuilder::finish()` produces the immutable `Ast`.

### 2.5 `error.rs.ctl` — Error types

```rust
/// A parse error with location, context, and expected tokens.
#[derive(Debug, Clone)]
pub struct ParseError {
    pub message: String,
    pub line: usize,
    pub column: usize,
    pub source_name: String,
    pub expected: Vec<TokenType>,
    pub found: Option<TokenType>,
    pub production_stack: Vec<String>,
}

impl std::fmt::Display for ParseError { ... }
impl std::error::Error for ParseError {}

pub type ParseResult<T> = Result<T, ParseError>;
```

The `Display` impl formats errors like:
```
file.json:3:15: expected one of '{', '[', STRING, NUMBER, but found ','
  in: Value > Array
```

### 2.6 `visitor.rs.ctl` — Visitor pattern

Generates a trait with one method per node kind, plus a `walk()` driver.

```rust
/// Visitor trait for depth-first AST traversal.
///
/// Override specific `visit_*` methods to process nodes of interest.
/// Each method receives the node's `NodeId` and a reference to the
/// `Ast`.  The default implementation calls `walk_children()`
/// to continue the traversal.
pub trait Visitor {
    // One method per grammar.nodeNames:
    fn visit_root(&mut self, id: NodeId, ast: &Ast) {
        self.walk_children(id, ast);
    }
    fn visit_expression(&mut self, id: NodeId, ast: &Ast) {
        self.walk_children(id, ast);
    }
    // ... etc for each node type ...

    fn visit_token(&mut self, _id: NodeId, _ast: &Ast) {}

    fn walk_children(&mut self, id: NodeId, ast: &Ast) {
        for child in ast.children(id) {
            self.visit(child, ast);
        }
    }

    fn visit(&mut self, id: NodeId, ast: &Ast) {
        match ast.kind(id) {
            NodeKind::Root { .. } => self.visit_root(id, ast),
            NodeKind::Expression { .. } => self.visit_expression(id, ast),
            // ... dispatch for each kind ...
            NodeKind::Token(_) => self.visit_token(id, ast),
        }
    }
}
```

### 2.7 `visitor.rs.ctl` — AstMapper trait (functional AST transformation)

The same template file also generates the `AstMapper` trait alongside `Visitor`.  While `Visitor` provides read-only traversal, `AstMapper` enables both node-local and structural changes by producing a new `Ast` from an existing one.

#### Design Principles

- **Source and output are separate** — the mapper reads from an immutable `&Ast` and writes to a new `AstBuilder`.  There are no borrow conflicts: Rust's borrow checker is satisfied trivially.
- **Bottom-up traversal** — children are mapped before their parents.  By the time `map_foo()` is called, all of foo's descendants are finalized in the builder.  The `mapped_children` slice contains stable `NodeId`s in the output AST.
- **Default is identity** — every `map_*` method defaults to preserving the node's kind and passing through its mapped children.  Users override only the node types they want to transform.
- **Composable** — mappers can be chained: `let ast2 = m1.map(&ast1); let ast3 = m2.map(&ast2);`

#### Generated API

```rust
/// Describes what a mapped node becomes in the output AST.
pub enum MappedNode {
    /// Emit a node with the given kind and children.
    /// Use the source kind + mapped_children for identity.
    /// Use a different kind for node-local changes.
    /// Use a different children vec for structural changes.
    /// Offsets are recomputed from the children's spans.
    Node {
        kind: NodeKind,
        children: Vec<NodeId>,
    },

    /// Remove this node from the tree and splice the given nodes
    /// (typically the mapped children, or builder-created nodes)
    /// directly into the parent's child list.
    Splice(Vec<NodeId>),

    /// Remove this node and all its descendants from the output.
    Remove,
}

/// Functional AST transformation driven by bottom-up traversal.
///
/// The framework walks the source AST in post-order (children before
/// parents).  For each node, it maps all children first, then calls
/// the appropriate `map_*` method with:
///
/// - `source_id` — the node's ID in the source AST (for reading
///   context, siblings, ancestors, etc.)
/// - `source` — the complete source AST (read-only)
/// - `mapped_children` — this node's children, already mapped, as
///   `NodeId`s in the output AST being built
/// - `builder` — mutable handle for creating new nodes in the output
///
/// Each method returns a `MappedNode` describing what this node
/// should become.
pub trait AstMapper {
    // One method per grammar.nodeNames, e.g.:

    fn map_root(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        MappedNode::Node {
            kind: source.kind(source_id).clone(),
            children: mapped_children.to_vec(),
        }
    }

    fn map_expression(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        mapped_children: &[NodeId],
        builder: &mut AstBuilder,
    ) -> MappedNode {
        MappedNode::Node {
            kind: source.kind(source_id).clone(),
            children: mapped_children.to_vec(),
        }
    }

    // ... generated for each node kind ...

    /// Map a token leaf node.  Tokens have no children.
    fn map_token(
        &mut self,
        source_id: NodeId,
        source: &Ast,
        builder: &mut AstBuilder,
    ) -> MappedNode {
        MappedNode::Node {
            kind: source.kind(source_id).clone(),
            children: vec![],
        }
    }

    /// Drive the bottom-up transformation.  Returns a new Ast.
    ///
    /// The framework:
    /// 1. Walks the source AST in post-order.
    /// 2. For each node, collects its already-mapped children.
    /// 3. Calls the appropriate `map_*` method.
    /// 4. Handles `MappedNode::Node` (insert into builder),
    ///    `Splice` (flatten into parent), or `Remove` (skip).
    /// 5. Returns the completed output Ast.
    fn map(&mut self, source: &Ast) -> Ast { ... }
}
```

#### `AstBuilder` API for mappers

During mapping, the `builder` parameter allows creating new nodes that don't correspond to anything in the source AST:

```rust
impl AstBuilder {
    /// Create a new interior node in the output AST.
    /// Children must already exist in the builder (returned by
    /// prior add_node/add_token calls or from mapped_children).
    /// Returns the new node's NodeId.
    pub fn add_node(&mut self, kind: NodeKind, children: &[NodeId]) -> NodeId

    /// Create a new token leaf node in the output AST.
    /// Returns the new node's NodeId.
    pub fn add_token(&mut self, token_type: TokenType, text: &str) -> NodeId

    /// Read a node already in the output AST (for inspection
    /// during mapping decisions).
    pub fn kind(&self, id: NodeId) -> &NodeKind

    /// Read a token already in the output AST.
    pub fn token(&self, id: TokenId) -> &Token
}
```

#### Usage Examples

**Node-local change** — modify a node's data without changing structure:

```rust
struct FlipOperators;
impl AstMapper for FlipOperators {
    fn map_binary_expression(
        &mut self, source_id: NodeId, source: &Ast,
        mapped_children: &[NodeId], _builder: &mut AstBuilder,
    ) -> MappedNode {
        let kind = match source.kind(source_id) {
            NodeKind::BinaryExpression { op: BinaryOp::Add } =>
                NodeKind::BinaryExpression { op: BinaryOp::Sub },
            other => other.clone(),
        };
        MappedNode::Node { kind, children: mapped_children.to_vec() }
    }
}

let modified = FlipOperators.map(&original_ast);
```

**Filter children** — remove specific children from a node:

```rust
struct StripComments;
impl AstMapper for StripComments {
    fn map_token(
        &mut self, source_id: NodeId, source: &Ast,
        _builder: &mut AstBuilder,
    ) -> MappedNode {
        if source.token_type(source_id) == Some(TokenType::COMMENT) {
            return MappedNode::Remove;
        }
        MappedNode::Node {
            kind: source.kind(source_id).clone(),
            children: vec![],
        }
    }
}
```

**Unwrap / hoist** — remove a wrapper node, keeping its children:

```rust
struct RemoveParens;
impl AstMapper for RemoveParens {
    fn map_parenthesized_expression(
        &mut self, _source_id: NodeId, _source: &Ast,
        mapped_children: &[NodeId], _builder: &mut AstBuilder,
    ) -> MappedNode {
        // The parens node is removed; its children (the inner
        // expression) are spliced into the parent's child list.
        MappedNode::Splice(mapped_children.to_vec())
    }
}
```

**Insert new nodes** — use the builder to create synthetic nodes:

```rust
struct WrapBodiesInLogging;
impl AstMapper for WrapBodiesInLogging {
    fn map_function(
        &mut self, source_id: NodeId, source: &Ast,
        mapped_children: &[NodeId], builder: &mut AstBuilder,
    ) -> MappedNode {
        // Wrap the last child (function body) in a LogWrapper node.
        let mut children = mapped_children.to_vec();
        if let Some(body_id) = children.pop() {
            let wrapper = builder.add_node(
                NodeKind::LogWrapper,
                &[body_id],
            );
            children.push(wrapper);
        }
        MappedNode::Node {
            kind: source.kind(source_id).clone(),
            children,
        }
    }
}
```

**Delete a subtree** — remove a node and all its descendants:

```rust
struct StripDebugStatements;
impl AstMapper for StripDebugStatements {
    fn map_debug_statement(
        &mut self, _source_id: NodeId, _source: &Ast,
        _mapped_children: &[NodeId], _builder: &mut AstBuilder,
    ) -> MappedNode {
        MappedNode::Remove
    }
}
```

**Chaining transforms:**

```rust
let ast = Parser::parse(input, source_name)?;
let ast = StripComments.map(&ast);
let ast = RemoveParens.map(&ast);
let ast = WrapBodiesInLogging.map(&ast);
```

### 2.8 `pretty.rs.ctl` — Pretty-print API

Generates a Wadler-Lindig pretty-printer built on the read-only `Visitor` interface.  The pretty-printer converts an AST into an abstract `Doc` document that is then rendered to text with optimal line-breaking for a given target width.

#### Design Principles

- **Read-only** — the pretty-printer only reads the AST via `&Ast`; it never modifies it.  Internally it uses the `Visitor` traversal pattern.
- **Separation of content and layout** — `pp_*` methods produce `Doc` values describing *what* to print.  The `Doc::render()` method decides *where* to break lines based on the target width.
- **Overridable defaults** — every `pp_*` method has a generated default that produces reasonable output.  Users override specific methods to customize formatting for their grammar.
- **Works on transformed ASTs** — after an `AstMapper` produces a new AST (possibly with builder-created tokens that have no source offsets), the pretty-printer can still render it to text, because it reads `NodeKind` and token text rather than source byte spans.

#### `Doc` type

```rust
/// A pretty-print document.  Represents content and potential
/// line-break points without committing to a specific layout.
#[derive(Debug, Clone)]
pub enum Doc {
    /// Empty document.
    Nil,

    /// Literal text (must not contain newlines).
    Text(String),

    /// Soft line break: rendered as a single space if the enclosing
    /// `Group` fits on one line, otherwise rendered as a newline
    /// followed by the current indentation.
    Line,

    /// Hard line break: always rendered as a newline followed by
    /// the current indentation.  Forces the enclosing Group to break.
    HardLine,

    /// Increase indentation by `n` spaces for the inner document.
    Indent(i32, Box<Doc>),

    /// Try to flatten the inner document onto one line (replacing
    /// `Line` with space).  If it doesn't fit within the remaining
    /// width, render in broken (multi-line) mode instead.
    Group(Box<Doc>),

    /// Concatenation of documents in sequence.
    Concat(Vec<Doc>),
}
```

#### `Doc` combinators

```rust
impl Doc {
    /// Create a text document.
    pub fn text(s: impl Into<String>) -> Doc

    /// Concatenate multiple documents.
    pub fn concat(docs: impl IntoIterator<Item = Doc>) -> Doc

    /// Group: try to fit on one line, break if it doesn't.
    pub fn group(inner: Doc) -> Doc

    /// Indent the inner document by `n` additional spaces.
    pub fn indent(n: i32, inner: Doc) -> Doc

    /// Join documents with a separator between each pair.
    /// Example: `Doc::intersperse(items, Doc::text(", "))`
    pub fn intersperse(docs: Vec<Doc>, sep: Doc) -> Doc

    /// Join documents with a soft line break between each pair.
    /// In flat mode (inside a fitting Group), rendered as spaces.
    /// In broken mode, rendered as newlines.
    pub fn join_lines(docs: Vec<Doc>) -> Doc

    /// Render this document to a string, targeting the given
    /// line width.  Uses the Wadler-Lindig optimal layout algorithm.
    pub fn render(&self, width: usize) -> String
}
```

#### `PrettyPrinter` trait

```rust
/// Pretty-print trait for AST-to-text conversion.
///
/// Each `pp_*` method receives a `NodeId` and `&Ast` (read-only,
/// same as `Visitor`) and returns a `Doc` describing how that node
/// should be formatted.
///
/// Default implementations are generated from the grammar structure:
/// - **Tokens** emit their text image.
/// - **Separator-list patterns** (e.g., `X ("," X)*`) intersperse
///   items with the separator token and a soft line break.
/// - **Delimited groups** (matched `{}`/`[]`/`()`) wrap the body
///   in `Doc::Group(open + Doc::Indent(4, body) + Doc::Line + close)`.
/// - **All other nodes** concatenate their children separated by
///   soft line breaks.
///
/// Override specific methods to customize formatting.
pub trait PrettyPrinter {
    // One method per grammar.nodeNames, e.g.:

    fn pp_root(&self, id: NodeId, ast: &Ast) -> Doc {
        // Default: join children with soft line breaks
        let child_docs: Vec<Doc> = ast.children(id)
            .map(|child| self.pp(child, ast))
            .collect();
        Doc::join_lines(child_docs)
    }

    fn pp_array(&self, id: NodeId, ast: &Ast) -> Doc {
        // Default for delimited group: [ items ]
        let items = self.pp_children_between_delimiters(id, ast);
        Doc::group(Doc::concat([
            Doc::text("["),
            Doc::indent(4, Doc::concat([
                Doc::Line,
                Doc::intersperse(items, Doc::concat([
                    Doc::text(","),
                    Doc::Line,
                ])),
            ])),
            Doc::Line,
            Doc::text("]"),
        ]))
    }

    fn pp_json_object(&self, id: NodeId, ast: &Ast) -> Doc {
        // Default for delimited group: { pairs }
        // ...similar pattern with "{" / "}"...
    }

    // ... generated for each node kind ...

    /// Format a token leaf node.
    fn pp_token(&self, id: NodeId, ast: &Ast) -> Doc {
        // Emit the token's text image
        Doc::text(ast.text(id))
    }

    /// Dispatch to the appropriate pp_* method by node kind.
    fn pp(&self, id: NodeId, ast: &Ast) -> Doc {
        match ast.kind(id) {
            NodeKind::Root { .. } => self.pp_root(id, ast),
            NodeKind::Array { .. } => self.pp_array(id, ast),
            NodeKind::JSONObject { .. } => self.pp_json_object(id, ast),
            // ... dispatch for each kind ...
            NodeKind::Token(_) => self.pp_token(id, ast),
        }
    }

    /// Convenience: pretty-print the root node and render to string.
    fn render(&self, ast: &Ast, width: usize) -> String {
        self.pp(ast.root(), ast).render(width)
    }

    /// Helper: collect Doc values for children between the first
    /// and last token (the delimiters) of a node.
    fn pp_children_between_delimiters(
        &self, id: NodeId, ast: &Ast,
    ) -> Vec<Doc> { ... }
}

/// Default implementation: can be used directly without overrides.
pub struct DefaultPrettyPrinter;
impl PrettyPrinter for DefaultPrettyPrinter {}
```

#### How defaults are generated from grammar structure

The template inspects each production's structure to choose the appropriate default:

| Grammar Pattern | Default `pp_*` Implementation |
|---|---|
| `X : Y <EOF>` (root) | `pp(Y)` only (suppress EOF token) |
| `X : "[" (Y ("," Y)*)? "]"` (delimited list) | `Group("[" + Indent(4, Line + items) + Line + "]")` with `","` + `Line` between items |
| `X : "{" (Y ("," Y)*)? "}"` (delimited list) | Same pattern with `{`/`}` |
| `X : Y (("+" \| "-") Y)*` (infix) | Children joined with spaces (operators are tokens) |
| `X : <A> \| <B> \| Y` (choice) | Delegate to whichever alternative was parsed |
| All other productions | Children joined with `Doc::Line` (soft breaks) |

The template uses the same `grammar.parserProductions` iteration as the parser and visitor templates.  For each production, it examines the expansion tree to detect delimiters, separators, and infix patterns, then emits the appropriate default body.

#### Usage Examples

**Use the generated defaults directly:**

```rust
use my_parser::{Parser, DefaultPrettyPrinter, PrettyPrinter};

let ast = Parser::parse(input, "input.json")?;
let formatted = DefaultPrettyPrinter.render(&ast, 80);
println!("{}", formatted);
```

**Override formatting for specific nodes:**

```rust
struct CompactJson;
impl PrettyPrinter for CompactJson {
    fn pp_array(&self, id: NodeId, ast: &Ast) -> Doc {
        // Force arrays onto one line regardless of width
        let items: Vec<Doc> = ast.children(id)
            .filter(|&child| !ast.is_token(child)
                || !matches!(ast.token_type(child),
                    Some(TokenType::LBRACKET | TokenType::RBRACKET)))
            .map(|child| self.pp(child, ast))
            .collect();
        Doc::concat([
            Doc::text("["),
            Doc::intersperse(items, Doc::text(", ")),
            Doc::text("]"),
        ])
    }
}

let compact = CompactJson.render(&ast, 120);
```

**Pretty-print after an AstMapper transform:**

```rust
let ast = Parser::parse(input, source_name)?;
let ast = StripComments.map(&ast);
let output = DefaultPrettyPrinter.render(&ast, 80);
```

### 2.9 `lib.rs.ctl` — Crate root

```rust
//! Parser generated by CongoCC.  Do not edit.

pub mod tokens;
pub mod lexer;
pub mod error;
pub mod ast;
pub mod parser;
pub mod visitor;
pub mod pretty;

pub use tokens::TokenType;
pub use ast::{Ast, AstBuilder, NodeId, TokenId, NodeKind};
pub use parser::Parser;
pub use error::{ParseError, ParseResult};
pub use visitor::{Visitor, AstMapper, MappedNode};
pub use pretty::{Doc, PrettyPrinter, DefaultPrettyPrinter};
```

### 2.9 `Cargo.toml.ctl` — Package manifest

```toml
[package]
name = "${package_name}"
version = "0.1.0"
edition = "2024"

[lib]
path = "lib.rs"

[[test]]
name = "parse_files"
path = "tests/parse_files.rs"
```

The `name` derives from `settings.parserPackage` (dots replaced with hyphens).
Source files are placed at the crate root (not in `src/`) to match CongoCC's output directory model.

### 2.11 `tests/parse_files.rs.ctl` — Integration test harness

Generated test binary that parses all files in a given directory:

```rust
use ${crate_name}::Parser;
use std::{env, fs, path::Path, process};

fn parse_file(path: &Path) -> bool {
    let input = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Failed to read {}: {}", path.display(), e);
            return false;
        }
    };
    let source_name = path.to_string_lossy();
    match Parser::parse(&input, &source_name) {
        Ok(ast) => {
            println!("Parsed {} ({} nodes)", path.display(),
                     /* node count */);
            true
        }
        Err(e) => {
            eprintln!("Failed to parse {}: {}", path.display(), e);
            false
        }
    }
}

fn main() {
    // Accepts file or directory arguments, recurses into directories
    // matching *.${test_extension}, reports success/failure counts,
    // exits with non-zero status on any failure.
}
```

---

## Phase 3: Build System Integration

### 3.1 Top-level `build.xml`

Add to the `test` target (after existing language tests):

```xml
<echo>Testing the Rust parsers</echo>
<ant dir="examples/json" target="test-rust" />
<ant dir="examples/lua" target="test-rust" />
<!-- etc. for each example grammar -->
```

Add a `test-rust` convenience target:
```xml
<target name="test-rust" depends="jar">
    <ant dir="examples/json" target="test-rust" />
    <ant dir="examples/lua" target="test-rust" />
    ...
</target>
```

### 3.2 Example `build.xml` updates (per-grammar)

Each example's `build.xml` gets three new targets:

```xml
<!-- Generate Rust parser from .ccc -->
<target name="rust-parser-gen" depends="init" unless="rustparser.uptodate">
    <java jar="../../congocc.jar" failonerror="true" fork="true">
        <assertions><enable/></assertions>
        <arg line="-n -lang rust -d ${basedir}/rust-${name} ${basedir}/Grammar.ccc"/>
    </java>
</target>

<!-- Build Rust parser with cargo -->
<target name="compile-rust" depends="rust-parser-gen">
    <exec executable="cargo" dir="${basedir}/rust-${name}" failonerror="true">
        <arg line="build"/>
    </exec>
</target>

<!-- Run Rust integration tests -->
<target name="test-rust" depends="compile-rust">
    <exec executable="cargo" dir="${basedir}/rust-${name}" failonerror="true">
        <arg line="test"/>
    </exec>
    <!-- Also run the parse_files binary against testfiles/ -->
    <exec executable="cargo" dir="${basedir}/rust-${name}" failonerror="true">
        <arg line="run --test parse_files -- ../../testfiles"/>
    </exec>
</target>
```

### 3.3 Template copy

Already handled.  The existing build rule copies all `src/templates/**/*.ctl` to `build/templates/`.  No changes needed — adding files under `src/templates/rust/` is sufficient.

---

## Phase 4: Example Grammars and Testing

### 4.1 Grammars to target

Every grammar that has both Python and C# test targets should also get Rust.  Based on the existing `build.xml` test targets:

| Example | Grammar | Test Files | Priority |
|---|---|---|---|
| `json` | JSON.ccc, JSONC.ccc | 5 files | 1 (simplest, implement first) |
| `lua` | Lua.ccc | 39 files | 2 |
| `cics` | Cics.ccc | 6 files | 3 |
| `preprocessor` | Preprocessor.ccc | 45 files | 3 |
| `java` | Java.ccc | 14 files | 4 (complex grammar) |
| `csharp` | CSharp.ccc | 164 files | 4 (most complex) |
| `python` | Python.ccc | 9 files | 4 |
| `arithmetic` | Arithmetic1.ccc, Arithmetic2.ccc | (manual) | 1 (good for visitor demo) |

### 4.2 Generated output directory structure

For `examples/json`, Rust generation with `-d rust-json` produces:

```
examples/json/rust-json/
├── Cargo.toml
├── lib.rs
├── tokens.rs
├── lexer.rs
├── parser.rs
├── ast.rs
├── error.rs
├── visitor.rs
├── pretty.rs
└── tests/
    └── parse_files.rs
```

### 4.3 Test categories

For each grammar, verify:

1. **Parse correctness** — all test files parse successfully
2. **Error reporting** — malformed inputs produce errors with correct line/column/expected-set
3. **AST completeness** — `ast.dump()` on a sample file produces a complete, correct AST
4. **Visitor traversal** — a counting visitor walks all nodes
5. **Round-trip** — `ast.text(ast.root())` reconstructs the original source

### 4.4 Arithmetic visitor example

Provide a worked example with the arithmetic grammar showing:

```rust
use arithmetic_parser::{Parser, Ast, Visitor, NodeId, NodeKind};

struct Evaluator<'a> {
    ast: &'a Ast,
    result: f64,
}

impl Visitor for Evaluator<'_> {
    fn visit_additive_expression(&mut self, id: NodeId, ast: &Ast) {
        // Evaluate left, get operator, evaluate right, combine
    }
    // ... etc.
}

fn main() {
    let ast = Parser::parse("2 + 3 * 4", "<stdin>").unwrap();
    let mut eval = Evaluator { ast: &ast, result: 0.0 };
    eval.visit(ast.root(), &ast);
    println!("Result: {}", eval.result);  // 14.0
}
```

---

## Phase 5: Implementation Order

### Step 1 — Scaffolding (no generated code yet)

1. Add `RUST` to `CodeLang` enum in `src/grammars/CongoCC.ccc`
2. Add `"rust"` to `otherSupportedLanguages` in `Main.java`
3. Auto-define `__rust__` preprocessor symbol when `-lang rust` is specified
4. Create `src/java/org/congocc/codegen/rust/RustTranslator.java` (stub with graceful degradation)
5. Register in `Translator.getTranslatorFor()`
6. Add `case RUST` to `FilesGenerator.generateAll()` with file list
7. Add `outputRustFile()` to `FilesGenerator`
8. Create empty template stubs in `src/templates/rust/`
9. Verify: `ant jar` succeeds, `java -jar congocc.jar -n -lang rust examples/json/JSON.ccc` runs without error

### Step 2 — Tokens and Lexer (first compilable output)

1. Implement `tokens.rs.ctl` — generate `TokenType` and `LexicalState` enums
2. Implement `lexer.rs.ctl` — generate `Lexer` with full tokenization
3. Implement `error.rs.ctl` — generate error types
4. Implement minimal `lib.rs.ctl` and `Cargo.toml.ctl`
5. Verify: generated crate compiles with `cargo build`
6. Write manual tests: tokenize JSON input, verify token types and spans

### Step 3 — Syntax Tree and Parser (parse works)

1. Implement `ast.rs.ctl` — generate `NodeId`, `Node`, `NodeKind`, `Ast`, `AstBuilder`
2. Implement `parser.rs.ctl` — generate recursive descent parser with AST building
3. Verify: `Parser::parse(...)` returns an `Ast` for valid JSON
4. Verify: error messages report correct line/column/expected-set for invalid JSON
5. Implement `ast.dump()` for debugging

### Step 4 — Visitor, AstMapper, PrettyPrinter, and Testing (feature complete)

1. Implement `visitor.rs.ctl` — generate `Visitor` trait, `AstMapper` trait, and `MappedNode` enum
2. Implement `pretty.rs.ctl` — generate `Doc`, `PrettyPrinter` trait, and `DefaultPrettyPrinter`
3. Implement `tests/parse_files.rs.ctl` — generate test harness
4. Update example `build.xml` files to include `test-rust` targets
5. Run all JSON test files through generated Rust parser
6. Write mapper tests: identity transform, node-local change, structural change
7. Write pretty-print tests: default formatting, round-trip (parse → pretty-print → re-parse)
8. Fix any issues found

### Step 5 — Complete `RustTranslator` (INJECT support)

1. Implement full `translateIdentifier()`, `translateTypeName()`, etc.
2. Test with grammars that use INJECT blocks (Java, C#, Python grammars)
3. Handle edge cases in code translation

### Step 6 — Expand to all grammars

1. Generate and test Lua parser
2. Generate and test CICS parser
3. Generate and test Preprocessor parser
4. Generate and test Arithmetic parsers (with visitor example)
5. Generate and test Java, C#, Python parsers (most complex)

### Step 7 — Build system and polish

1. Update top-level `build.xml` with `test-rust` target
2. Add `test-rust` to each example's `build.xml`
3. Verify `ant test-rust` passes for all grammars
4. Performance benchmarking vs. Java parser on large inputs
5. Clean up generated code formatting

### Step 8 — Fault-tolerant parsing (deferred)

Fault-tolerant parsing (`FAULT_TOLERANT` setting, `ATTEMPT`/`RECOVER` blocks) is a separate effort because the existing Java/C#/Python implementations rely on exception-based error recovery, which has no direct Rust equivalent.

The Rust design will use a checkpoint/restore model:

1. Before an `ATTEMPT` block, save parser state (token position, AST builder stack depth)
2. Try the production; on failure, restore saved state
3. Scan forward through tokens looking for a recovery point (a token in the follow-set of the enclosing production)
4. Wrap skipped tokens in an `InvalidNode` variant in the AST
5. Resume parsing from the recovery point

Implementation tasks:
1. Add `InvalidNode` variant to `NodeKind`
2. Implement `checkpoint()` / `restore()` on the parser
3. Implement `skip_to_recovery_point()` token scanner
4. Generate `ATTEMPT`/`RECOVER` blocks as checkpoint/try/restore sequences
5. Test with grammars that enable `FAULT_TOLERANT` (Java, C# grammars)

---

## Directory Summary

### New directories and files in `src/`

```
src/
├── java/org/congocc/codegen/rust/
│   └── RustTranslator.java          # Java→Rust code translator
└── templates/rust/
    ├── tokens.rs.ctl                 # TokenType, LexicalState enums
    ├── lexer.rs.ctl                  # Lexer struct and tokenization
    ├── parser.rs.ctl                 # Recursive descent parser
    ├── ast.rs.ctl                    # NodeId, Node, NodeKind, Ast
    ├── error.rs.ctl                  # ParseError, ParseResult
    ├── visitor.rs.ctl                # Visitor, AstMapper, MappedNode
    ├── pretty.rs.ctl                 # Doc, PrettyPrinter, DefaultPrettyPrinter
    ├── lib.rs.ctl                    # Crate root with re-exports
    ├── Cargo.toml.ctl                # Package manifest
    └── tests/
        └── parse_files.rs.ctl        # Integration test harness
```

### Modified existing files

```
src/grammars/CongoCC.ccc              # Add RUST to CodeLang enum
src/java/org/congocc/app/Main.java    # Add "rust" to supported languages
src/java/org/congocc/app/AppSettings.java            # Auto-define __rust__ preprocessor symbol
src/java/org/congocc/codegen/Translator.java         # Factory method for RustTranslator
src/java/org/congocc/codegen/FilesGenerator.java     # RUST case + outputRustFile()
build.xml                             # test-rust target
examples/json/build.xml               # rust-parser-gen, compile-rust, test-rust
examples/lua/build.xml                # (same pattern)
examples/cics/build.xml               # (same pattern)
examples/preprocessor/build.xml       # (same pattern)
examples/java/build.xml               # (same pattern)
examples/csharp/build.xml             # (same pattern)
examples/python/build.xml             # (same pattern)
examples/arithmetic/build.xml         # (same pattern, plus visitor example)
```

### Generated output (per grammar)

```
examples/<name>/rust-<name>/
├── Cargo.toml
├── lib.rs
├── tokens.rs
├── lexer.rs
├── parser.rs
├── ast.rs
├── error.rs
├── visitor.rs
├── pretty.rs
└── tests/
    └── parse_files.rs
```

---

## Public API Summary

### Parsing

```rust
// One-step parse
let ast = Parser::parse(input, source_name)?;

// Access root
let root: NodeId = ast.root();
```

### AST Navigation

```rust
// Node inspection
let node: &Node = ast.node(root);
let kind: &NodeKind = ast.kind(root);

// Traversal
for child in ast.children(root) { ... }
let parent: Option<NodeId> = ast.parent(some_node);
let next: Option<NodeId> = ast.next_sibling(some_node);

// Typed queries
let first_string = ast.first_child_of_type(root, TokenType::STRING_LITERAL);

// Iterators
for ancestor in ast.ancestors(node_id) { ... }
for descendant in ast.descendants(node_id) { ... }
```

### Source Location

```rust
let text: &str = ast.text(node_id);
let line: usize = ast.begin_line(node_id);
let col: usize = ast.begin_column(node_id);
let loc: String = ast.location(node_id);  // "file.json:3:15"
```

### Visitor Pattern

```rust
struct MyVisitor;
impl Visitor for MyVisitor {
    fn visit_json_object(&mut self, id: NodeId, ast: &Ast) {
        println!("Object at {}", ast.location(id));
        self.walk_children(id, ast);
    }
}

let mut v = MyVisitor;
v.visit(ast.root(), &ast);
```

### Error Handling

```rust
match Parser::parse(input, source_name) {
    Ok(ast) => { /* use ast */ }
    Err(e) => {
        // e.line, e.column, e.source_name
        // e.expected: Vec<TokenType>
        // e.found: Option<TokenType>
        // e.production_stack: Vec<String>
        eprintln!("{}", e);
        // → "file.json:3:15: expected one of '{', '[', STRING, but found ','"
        // → "  in: Value > Array"
    }
}
```

### AST Transformation (AstMapper)

```rust
// Node-local change: modify a node's data, preserve structure
struct NegateOps;
impl AstMapper for NegateOps {
    fn map_binary_expression(
        &mut self, source_id: NodeId, source: &Ast,
        mapped_children: &[NodeId], _builder: &mut AstBuilder,
    ) -> MappedNode {
        let kind = match source.kind(source_id) {
            NodeKind::BinaryExpression { op: BinaryOp::Add } =>
                NodeKind::BinaryExpression { op: BinaryOp::Sub },
            other => other.clone(),
        };
        MappedNode::Node { kind, children: mapped_children.to_vec() }
    }
}
let modified = NegateOps.map(&ast);

// Structural change: remove a node, keep its children
struct Unwrap;
impl AstMapper for Unwrap {
    fn map_parenthesized_expression(
        &mut self, _source_id: NodeId, _source: &Ast,
        mapped_children: &[NodeId], _builder: &mut AstBuilder,
    ) -> MappedNode {
        MappedNode::Splice(mapped_children.to_vec())
    }
}

// Delete a subtree
struct StripDebug;
impl AstMapper for StripDebug {
    fn map_debug_statement(
        &mut self, _source_id: NodeId, _source: &Ast,
        _mapped_children: &[NodeId], _builder: &mut AstBuilder,
    ) -> MappedNode {
        MappedNode::Remove
    }
}

// Chain transforms
let ast = Parser::parse(input, source_name)?;
let ast = StripDebug.map(&ast);
let ast = Unwrap.map(&ast);
let ast = NegateOps.map(&ast);
```

### Pretty Print

```rust
use my_parser::{Parser, DefaultPrettyPrinter, PrettyPrinter};

// Format with default rules, 80-column target width
let ast = Parser::parse(input, "input.json")?;
let formatted = DefaultPrettyPrinter.render(&ast, 80);

// Custom formatter: override specific node types
struct CompactArrays;
impl PrettyPrinter for CompactArrays {
    fn pp_array(&self, id: NodeId, ast: &Ast) -> Doc {
        let items: Vec<Doc> = /* collect non-delimiter children */;
        Doc::concat([
            Doc::text("["),
            Doc::intersperse(items, Doc::text(", ")),
            Doc::text("]"),
        ])
    }
}
let compact = CompactArrays.render(&ast, 120);

// Pretty-print after a transform
let ast = StripComments.map(&ast);
let output = DefaultPrettyPrinter.render(&ast, 80);
```

### Debug Display

```rust
println!("{}", ast.dump(ast.root()));
// Root
//   Array
//     Token([)
//     Value
//       Token(123)
//     Token(,)
//     Value
//       Token("hello")
//     Token(])
//   Token(EOF)
```

---

## CongoCC Feature Coverage

Summary of how every CongoCC grammar feature is handled by the Rust code generator:

| Feature | Status | Handled By |
|---|---|---|
| Token definitions (TOKEN, SKIP, MORE, UNPARSED) | Full | `lexer.rs.ctl` |
| Lexical states and transitions | Full | `lexer.rs.ctl` |
| Contextual tokens | Full | `lexer.rs.ctl` (`type_matches()`) |
| Token activation/deactivation | Full | `lexer.rs.ctl` + `parser.rs.ctl` |
| Regular expressions / NFA | Full | `lexer.rs.ctl` via `lexerData` |
| Unicode support | Full | Rust native Unicode strings |
| BNF productions (choices, sequences, repetition) | Full | `parser.rs.ctl` |
| SCAN/LOOKAHEAD (all variants) | Full | `parser.rs.ctl` scan routines |
| ASSERT / ENSURE | Full | `parser.rs.ctl` inline conditions |
| Up-to-here markers (`=>\|`, `=>\|\|`, `=>\|+N`) | Full | `parser.rs.ctl` scan budget reset |
| Look-behind (path-based context check) | Full | `parser.rs.ctl` call stack check |
| Negated scans | Full | `parser.rs.ctl` inverted result |
| Semantic actions (`{ code }`, `{% code %}`) | Full | `parser.rs.ctl` + `RustTranslator` |
| Production parameters and return types | Full | `parser.rs.ctl` + `RustTranslator` |
| Token assignment (`@var := ...`) | Full | `parser.rs.ctl` local variables |
| FAIL | Full | `parser.rs.ctl` → `Err(ParseError)` |
| Repetition cardinality | Full | `parser.rs.ctl` counter check |
| Tree building (`#Node`, `#void`, `#abstract`, `#interface`) | Full | `ast.rs.ctl` + `parser.rs.ctl` |
| Named children / child lists | Full | `ast.rs.ctl` side table |
| Conditional node annotations (`#Node(>expr)`) | Full | `parser.rs.ctl` + `RustTranslator` |
| INCLUDE (multi-file grammars) | Full | Transparent (resolved before code generation) |
| Preprocessor (`#if`, `#define`) | Full | Transparent (resolved before code generation) |
| Language-specific INJECT (`#if __rust__`) | Full | Auto-defined `__rust__` symbol |
| Code injection (simple patterns) | Full | `RustTranslator` mechanical translation |
| Code injection (complex Java patterns) | Graceful degradation | Commented-out Java + FIXME + warning |
| Fault-tolerant parsing (ATTEMPT/RECOVER) | Deferred (Step 8) | Checkpoint/restore design |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| INJECT code translation is incomplete for complex grammars | Some productions have FIXME comments | Graceful degradation: commented-out Java with warnings; users can provide Rust via `#if __rust__` blocks |
| NFA-based lexer generation is complex | Lexer may not handle all token specs | Follow the same `lexerData` iteration pattern as C# templates; test with simple grammars first |
| Generated Rust code may not compile | Blocks progress | Use `cargo check` in the build loop; fix templates incrementally |
| Performance regression vs. Java | Fails requirement 1 | Index-based design is inherently cache-friendly; benchmark early with JSON parser on large files |
| `rustfmt` not available on build machines | Generated code is hard to read | Generate readable code from templates; `rustfmt` is optional polish |
| Complex grammars (Java, C#) stress edge cases | Late-stage failures | Leave these for Step 6; most issues will surface and be fixed in earlier steps |
| Fault-tolerant parsing deferred | FAULT_TOLERANT grammars won't fully work until Step 8 | Non-fault-tolerant features work immediately; Step 8 is self-contained |
| Semantic actions with complex Java | Generated code needs manual fixes | Warnings at generation time + FIXME comments; `#if __rust__` escape hatch |
