# Delegated Repetition Cardinality — Implementation Plan

**Branch:** `feature/delegated-repetition-cardinality` (from `master`)  
**Baseline behavior:** current `master` (post cardinality merge)  
**Original feature:** commit `82d331d` (repetition cardinality); small adjustments since  
**Status:** Phases 0–4 complete (Java), including CICS `CommonOptions` via multi-parent bias. **C# and Python delegated-cardinality templates are ported** on this branch; Rust remains deferred. CICS Python tests run after splitting `Assign` into `AssignOptsA`/`AssignOptsB` (CPython indent-depth limit).  
**See also:** `sandbox/cardinality/RepetitionCardinality.md` (feature docs + #203 lexical/FT note); `docs/parse-debugger-plan.md` (semantic parse trace API — plan only).

---

## 1. Problem

Repetition cardinality assertions (RCAs, `&m:n&`) attach to the **nearest enclosing `ZeroOrMore` / `OneOrMore`** in the **same** production’s expansion tree (`ExpansionSequence.isOuterCardinalityScope` → `IteratingExpansion`). RCAs inside a **callee** production are not under the caller’s iterator in the AST, so authors must either:

- duplicate the loop and constraints at every call site, or  
- pull the constrained choices up into the caller production.

That is especially painful when many commands share the same constrained option group.

### Today (workaround)

```text
Parent : Child ;

Child  : ( & <OPT1> ... | & <OPT2> ... )+ ;   // loop + RCAs must live together
```

Each command production that needs the same options repeats that loop (or inlines an equivalent choice group).

### Desired (delegated cardinality)

```text
Parent : ( Child )+ ;   // or Child+ at call site — iterator lives in caller

Child  : & <OPT1> ... | & <OPT2> ... ;       // RCAs bind to Parent’s loop
```

**Scope rule (least surprise):** RCAs in `Child` attach only to a **direct parent** production’s iterator that invokes `Child` — **not** unbounded upward propagation. (Implementation should still use a **stack** so multi-level delegation can be enabled later without redesign; see §3.)

---

## 2. Exemplar: CICS `CommonOptions`

In `examples/cics/Cics.ccc`, almost every command repeats the same constrained trio:

```text
(
    & @noHandle :=? <NOHANDLE>
    |
    & <RESP> "(" Name #ResponseName(1) ")"
    |
    & <RESP2> "(" Name #Response2Name(1) ")"
) #CommonOptions
```

often as `( … )*` or `( … )+` after command-specific options.

`#CommonOptions` is today only a **tree tag** on an inline choice group, not a production — so the loop and RCAs are copy-pasted per command.

**Target end state (illustrative):**

```text
CommonOptions :
    & @noHandle :=? <NOHANDLE>
    | & <RESP> "(" Name #ResponseName(1) ")"
    | & <RESP2> "(" Name #Response2Name(1) ")"
;

SomeCommand :
    <SOMECMD> …specific… ( CommonOptions )* =>||
;
```

With delegation, `CommonOptions` holds the RCAs; each command’s `( CommonOptions )*` supplies the iterator and tally scope. One definition, many call sites — the main motivation for this feature.

**Validation:** after implementation, optionally refactor a subset of CICS to use a real `CommonOptions` production and ensure `examples/cics` tests still pass.

---

## 3. Design decisions

### 3.1 Parse path: delegated cardinality **stack**

Do **not** add a synthetic production parameter (conflicts with arbitrary user `FormalParameters` / `InvocationArguments`).

Use a parser field, implemented as a **stack** from the start:

```text
Deque<RepetitionCardinality> delegatedCardinalityStack;   // or equivalent
```

- Before calling a delegatee non-terminal from an iterating expansion, **push** that loop’s `RepetitionCardinality`.
- After the call, **pop**.
- Child production RCAs use **stack top** (if non-empty) for `choose(index)` / min checks.

**Phase 1 behavior:** only **one** level — checker ensures delegatee is called only from linked parent loops; stack depth is 0 or 1 in practice.

**Future option:** relax checker to allow chained delegation (Parent → Middle → Leaf); stack already supports it.

### 3.2 Lookahead / SCAN path: optional parameter

Scan/lookahead routines are generator-controlled. Extend production lookahead methods (and `ScanCodeNonTerminal` / `CheckExpansion`) with an optional trailing `RepetitionCardinality` argument when the production contains delegated RCAs or is a known delegatee — same pattern as expansion-level `scanRoutineName(..., cardinalities)` today. Addresses the existing TODO in `LookaheadRoutines.java.ctl` (~388) for production-level cardinality.

### 3.3 Implicit binding (phase 1)

No new grammar syntax required. Static analysis links:

- iterator `L` in production `P`, and  
- non-terminal `NT` → production `C` in `L`’s body (stop at nested iterators inside `L`), and  
- “orphan” RCAs in `C` (no `IteratingExpansion` ancestor in `C`).

Optional explicit opt-in/out (`#local` / `#delegates`) can be added later if needed.

---

## 4. Phase 0 — CardinalityChecker hardening

**Prerequisite** for delegated cardinality and for trustworthy diagnostics on existing grammars. Harden `Grammar.CardinalityChecker` and `ExpansionSequence` scope logic before adding cross-production binding.

### 4.0.1 Current checker (`Grammar.java` ~521–600)

| Check | Severity | Assessment |
|-------|----------|------------|
| `constraint[1] == 0` | Warning | **Keep** — almost always erroneous |
| `constraint[0] > constraint[1]` on one RCA | Error | **Keep** |
| `rangeStack` non-empty inside `ATTEMPT` | Error | **Keep** until recovery stashes/restores cardinality |
| ZOM + folded `repetitionRange[0] > 0` | Info | **Revisit** — folding does not match runtime tallies (see below) |
| `isCardinalityContainer` && !`IteratingExpansion` | Error | **Dead code** — container requires `IteratingExpansion` (`CongoCC.ccc` ~987–990); `ZeroOrOne` does not implement it |
| TODO: telescoped constraints in one sequence | — | **Not implemented** |

The checker pushes `[0, MAX]` on each cardinality container and, for every constrained `ExpansionSequence`, folds all RCA `[min,max]` into that single slot via `max` of mins / `min` of maxes. **Runtime** uses one tally per RCA (`RepetitionCardinality` in `Parser.java.ctl`). The folded range is only consumed for the ZOM “min > 0” info message, so that message can be misleading (e.g. `( &1:2& A \| & B )*` in a `*` loop).

### 4.0.2 Bugs to fix first

**1. `isInScopeConstraint` (`ExpansionSequence.java` ~49–50)**

When no `ZOM`/`OOM` ancestor exists, `getCardinalitiesContainer()` is `null` and the assertion’s iterating ancestor is also `null`, so `null == null` treats orphan RCAs as in scope. `CardinalityChecker.visit(ExpansionSequence)` then calls `rangeStack.peek()` on an **empty** stack and fails (~596–598) with a production dump instead of a clear error.

**Fix:** in-scope only if the cardinality container is non-null and equals the assertion’s nearest `IteratingExpansion` wrapper (and, after delegation, the linked parent container — see Phase 1).

**2. Orphan RCAs**

Any RCA not under a `ZOM`/`OOM` container (including RCAs in a callee production before delegation exists) should produce an explicit **error**, not a crash.

**3. RCAs under `(...)?` / `ZeroOrOne`**

`ZeroOrOne` is not an `IteratingExpansion` (~1558–1565 in `CongoCC.ccc`); `?` is never a cardinality container. The FIXME at `Grammar.java` ~552 (“warn on constraints within ZeroOrOne — below does not work”) is accurate. Emit a clear **error** (doc: RCAs belong in `*`/`+` only).

**4. Remove or repurpose dead error** at ~551–554.

### 4.0.3 Checker improvements (warnings / errors)

| Situation | Level | Notes |
|-----------|-------|-------|
| RCA not under any `*`/`+` container | **Error** | After scope fix; includes “RCAs only in child production, no local iterator” until delegation |
| RCA under `(...)?` only | **Error** | |
| Multiple RCAs on **one** `ExpansionSequence` with combined min > combined max | **Warning** | Implements the TODO at ~579 for a single sequence |
| Production contains RCAs but no local `isCardinalityContainer` | **Info** | “May need a `*`/`+` here or delegated cardinality (e.g. `CommonOptions` + `( CommonOptions )*`)” |
| ZOM + loop-length RCA semantics | **Info** (optional) | Only when an RCA clearly constrains **loop iteration count** (e.g. `( (A\|B) &5&)+`), not per-branch `&` tallies — do not use broken global folding |
| `&` on a sequence with no following expansion unit | **Warning** | Likely typo / no-op |
| ATTEMPT / RECOVER with active cardinality | **Error** | Keep current rule |

Defer until after delegation linking: child with orphan RCAs called from non-iterating sites; sum of per-branch minimums vs iteration count.

### 4.0.4 Tests

Add negative cases to `sandbox/cardinality/CardTests.ccc` (or a small companion grammar) expecting clean **errors/warnings**, not checker crashes:

- RCA in a production with no `*`/`+`
- RCA inside `(...)?`
- Two RCAs on one sequence with impossible combined range

**Files:** `Grammar.java`, `ExpansionSequence.java`; optional note in `RepetitionCardinality.md`.

---

## 5. Phase 1 — Grammar model & static analysis

### 5.1 Discovery

Extend `Grammar.CardinalityChecker` (or a dedicated pass) to:

1. For each `IteratingExpansion` `L` in production `P`, find delegatee non-terminals in `L`’s nested expansion (see §5.2).
2. For each delegatee production `C`, collect RCAs not enclosed by an iterator in `C`.
3. Record link `(L, NT, C)` and merge constraint metadata into `L`’s container.

### 5.2 Validation rules

| Rule | Action |
|------|--------|
| **Single hop (phase 1)** | Orphan RCAs in `C` bind only to **immediate** caller’s iterator, not grandparent |
| **Call-site consistency** | If `C` is called both inside a delegating loop and elsewhere → **error** |
| **Inner loop wins** | RCAs under `C`’s own `ZOM`/`OOM` bind there; only **non-enclosed** RCAs delegate |
| **Ambiguous iterators** | Same `C` from two sibling iterators in `P` without clear binding → **error** |
| **ATTEMPT / RECOVER** | Keep current ban on RCAs inside attempt blocks; delegation through attempt deferred |
| **Telescoping** | Per-RCA tallies on `L`; do **not** reuse Phase 0’s broken global fold across unrelated RCAs |

### 5.3 Index & constraint table

Today `ExpansionWithParentheses.close()` assigns `assertionIndex` from `getCardinalityAssertions()` on the loop only (`CongoCC.ccc`).

**Change:** when closing loop `L`, build constraints from:

1. In-loop sequences (current order), then  
2. Delegated sequences from each linked `C`, in stable discovery order (NT walk order in `L`’s body).

Parent loop `L` owns `int[][] choiceCardinalities` and global assertion indices for all delegated RCAs.

**Implemented:** `Grammar.discoverDelegatedCardinality()` after `checkReferences()`, populates `delegatedAssertions` on `L`, then `refreshAssertionIndices()`. Phase 2: `isCardinalityContainer()` includes delegated RCAs so the parent loop allocates `RepetitionCardinality`; parse codegen pushes/pops the delegated stack around delegatee NonTerminal calls.

### 5.4 Scope predicates

Update `ExpansionSequence.java`:

- `isInScopeConstraint` — RCA in delegatee `C` is in scope iff linked to `L` and not under an inner iterator in `C`.
- `getCardinalitiesContainer()` — for delegated RCAs, returns **`L`** (may cross production boundary in the model).
- Clarify comment on line 54 (“can erroneously extend beyond the parent production”) for the intentional delegated case.

**Core files:** `Grammar.java`, `ExpansionSequence.java`, `CongoCC.ccc` (`ExpansionWithParentheses` inject), possibly `NonTerminal.java`, `BNFProduction.java`.

---

## 6. Phase 2 — Parse codegen (**Java only** on this branch)

| Location | Change |
|----------|--------|
| `Parser.java.ctl` | `delegatedCardinalityStack` (+ push/pop helpers) |
| `BuildCodeOneOrMore` / `BuildCodeZeroOrMore` | Unchanged local `cardinalitiesN` allocation |
| `BuildCodeNonTerminal` | If delegatee: push `cardinalitiesN`, call child, pop |
| `BuildAssertionCode` | Delegated RCAs use stack top |
| `ErrorHandling.java.ctl` | Eventually stash/restore stack in `ATTEMPT` if delegation allowed there |

**Templates (this phase):** `ParserProductions.java.ctl`, `Parser.java.ctl`.  
**Deferred:** `ParserProductions.inc.ctl`, `parser_productions.inc.ctl`, `Parser.cs.ctl`, `parser.py.ctl`.

---

## 7. Phase 3 — Lookahead / SCAN codegen (**Java only** on this branch)

| Location | Change |
|----------|--------|
| `BuildProductionLookaheadMethod` | Optional `RepetitionCardinality cardinalities` when needed |
| `ScanCodeNonTerminal` | Pass active `cardinalitiesVar` into callee lookahead |
| `BuildPredicateRoutine` / `BuildScanRoutine` | Delegatee expansions use passed-in cardinalities |
| `ExpansionCondition` / `ScanAheadCondition` | Thread cardinalities into production-level checks |

Reuse existing `RepetitionCardinality` commit/provisional stack so parse and lookahead stay aligned.

**Templates (this phase):** `LookaheadRoutines.java.ctl` only.  
**Deferred:** `LookaheadRoutines.inc.ctl`, `lookahead_routines.inc.ctl` (see polyglot backup branch).

---

## 8. Phase 4 — Tests & docs

| Item | Action | Status |
|------|--------|--------|
| `sandbox/cardinality/CardTests.ccc` | Pure Parent/Child, mixed local+delegated, inner loop vs delegate | **Done** |
| Checker negatives | Orphan / `?` / telescoping / inconsistent / ambiguous | **Done** (Phase 0–1) |
| `sandbox/cardinality/RepetitionCardinality.md` | Document delegation, polyglot, CICS split, #203 note | **Done** |
| `examples/cics` | `CommonOptions` production + multi-parent bias; Assign split for Python | **Done** |
| CI | sandbox `test-java` / `test-python` / `test-csharp` + `test-checker-negative`; CICS `test-all` includes Python | Required on this branch |

---

## 9. Implementation order

1. **Phase 0** — fix scope predicate; orphan/`?` errors; per-sequence telescoping warning; revisit ZOM info; sandbox negative grammar tests (**Java checker / grammar model**) — **done**  
2. Phase 1 — delegation analysis + checker extensions — **done**  
3. Phase 2 — Java parse templates + sandbox Java parse tests — **done**  
4. Phase 3 — Java lookahead + sandbox (parse + lookahead paths) — **done**  
5. Phase 4 — tests & docs (this section) — **done**  
6. **Later phase** — port C#, Python — **done** on this branch; Rust still deferred  
7. CICS `CommonOptions` refactor + Assign split for Python indent — **done**

---

## 10. Reference map (82d331d / master)

| Area | Files |
|------|--------|
| Grammar syntax / inject | `src/grammars/CongoCC.ccc` |
| Scope & container | `src/java/org/congocc/core/ExpansionSequence.java` |
| Sanity checker | `src/java/org/congocc/core/Grammar.java` (`CardinalityChecker`) |
| Runtime tally | `src/templates/java/Parser.java.ctl` (`RepetitionCardinality`) |
| Parse loops / assertions | `src/templates/java/ParserProductions.java.ctl` |
| Lookahead / scan | `src/templates/java/LookaheadRoutines.java.ctl` |
| Cardinality literals | `src/templates/java/CommonUtils.java.ctl` (`BuildCardinalities`) |
| Oracle tests | `sandbox/cardinality/` |
| Real-world stress | `examples/cics/Cics.ccc` |

---

## 11. Non-goals (this branch)

- Multi-hop / unlimited upward delegation (one hop only; stack+bias ready for multi-hop — see §12)  
- RCAs inside `ATTEMPT`/`RECOVER` via delegation  
- New surface syntax unless implicit binding proves insufficient  
- LSP diagnostic messages (follow-up in `congocc-lsp` if desired)

---

## 12. Multi-parent bias (implemented) and multi-hop (future)

### Multi-parent (done)

The same callee (e.g. CICS `CommonOptions`) may be invoked from many distinct parent loops with different local RCA counts. Each delegated assertion stores a **callee-relative** `assertionIndex` (`0..n-1`). Each parent loop appends that callee’s orphan constraints as a contiguous block after its locals and records a **bias** (block start). Runtime stack frames are:

```text
DelegatedCardinalityBinding { RepetitionCardinality cardinalities; int indexBias; }
```

At an RCA in the callee:

```text
choose(indexBias + assertionIndex, …)
```

### Multi-hop (future)

`Parent → Middle → Leaf`, possibly with `Middle : &M1 | Leaf1 | Leaf2 | &M2` where leaves contribute different orphan widths to the **root** array. Same binding frame: intermediate calls push an adjusted bias. Scalar bias remains enough while each callee’s assertions stay contiguous; a mapping vector is only needed if assertions are deduplicated or selectively bound.

**Min check:** still after the root loop exits, `checkCardinality` walks the entire root array.