# CongoCC Parse Debugger — Implementation Plan

**Branch:** `feature/delegated-repetition-cardinality` (plan only for now)  
**Related:** `docs/delegated-repetition-cardinality-plan.md` (delegation work on this branch is Java-only)  
**Status:** design / not implemented

---

## 1. Problem

Large CongoCC-generated parsers (e.g. P3/COBOL-scale grammars producing **hundreds of thousands of lines** of Java in a single `Parser` class) are painful to explore with **line-oriented JVM debuggers**:

- Generated classes can exceed the **64K line limit** of the JVM class debug table, so breakpoints and source mapping degrade or fail.
- Useful steps are not “Java line 238417” but **grammar-level** events: production entry, `SCAN` / predicate lookahead, choice resolution, token consume vs speculative peek.
- Lookahead failures are especially opaque: many small `check$…` / `scan$…` methods with little connection to the `.ccc` rule unless you already know the naming scheme.

A **semantic parse tracer** (production- and lookahead-aware) would let humans and agents answer: *where did we fail, in parse or lookahead, on which token, with which stacks and predicate state?*

This is **not** a replacement for debugging generated COBOL (see the P3/COBOL project). It targets **CongoCC-generated parser runtimes** (Java first).

---

## 2. Goals

| Goal | Notes |
|------|--------|
| **Observable parse state** | Stacks, mode (parse vs lookahead), token cursor, lookahead counters, optional cardinality state |
| **Event stream** | Filterable trace keyed by production / routine names and grammar locations |
| **Agent-friendly output** | JSON or JSONL; stable event schema; no GUI required |
| **Low overhead when off** | Default no-op; trace gated by grammar setting or runtime flag |
| **Java first** | Same phased discipline as delegation: stabilize API on Java templates before C#/Python/Rust |

### Non-goals (initial releases)

- Bytecode-level stepping inside generated methods  
- JDWP integration or IDE plugin (optional later via `congocc-lsp`)  
- Fixing giant single-class codegen layout (related but separate; see §8)  
- Polyglot port until Java event schema is stable  

---

## 3. What already exists

Generated Java parsers (from `ErrorHandling.java.ctl`, `Parser.java.ctl`, `LookaheadRoutines.java.ctl`) already maintain:

| State | Use |
|-------|-----|
| `parsingStack` / `lookaheadStack` | `NonTerminalCall` frames (production name, grammar file, line, column) |
| `currentlyParsedProduction` / `currentLookaheadProduction` | Active frame labels |
| `remainingLookahead`, `hitFailure`, `passedPredicate`, `lookaheadRoutineNesting` | Lookahead control |
| `currentLookaheadToken` vs `lastConsumedToken` | Speculative vs committed token stream |
| `dumpLookaheadCallStack(PrintStream)` | Ad hoc human dump |

C# templates expose an **observer `Log` / `LogInfo`** pattern; Python has **fault-tolerant** logging flags. There is no unified, filterable, grammar-correlated trace API.

---

## 4. Proposed architecture

Treat the feature as a **semantic tracer**, not a second debugger protocol.

```text
.ccc grammar  →  codegen (templates)  →  Parser with optional ParseTracer hooks
                                              ↓
                                    ParseTraceListener / snapshot API
                                              ↓
                         CLI, tests, agents (JSONL), future LSP UI
```

### 4.1 Snapshot API (Phase 0)

Read-only view of current parser state, e.g. `parser.traceSnapshot()` returning a structured object:

- Parse vs lookahead mode  
- Current token (image, type, offset, line, column)  
- `remainingLookahead`, `hitFailure`, `passedPredicate`, nesting depth  
- Parse and lookahead stacks (production names + grammar locations)  
- Optional: `RepetitionCardinality` tallies when `grammar.usingCardinality`  

No extra codegen beyond exposing existing fields through a small **stable façade** (generated method or `org.congocc.debug` helper accepting `Parser`).

### 4.2 Event trace (Phase 1)

Grammar setting (name TBD), e.g. `PARSE_TRACE` or runtime `parser.setTraceLevel(…)` / `parser.addTraceFilter(…)`.

Emit events at **template choke points** only (not per generated line):

| Event | When |
|-------|------|
| `enterProduction` / `exitProduction` | `pushOntoCallStack` / matching pop |
| `enterLookahead` / `exitLookahead` | Lookahead routine entry/exit (`check$…`, `scan$…`) |
| `scanToken` | `scanToken` helpers in lookahead |
| `predicateResult` | SCAN / semantic predicate return (incl. `hitFailure`) |
| `choice` | Which arm taken at a generated choice point |
| `consumeToken` | Committed token advance in parse mode |
| `fail` | `ParseException` or lookahead abort |

Each event carries: timestamp/sequence, event kind, production or routine name, grammar location (file, line), token summary, and selected counters.

**Volume control:** filters by production prefix, routine name pattern, token type, and “lookahead failures only”.

### 4.3 Agent ergonomics (Phase 2)

- **JSONL** stream to file or `OutputStream`  
- Ring buffer (last N events) for failure post-mortem  
- **Stop conditions:** e.g. stop on first lookahead `hitFailure` in production matching `Foo*`  
- Documented **event schema version** in this plan (appendix when implemented)  
- Small **CLI** or test harness: `parse --trace=lookahead-fail --file input`  

### 4.4 Step / replay (Phase 3, optional)

- **Step** = advance to next trace event (not next JVM instruction)  
- **Record & replay** trace for offline analysis (valuable for 400k-line parses you do not want to re-run under a debugger)  

### 4.5 LSP / UI (Phase 4, optional)

Wire event stream into `congocc-lsp`: timeline, stack tree, jump to `.ccc` location. Shares schema with agents; UI is not required for agent use.

---

## 5. Codegen impact

Hooks are injected at a **fixed set of template sites** (order of tens, not hundreds of thousands):

| Template | Hook sites |
|----------|------------|
| `ParserProductions.java.ctl` | Call-stack push/pop; choice branches; token consume; non-terminal call |
| `LookaheadRoutines.java.ctl` | Predicate/scan routine entry/exit; `scanToken`; returns using `hitFailure` |
| `ErrorHandling.java.ctl` | Optional unified `traceEvent(…)` helper |
| `Parser.java.ctl` | Trace listener field; snapshot assembly; settings from grammar |

When trace is disabled, hooks compile to **no-op** (boolean guard or empty inline) so production parses pay negligible cost.

---

## 6. Phases and effort (order-of-magnitude)

| Phase | Deliverable | Effort (one experienced dev) |
|-------|-------------|--------------------------------|
| **0** | Snapshot / dump JSON API on Java generated parser | ~1 week |
| **1** | `PARSE_TRACE` + events at template choke points; filters | 2–4 weeks |
| **2** | JSONL, ring buffer, stop-on-lookahead-fail, schema doc, sample CLI | +2–3 weeks |
| **3** | Event-step or record/replay | +1–2 months |
| **4** | LSP timeline (in `congocc-lsp`) | +2–4 months (partially parallel) |
| **Polyglot** | Port listener contract to C#/Python/Rust templates | ~×1 per language after Java stabilizes |

**Practical MVP** for large-grammar lookahead diagnosis (e.g. P3/COBOL Java parser): **Phases 0 + 1 + 2** → about **1–2 months** Java-focused.

**Full product** (step UI, replay, LSP, all targets): **6–12+ months** depending on polish.

---

## 7. Acceptance criteria (MVP = Phase 0–2, Java)

1. With trace off, generated parser behavior and performance are unchanged within noise (existing tests green).  
2. On a failing lookahead in `sandbox/cardinality` or a trimmed P3/COBOL repro, a trace filtered to `hitFailure` shows: routine name, grammar location, token, parse + lookahead stacks.  
3. An agent can consume JSONL without loading the 400k-line source file.  
4. `dumpLookaheadCallStack` remains available; snapshot API supersedes it for tooling.  

---

## 8. Related: giant single-class codegen

The **64K debug table** limit is a **class layout / codegen** issue (splitting `Parser`, productions, lookahead into separate compilation units). A semantic trace API **reduces reliance on JDWP** but does not by itself fix JVM limits. Track split-parser work separately; trace API should not assume a single `Parser.java` forever.

---

## 9. Implementation order (on this branch or follow-on)

1. Land this plan (doc only).  
2. After delegation Phase 0–3 Java work stabilizes, open a **parse-debugger** slice or branch from the same line.  
3. Phase 0 snapshot → Phase 1 events → Phase 2 JSONL/agents.  
4. Revisit LSP and polyglot once schema is versioned.  

---

## 10. Reference map (current master / templates)

| Area | Files |
|------|--------|
| Call / lookahead stacks | `src/templates/java/ErrorHandling.java.ctl` |
| Parser runtime state | `src/templates/java/Parser.java.ctl` |
| Parse codegen | `src/templates/java/ParserProductions.java.ctl` |
| Lookahead / SCAN | `src/templates/java/LookaheadRoutines.java.ctl` |
| Grammar settings pattern | `src/grammars/*.ccc` (`FAULT_TOLERANT`, etc.) |
| Settings plumbing | `src/java/org/congocc/app/AppSettings.java` |
| Future LSP consumer | `congocc-lsp` (separate repo) |
| Stress grammars | `examples/cics/`, P3/COBOL generated parsers (external) |

---

## 11. Event schema (placeholder)

To be filled when Phase 1 starts. Minimum fields per event:

```json
{
  "v": 1,
  "seq": 0,
  "kind": "enterLookahead",
  "production": "Statement",
  "routine": "check$Statement$1",
  "location": { "file": "Foo.ccc", "line": 42, "column": 10 },
  "mode": "lookahead",
  "token": { "type": "IDENTIFIER", "image": "foo", "line": 10, "column": 5 },
  "remainingLookahead": 3,
  "hitFailure": false
}
```

Appendix A (future): full enum of `kind`, filter grammar, and versioning rules.
