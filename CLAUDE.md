# CongoCC Parser Generator - Developer Guide

## Overview

CongoCC is a recursive descent parser generator that reads grammar specification files (`.ccc`) and generates complete lexer, parser, and AST infrastructure. It currently targets **Java**, **Python**, and **C#**. The tool is self-bootstrapping: it uses its own generated parser to parse `.ccc` grammar files.

- **Entry point:** `java -jar congocc.jar [options] grammarfile.ccc`
- **Requires:** Java 8+, Apache Ant (for building)
- **License:** Creative Commons Attribution-NonCommercial-ShareAlike 4.0

## Quick Reference

```bash
# Build the project (generates parsers, compiles, creates jar)
ant jar

# Run all tests (requires dotnet for C# tests)
ant test

# Run Java-only tests (no dotnet needed)
ant test-java

# Generate a parser from a grammar file
java -jar congocc.jar -n examples/json/JSON.ccc

# Generate for a specific language
java -jar congocc.jar -n -lang python examples/json/JSON.ccc
java -jar congocc.jar -n -lang csharp examples/json/JSON.ccc

# Generate to a specific output directory
java -jar congocc.jar -n -d /path/to/output examples/json/JSON.ccc

# Full bootstrap test (rebuild jar, test, rebuild again)
ant full-test
```

### CLI Flags

| Flag | Description |
|------|-------------|
| `-d <dir>` | Output directory for generated files (relative to grammar or absolute) |
| `-lang <lang>` | Target language: `java` (default), `python`, `csharp` |
| `-jdkN` | Target JDK version (8-25, Java only) |
| `-n` | Suppress check for newer version |
| `-p <symbols>` | Preprocessor symbols, comma-separated (e.g., `-p debug,strict`) |
| `-q` | Quiet output |

## Repository Structure

```
congo-rust/
├── bin/
│   └── congocc.jar              # Bootstrap JAR (used to build the project itself)
├── build/                        # Build artifacts (compiled classes, generated code)
│   ├── generated-java/           # Generated bootstrap parser source
│   ├── org/                      # Compiled .class files
│   └── templates/                # CTL templates (copied here during build)
│       ├── java/                 #   20 Java templates
│       ├── python/               #   10 Python templates
│       ├── csharp/               #   10 C# templates
│       └── rust/                 #   9 Rust templates (from prior prototype, not in src/)
├── build.xml                     # Ant build system
├── congocc.jar                   # Built JAR (output of `ant jar`)
├── docs/                         # Project documentation
├── examples/                     # Example grammars and generated parsers
│   ├── arithmetic/               #   Simple 4-function arithmetic (2 grammars)
│   ├── cics/                     #   IBM CICS command parsing
│   ├── congo-templates/          #   CTL template language grammar
│   ├── csharp/                   #   C# language grammar
│   ├── java/                     #   Java language grammar
│   ├── json/                     #   JSON and JSONC grammars
│   ├── lua/                      #   Lua 5.4 grammar
│   ├── php/                      #   PHP grammar (incomplete)
│   ├── preprocessor/             #   C# preprocessor grammar
│   └── python/                   #   Python language grammar
├── sandbox/
│   └── cardinality/              # Experimental repetition cardinality assertions
├── src/
│   ├── grammars/                 # CongoCC's own grammar files (for bootstrap)
│   │   ├── CongoCC.ccc           #   Main grammar (47KB)
│   │   ├── Lexical.inc.ccc       #   Shared lexical definitions
│   │   ├── JavaInternal.ccc      #   Java-specific grammar rules
│   │   ├── CSharpInternal.ccc    #   C#-specific grammar rules
│   │   └── PythonInternal.ccc    #   Python-specific grammar rules
│   ├── java/org/congocc/         # Java source for the generator
│   │   ├── app/                  #   Application entry point and settings
│   │   ├── codegen/              #   Code generation engine
│   │   ├── core/                 #   Grammar model and NFA
│   │   └── templates/            #   CTL template engine implementation
│   └── templates/                # CTL template source files
│       ├── java/                 #   Java code generation templates
│       ├── python/               #   Python code generation templates
│       └── csharp/               #   C# code generation templates
└── tests/                        # Additional test files
```

## Architecture

### Code Generation Pipeline

```
Grammar File (.ccc)
        │
        ▼
   Grammar.parse()           ← Parses .ccc into BNFProduction / RegularExpression trees
        │
        ▼
   Grammar.generateLexer()   ← Builds NFA from token specifications
        │
        ▼
   FilesGenerator.generateAll()  ← Renders CTL templates to generate source files
        │
        ├─── Java path:    Token, Lexer, Parser, Node, AST nodes, etc.
        ├─── Python path:  __init__.py, utils.py, tokens.py, lexer.py, parser.py
        └─── C# path:      Utils.cs, Tokens.cs, Lexer.cs, Parser.cs, project.csproj
```

### Key Java Packages

#### `org.congocc.app` (3 files) — Application Layer
- **Main.java** — CLI entry point; argument parsing, version checking, invokes `mainProgram()`
- **AppSettings.java** — Configuration from grammar file (output dirs, packages, JDK target, preprocessor symbols)
- **Errors.java** — Error and warning collection/reporting

#### `org.congocc.core` (16 files) — Grammar Model
- **Grammar.java** (36KB) — Root object holding all grammar data; manages productions, lexical states, preprocessor symbols
- **BNFProduction.java** — A parser production rule
- **Expansion.java** (17KB) — Expansion nodes in the grammar tree
- **ExpansionSequence.java** (12KB) — Sequence of expansions
- **NonTerminal.java** — Reference to another production
- **RegularExpression.java** — Base class for token definitions
- **LexerData.java** (14KB) — Manages lexical data across lexical states
- **TokenSet.java** — Set of token types

#### `org.congocc.core.nfa` (4 files) — Lexical NFA
- **NfaBuilder.java** — Constructs NFA from token specifications
- **NfaState.java** — Individual NFA state
- **LexicalStateData.java** (14KB) — NFA data for a specific lexical state
- **CompositeStateSet.java** — Manages composite NFA states

#### `org.congocc.codegen` (4 files) — Code Generation Core
- **FilesGenerator.java** (21KB) — Main generator; initializes template engine, orchestrates all file generation
- **TemplateGlobals.java** (20KB) — Methods/data exposed to CTL templates (`grammar`, `globals`, `settings`, `lexerData`)
- **Translator.java** (69KB) — Base translator for cross-language code generation
- **Sequencer.java** — Topological ordering of class dependencies

#### Language-Specific Code Generation
- **`codegen/java/`** (4 files) — CodeInjector, JavaCodeUtils, JavaFormatter, Reaper
- **`codegen/python/`** (4 files) — PythonTranslator, JToAltPyTranslator, PyFormatter, Reaper
- **`codegen/csharp/`** (3 files) — CSharpTranslator, CSharpFormatter, Reaper

#### `org.congocc.templates` (52 files) — CTL Template Engine
The CongoCC Template Language (CTL) is a FreeMarker-derived template engine used to generate output code. Key features:
- Shared variables: `grammar`, `globals`, `settings`, `lexerData`, `generated_by`
- Auto-imports for Java: `CommonUtils.java.ctl`
- Macros, conditionals, loops, variable declarations
- Built-in functions for strings, sequences, numbers, type checking

### Template Files (CTL)

Templates use the `.ctl` extension and the CongoCC Template Language:

| Language | Key Templates | Purpose |
|----------|--------------|---------|
| **Java** | `Parser.java.ctl`, `ParserProductions.java.ctl`, `Lexer.java.ctl`, `Token.java.ctl`, `Node.java.ctl`, `BaseNode.java.ctl` | Full parser infrastructure |
| **Python** | `parser.py.ctl`, `lexer.py.ctl`, `tokens.py.ctl`, `utils.py.ctl`, `__init__.py.ctl` | Complete Python parser |
| **C#** | `Parser.cs.ctl`, `Lexer.cs.ctl`, `Tokens.cs.ctl`, `Utils.cs.ctl`, `project.csproj.ctl` | Complete C# parser |

### CTL Template Syntax Quick Reference

```
[#-- comment --]                          Comment
[#if condition]...[#elseif]...[#else]...[/#if]   Conditional
[#list items as item]...[/#list]          Iteration
[#var x = expr]                           Variable declaration (block-scoped)
[#set x = expr]                           Variable reassignment
[#macro Name param1 param2]...[/#macro]   Macro definition
[@MacroName arg1, arg2 /]                 Macro invocation (comma-separated args)
${expression}                             Expression interpolation
expression?has_content                    Null/empty check
expression?string                         Convert to string
globals::methodName()                     Method call (double colon, not dot)
```

## Grammar File Syntax (.ccc)

### Settings Block (top of file)

```
PARSER_PACKAGE="org.parsers.json";        // Java package for generated parser
NODE_PACKAGE="org.parsers.json.ast";      // Java package for AST nodes
DEFAULT_LEXICAL_STATE=JSON;               // Initial lexical state
TEST_PRODUCTION=Root;                     // Production to test with parse harness
TEST_EXTENSION=json;                      // File extension for test files
ENSURE_FINAL_EOL;                         // Ensure files end with newline
JAVA_UNICODE_ESCAPE;                      // Enable Java unicode escape processing
USES_PREPROCESSOR;                        // Enable grammar preprocessor
FAULT_TOLERANT;                           // Enable fault-tolerant parsing
```

### Lexical Specifications

```
// Skip whitespace
SKIP : <WHITESPACE : (" "| "\t"| "\n"| "\r")+>;

// Token with node annotation
TOKEN #Delimiter :
    <COLON : ':'>
    |
    <COMMA : ','>
;

// Token with superclass annotation
TOKEN #Literal :
    <TRUE: 'true'> #BooleanLiteral
    |
    <STRING_LITERAL : '"' (<REGULAR_CHAR>|<ESCAPE>)* '"'> #StringLiteral
;

// Private token (used in other definitions only)
TOKEN : <#ESCAPE : '\\' (['\\', '"', '/'])>;

// Contextual token
CONTEXTUAL TOKEN : <IDENTIFIER : ...>;

// Lexical state-specific token
<IN_BLOCK> TOKEN : <BLOCK_CONTENT : (~[])+ <END_BLOCK>>;
```

### Parser Productions (BNF Rules)

```
// Simple production
Root : Value <EOF>;

// Production with tree annotation
Value #JSONValue :
    <TRUE> | <FALSE> | <NULL>
    | <STRING_LITERAL>
    | Array | JSONObject
;

// Zero-or-more repetition
Array : "[" [ Value ("," Value)* ] "]" ;

// Lookahead/scan
SomeProduction :
    SCAN <IDENTIFIER> "=" => Foo
    |
    Bar
;

// Code injection into generated parser
INJECT PARSER_CLASS : {
    // Java code injected into the parser class
}
```

### Key Grammar Features

- **SCAN/LOOKAHEAD**: Contextual predicates for resolving ambiguity
- **ASSERT/ENSURE**: Semantic assertions in grammar rules
- **INJECT**: Code injection into generated classes (Token, Lexer, Parser, AST nodes)
- **INCLUDE**: Multi-file grammars via includes
- **#preprocessor**: Conditional grammar sections (`#if`, `#define`)
- **Up-to-here marker** (`=>|`): Clean lookahead boundary syntax
- **Fault-tolerant parsing**: Experimental error recovery with `ATTEMPT`/`RECOVER`
- **Repetition cardinality**: Constrain repetition counts with `&m:n&` notation

## Build System

The Ant build (`build.xml`) provides these key targets:

| Target | Description |
|--------|-------------|
| `jar` | Build congocc.jar (generates parsers, compiles, packages) |
| `compile` | Compile Java source and copy templates |
| `test` | Full test suite (Java, Python, C# for all examples) |
| `test-java` | Java-only tests (no dotnet required) |
| `full-test` | Bootstrap cycle: build, test, rebuild with new jar, test again |
| `update-bootstrap` | Update `bin/congocc.jar` with current build |
| `clean` | Remove build artifacts |
| `build-parsers` | Regenerate all bootstrap parsers in parallel |

### Bootstrap Process

CongoCC is self-hosting. The build cycle:
1. `bin/congocc.jar` (bootstrap) generates the CongoCC parser from `src/grammars/CongoCC.ccc`
2. Generated Java source is compiled along with `src/java/` source
3. A new `congocc.jar` is created
4. `full-test` validates by rebuilding with the new jar and re-running tests

## Adding a New Target Language

To add support for a new language (e.g., Rust), the following components are needed:

1. **CodeLang enum** — Add language to `CodeLang` in `build/generated-java/org/congocc/parser/Node.java` (currently: `JAVA, PYTHON, CSHARP`)
2. **FilesGenerator.generateAll()** — Add a new `case` in the switch to specify which files to generate
3. **Translator subclass** — Create a language-specific translator (like `PythonTranslator`, `CSharpTranslator`)
4. **CTL templates** — Create template files in `src/templates/<lang>/` for each generated source file
5. **CLI support** — Add language name to `otherSupportedLanguages` in `Main.java`
6. **Output formatting** — Add output method in `FilesGenerator` (like `outputPythonFile`, `outputCSharpFile`)
7. **Example grammars** — Create example directories with build.xml and test infrastructure

### Existing Rust Artifacts (from prior prototype, in build/ only)

The following exist in the `build/` directory from a prior prototype but are **not** in `src/`:
- **Templates:** `build/templates/rust/` — 9 CTL templates (parser, lexer, arena, tokens, lib, error, visitor, Cargo.toml, tests/basic)
- **Compiled classes:** `build/org/congocc/codegen/rust/RustProductionInfo.class`, `RustTranslator.class` (no source in src/)

## Example Grammars

Each example has its own `build.xml` with targets for each language:

| Example | Grammar Files | Tests | Notes |
|---------|--------------|-------|-------|
| **json** | JSON.ccc, JSONC.ccc | 5 files | Simplest grammar; good starting point |
| **lua** | Lua.ccc, LuaLexer.ccc | 39 files | Complete Lua 5.4 parser |
| **java** | Java.ccc + identifier defs | 14 files | Used internally by CongoCC |
| **csharp** | CSharp.ccc + lexer/preprocessor | 164 files | Most comprehensive test suite |
| **python** | Python.ccc + lexer/identifier | 9 files | Python language parser |
| **preprocessor** | Preprocessor.ccc | 45 files | C# preprocessor directives |
| **cics** | Cics.ccc | 6 files | IBM CICS commands |
| **arithmetic** | Arithmetic1.ccc, Arithmetic2.ccc | — | Simple expression parsing |
| **congo-templates** | CTL.ccc | — | CongoCC template language |
| **php** | PHP.ccc, PHPLexer.ccc | — | Incomplete/work in progress |

## Resources

- **Discussion forum:** https://discuss.congocc.org/
- **Wiki:** https://bookstack.congocc.org
- **GitHub:** https://github.com/congo-cc/congo-parser-generator
- **Downloads:** https://parsers.org/download/congocc.jar
