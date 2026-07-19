# Cardinality sandbox — notes and potential futures

Companion to `RepetitionCardinality.md` (normative feature description). This file records implementation status, sandbox ops, and open problems that are **not** part of the core RCA semantics.

## Polyglot status

| Target | Local RCA | Delegated RCA | CardTests (`ant test-*`) |
|--------|-----------|---------------|--------------------------|
| Java | yes | yes | required |
| C# | yes | yes | required |
| Python | yes | yes | required |
| Rust | deferred | deferred | — |

Codegen: `src/templates/{java,csharp,python}/`. Grammar semantic actions written as Java `System.out.println` are rewritten for C# (`Console.WriteLine`) and Python (`print`).

## Sandbox build

`ant clean test-all` regenerates Java, Python, and C# from `CardTests.ccc`, compiles, and runs `testfiles/` on each. `ant test-checker-negative` covers orphan / `ZeroOrOne` / telescoping / delegation-consistency errors under `checker-negative/`.

- CardTests `allows` uses a single `EnumSet.of(...)` arity so Python (no overloads) matches Java/C#.
- Parse harnesses report success/failure counts but exit 0 when some inputs are expected to fail (same pattern as the JSON example).

## CICS and large choice groups (Python indent)

CPython rejects modules whose nested block depth exceeds ~100. A flat RCA choice with ~100+ alternatives (CICS `Assign`) generated a lookahead chain deeper than that limit.

**Mitigation:** split into `AssignOptA` / `AssignOptB` and iterate `( AssignOptA | AssignOptB )+`. Orphan RCAs bind to `Assign`’s loop via delegated cardinality. After the split, max generated indent is ~60 and CICS `test-python` runs with the other languages.

## Potential future development

- **Multi-hop delegation** (`Parent → Middle → Leaf`): stack already exists; checker and discovery still one hop.
- **FT recovery + cardinality:** `BuildRecoverRoutines` should respect RCA tallies; improve non-cardinality sync using FIRST∪FOLLOW with mandatory progress except on FOLLOW (see `ParserProductions.java.ctl` REVISIT near recovery catch, and issue discussion around `!` / panic-mode FOLLOW∩FIRST collisions).
- **No FT rewrite during lookahead:** INVALID→unparsed under speculative SCAN poisons the token cache across lexical-state restores (issue #203). Prefer fail-the-scan; apply FT only on the committed parse path, with careful uncache on lexical-state restore.
- **Rust:** local and delegated RCA templates still deferred.

## Lexical-state / FT footnote (issue #203)

`CardTests` uses `"[" LEXICAL_STATE JAVA (Modifiers) "]"` with `<DEFAULT,JAVA> TOKEN: <END_TOKEN: "]" >` as a **workaround** for [issue #203](https://github.com/congo-cc/congo-parser-generator/issues/203).

**What goes wrong (FT only):** if `]` is not valid in `JAVA`, Modifiers’ SCAN probes past the closing bracket while still in `JAVA`, gets `INVALID`, and FT `nextToken` marks it **unparsed** — skipping the outer delimiter. Modifiers continues into the next line; failure appears inside `Modifiers`, not at `"]"`.

**Repro:** `#define FT` and use `<DEFAULT> TOKEN: <END_TOKEN: "]" >` (omit `JAVA`). Non-FT still passes; FT fails on bracketed modifier cases in `testfiles/small_tests.txt`.

**Why `<JAVA>` alone can still “work”:** a cached `END_TOKEN` from the JAVA probe may be reused after restore without retokenizing, so INVALID→unparsed never fires.
