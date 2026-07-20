## Repetition Cardinality Assertions

### 1. Introduction

The repetition cardinality assertion is a mechanism provided by the CongoCC parser generator. It enables constraining how many times a specific expansion may occur within a repeating grammar element — that is, within a `OneOrMore` (`+`) or `ZeroOrMore` (`*`) loop — by placing explicit occurrence limits at points in the grammatical scope of that loop.

A **repetition cardinality assertion**, or *RCA*, is written `&m:n&`, where `m` is the lower bound and `n` the upper bound of how often the following sequence may appear in the nearest enclosing syntactic repetition. Shorthand forms are `&n` (exactly `n`), `&n:&` (at least `n`), and bare `&` (at most once, i.e. `&0:1&`).

The iterating loop may live in the **same** production as the RCA, or — with **delegated** cardinality — in a **direct caller** production that invokes a callee whose RCAs are orphans (no local `*`/`+`). Delegation is what lets shared option groups (for example CICS `CommonOption`) be written once and reused under many command loops.

### 2. Why Cardinality Constraints?

Programming languages often restrict how many syntactic elements of a given kind may appear in a construct: “at most one of each …”, “exactly one …”, “between *n* and *m* …”. Those rules are awkward to enforce in plain top-down grammars and usually force obscure semantic actions or bloated BNF.

IBM’s **Customer Information Control System (CICS)** embedded command language is a strong example: hundreds of commands share the rule that optional items may occur in any order, with per-option occurrence limits. Without RCAs (and without delegation for shared options), a recursive-descent grammar would need tedious predicates and duplicated choice groups at every call site.

### 3. CongoCC Formal Syntax

Repetition cardinality is expressed as an assertion whose predicate uses the cardinality notation (the meta-syntax itself uses RCAs):

```
( &"ASSERT" | &"ENSURE" )+ "{" Cardinality "}"
|
Cardinality
```

Cardinality:

```
"&" [ [ Min [ ":" [ Max ] ] ] "&" ]
```

where:

- `Min` is the minimum required occurrences (optional; defaults to `0` for lone `"&"`, `1` if a following `"&"` is present).
- `Max` is the maximum allowed occurrences (optional; defaults to `Min` if no `":"` is present, otherwise unbounded / largest integer).
- Lone `"&"` means `&0:1&` (at most once).
- `"&&"` means `&1:1&` (exactly once).
- With no constraint, behavior is like `&0:∞&` (no upper limit from an RCA).

`ASSERT` checks only during parsing (not lookahead). `ENSURE`, or the `#` suffix on a semantic assertion, also applies during lookahead. The **abbreviated** form (cardinality syntax alone) implies both parse and lookahead, and may appear in any `ExpansionChoice` under a BNF production — including a bare production body used for delegated orphans (no outer parentheses required).

### 4. Formal Semantics

- Constraints apply during **parsing** of the owning repetition, and may apply during **lookahead**, so SCAN and parse stay aligned.
- On entering `(...)*` or `(...)+`, if the loop owns RCAs (local and/or delegated), the parser allocates a `RepetitionCardinality` tally vector — one slot per RCA in stable order (local indices first, then delegated).
- When an RCA is reached, the parser tries to increment its tally; success continues, failure (already at max) fails parse or ends the lookahead loop attempt.
- On loop exit, each tally is checked against its minimum; failure fails parse or lookahead.

**Local binding:** an RCA under a `*`/`+` in the same production binds to that nearest enclosing iterator.

**Delegated binding:** an orphan RCA in callee `C` binds to a **direct** caller’s iterating loop `L` that invokes `C` (one hop). At parse time the caller pushes the loop’s `RepetitionCardinality` (and, for multi-parent layouts, an `indexBias`) around the NonTerminal call; the callee’s RCAs use the stack top. Lookahead passes the same object into the callee’s production scan method. Call sites must be consistent: the same callee may not be used both inside and outside a loop, or from two distinct loops, unless multi-parent bias assigns each site a distinct contiguous index block.

**Inner loop wins:** an RCA under a `*`/`+` inside the callee binds locally; only non-enclosed orphans delegate.

**Mixed local + delegated:** one parent loop may own both, e.g. `( &A | &B | Child )+`, sharing one tally array.

#### Examples of expansion behavior

- `( &A | &B | &C )*` — any subset of `A`, `B`, `C` (including empty), each at most once.
- `( &1:2& A | && B )*` — `A` one or two times, `B` exactly once (order free among valid sequences such as `AB`, `BA`, `AAB`, …).

Delegated shape:

```text
Parent : ( Child )+ ;
Child  : & Opt1 | & Opt2 ;   // orphans bind to Parent's +
```

### 5. Examples of Usage

#### Basic Cases

- `( &Foo | &Bar )*` → equivalent to `( &0:1& Foo | &0:1& Bar )*` → equivalent to the long `ENSURE ASSERT {&0:1&} …` form.

Placement of the RCA usually does not change the language recognized (the whole alternative must succeed), but can affect when semantic actions run relative to accept/reject.

- `( && Foo | Bar )*` → `( &1:1& Foo | Bar )*`.

#### Constrained Elements Within Repetitions

- `( &1:2& A | B )*` — at least one and at most two `A`s; any number of `B`s.
- `( &0:4& A | &B )*` — at most four `A`s and at most one `B`.
- `( &2& Foo )*` — exactly two `Foo`s, or empty if the loop allows exit before mins are met in other designs; with mins enforced on exit, empty fails when a min is positive.
- `( & Bar )*` — `Bar` or nothing; like `[Bar]`.

#### Constrained Repetitions

- `( ( A | B ) &5& )+` — sequences of `A`/`B` of length 5.
- `( &5:& ( A | B ) )*` — optional run of five or more `A`/`B`.

#### Delegation

- Shared options: `CommonOption : & <NOHANDLE> | & <RESP> … ;` used as `( CommonOption )*` under many commands.
- Mixed: `( & "W" | & "Z" | MixedChild )+` with orphans in `MixedChild`.
- Inner vs delegate: callee has both an orphan and `( & "V" )+`; only the orphan binds to the parent loop.

### 6. Summary

Repetition cardinality gives precise, lookahead-aligned control over occurrence counts inside `*` / `+` without semantic predicates or duplicated BNF. **Local** RCAs bind to the nearest enclosing loop in the same production; **delegated** RCAs bind orphan assertions in a callee to a direct caller’s loop, so shared option productions stay DRY. CongoCC targets Java, C#, and Python for both local and delegated codegen; the sandbox (`CardTests`, `checker-negative/`) and CICS example exercise the feature end to end.
