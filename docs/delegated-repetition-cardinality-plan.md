# Delegated Repetition Cardinality — Implementation Plan

**Branch:** `feature/delegated-repetition-cardinality` (from `master`)  
**Baseline behavior:** current `master` (post cardinality merge)  
**Original feature:** commit `82d331d` (repetition cardinality); small adjustments since  
**Status:** plan only — no implementation on this branch yet

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

## 4. Phase 1 — Grammar model & static analysis

### 4.1 Discovery

Extend `Grammar.CardinalityChecker` (or a dedicated pass) to:

1. For each `IteratingExpansion` `L` in production `P`, find delegatee non-terminals in `L`’s nested expansion (see §4.2).
2. For each delegatee production `C`, collect RCAs not enclosed by an iterator in `C`.
3. Record link `(L, NT, C)` and merge constraint metadata into `L`’s container.

### 4.2 Validation rules

| Rule | Action |
|------|--------|
| **Single hop (phase 1)** | Orphan RCAs in `C` bind only to **immediate** caller’s iterator, not grandparent |
| **Call-site consistency** | If `C` is called both inside a delegating loop and elsewhere → **error** |
| **Inner loop wins** | RCAs under `C`’s own `ZOM`/`OOM` bind there; only **non-enclosed** RCAs delegate |
| **Ambiguous iterators** | Same `C` from two sibling iterators in `P` without clear binding → **error** |
| **ATTEMPT / RECOVER** | Keep current ban on RCAs inside attempt blocks; delegation through attempt deferred |
| **Telescoping** | Existing min/max folding in `CardinalityChecker.visit(ExpansionSequence)` applies to **merged** constraints on `L` |

### 4.3 Index & constraint table

Today `ExpansionWithParentheses.close()` assigns `assertionIndex` from `getCardinalityAssertions()` on the loop only (`CongoCC.ccc`).

**Change:** when closing loop `L`, build constraints from:

1. In-loop sequences (current order), then  
2. Delegated sequences from each linked `C`, in stable discovery order (NT walk order in `L`’s body).

Parent loop `L` owns `int[][] choiceCardinalities` and global assertion indices for all delegated RCAs.

### 4.4 Scope predicates

Update `ExpansionSequence.java`:

- `isInScopeConstraint` — RCA in delegatee `C` is in scope iff linked to `L` and not under an inner iterator in `C`.
- `getCardinalitiesContainer()` — for delegated RCAs, returns **`L`** (may cross production boundary in the model).
- Clarify comment on line 54 (“can erroneously extend beyond the parent production”) for the intentional delegated case.

**Core files:** `Grammar.java`, `ExpansionSequence.java`, `CongoCC.ccc` (`ExpansionWithParentheses` inject), possibly `NonTerminal.java`, `BNFProduction.java`.

---

## 5. Phase 2 — Parse codegen (Java → C# → Python)

| Location | Change |
|----------|--------|
| `Parser.java.ctl` (and C#/Python) | `delegatedCardinalityStack` (+ push/pop helpers) |
| `BuildCodeOneOrMore` / `BuildCodeZeroOrMore` | Unchanged local `cardinalitiesN` allocation |
| `BuildCodeNonTerminal` | If delegatee: push `cardinalitiesN`, call child, pop |
| `BuildAssertionCode` | Delegated RCAs use stack top |
| `ErrorHandling.java.ctl` | Eventually stash/restore stack in `ATTEMPT` if delegation allowed there |

**Templates:** `ParserProductions.java.ctl`, `ParserProductions.inc.ctl`, `parser_productions.inc.ctl`, `Parser.cs.ctl`, `Parser.java.ctl`.

---

## 6. Phase 3 — Lookahead / SCAN codegen

| Location | Change |
|----------|--------|
| `BuildProductionLookaheadMethod` | Optional `RepetitionCardinality cardinalities` when needed |
| `ScanCodeNonTerminal` | Pass active `cardinalitiesVar` into callee lookahead |
| `BuildPredicateRoutine` / `BuildScanRoutine` | Delegatee expansions use passed-in cardinalities |
| `ExpansionCondition` / `ScanAheadCondition` | Thread cardinalities into production-level checks |

Reuse existing `RepetitionCardinality` commit/provisional stack so parse and lookahead stay aligned.

**Templates:** `LookaheadRoutines.java.ctl`, `LookaheadRoutines.inc.ctl`, `lookahead_routines.inc.ctl`.

---

## 7. Phase 4 — Tests & docs

| Item | Action |
|------|--------|
| `sandbox/cardinality/CardTests.ccc` | Parent/Child delegation, inner loop vs delegate, illegal dual call sites |
| `sandbox/cardinality/RepetitionCardinality.md` | Document delegation, one-level rule, stack rationale |
| `examples/cics` | Optional refactor to `CommonOptions` production + tests |
| CI | `ant test-cardinality-oracles-java`; sandbox `test-all` for Java/C#/Python |

---

## 8. Implementation order

1. Phase 1 — analysis + checker only; grammar compile tests for new errors  
2. Phase 2 — Java parse templates + sandbox parse tests  
3. Phase 3 — Java lookahead + sandbox (parse + lookahead paths)  
4. Port C# and Python templates  
5. CICS `CommonOptions` refactor (optional but strong regression for real usage)  
6. Update `RepetitionCardinality.md` and this plan’s status  

---

## 9. Reference map (82d331d / master)

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

## 10. Non-goals (this branch / phase 1)

- Unlimited upward delegation (stack is ready; checker stays one hop)  
- RCAs inside `ATTEMPT`/`RECOVER` via delegation  
- New surface syntax unless implicit binding proves insufficient  
- LSP diagnostic messages (follow-up in `congocc-lsp` if desired)
