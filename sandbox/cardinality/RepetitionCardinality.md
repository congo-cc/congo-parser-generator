##Repetition Cardinality Assertions

### 1. Introduction

The repetition cardinality assertion is a mechanism provided by the CongoCC parser generator.  It enables the constraining of the number of times a specific expansion may occur within a repeating grammar element. Thus, it constrains the behavior of `OneOrMore` (`+`) and `ZeroOrMore` (`*`) loops dynamically by allowing explicit occurrence limits to be optionally specified at points in the grammatical scope of the loop.

### 2. Why Cardinality Constraints?

Often programming languages impose restrictions on the number of syntactic elements of a certain type that may be used within a source construct. These restrictions can go beyond the simple "no more than one instance in any order" to cases requiring "exactly one of each ...", "no more than *n* ..." or "between *n* and *m* ..." occurrences. This introduces constraints that are usually difficult or tedious to enforce during top-down parsing.  In particular, the solution to this often requires obscure semantic actions or overly complex grammatical expression.

A notable example of a language with a compelling need for this capability in a top-down parser is IBM's **Customer Information Control System (CICS)** embedded command language. CICS is an extensive  language comprising hundreds of commands. With a single seemingly innocuous syntactic rule stating that "optional items may occur in any order," CICS seems not to be parsable by any of the common parser generators. Use of a typical recursive descent parser generator would would be expected to require tedious semantic actions and predicates within the grammar specification to enforce this kind of syntax restriction during parsing.

The following describes a feature of the CongoCC grammar supporting a type of constraining predicate: **a repetition cardinality assertion**, or *RCA*, notated generally as `&m:n&`, where `m` is the lower bound of cardinality and `n` is the upper bound of cardinality of the following sequence in the context of the nearest enclosing syntactic repetition. This sequence must be within a `ZeroOrMore` or `OneOrMore` expansion. The notation may also be abbreviated as `&n` (equivalent to `&n:n&`, meaning "exactly n"), `&n:&` (equivalent to `&n:∞&`), or simply `&`, which defaults to `&0:1&` (meaning "no more than one").

### 3. CongoCC Formal Syntax

In the CongoCC parser generator the syntax used to express repetition cardinality consists of an assertion with a semantic predicate specifying a special notation as follows (and itself using this notation):

```
( &"ASSERT" | &"ENSURE" )+ "{" Cardinality "}"
|
Cardinality
```

The syntax for repetition cardinality (`Cardinality`) can be expressed as:

```
"&" [ Min [ ":" [ Max ] ] "&" ]
```

where:

- `Min` is the minimum required occurrences (optional; defaults to `0` if omitted).
- `Max` is the maximum allowed occurrences (optional; defaults to `Min` if the `Min` alone is present or the largest *integer* value otherwise).
- A lone `"&"` is interpreted as `&0:1&`, meaning the expansion may appear at most once.
- If no constraint is specified, it is implicitly equivalent to `&0:∞&` (no upper limit).

If an `ASSERT` is used, the assertion will only be checked during parsing, and will not affect any lookahead. To enforce the assertion during lookahead in addition to parsing, `ENSURE` may be specified, or it may be followed by the `#` suffix consistent with other semantic assertions.

For convenience and readablity the assertion may also be abbreviated using the cardinality syntax alone at the point that the corresponding `ASSERT` and/or `ENSURE` may be used.  When this is the case, it also implies the application of the constraint in both lookahead and parsing.


### 4. Formal Semantics

- The repetition constraints are always applied during **parsing** of the affected repetition, and may be applied during **lookahead**, thus ensuring that parsing follows a consistent path.
- Upon entering a repetition `(...)*` or `(...)+` the parser will initialize a cardinality tally if the loop contains an RCA.  Each unique RCA within the loop will have its own tally.
- At the point in either lookahead or parsing that the parser encounters the RCA, it will attempt to increment the tally by one and the lookahead or parsing will continue if it is successful. If the tally has already reached the maximum indicated in the associated RCA, however, parsing or lookahead will fail.  This process is repeated for each RCA encountered until the loop controlling them is exited.
- Following exit of the loop, the parser will check each RCA's tally against the minimum cardinality of the RCA and, if met, will continue as is normally the case.  If the minimum constraint is not met, the parsing or lookahead will fail at that point.

#### **Examples of expansion behavior**:
  - `( &A | &B | &C )*` allows any subset of sequences of `A`, `B`, and `C`, including the empty set. Specifically, `{}` (empty sequence), `A`, `B`, `C`, `AB`, `AC`, `BA`, `CA`, `BC`, `CB`, `ABC`, `CBA`, `BAC`, `CAB`, `BCA`, `ACB`. 
      - The parser will enter the loop containing either an `A`, `B`, or `C` after initializing three distinct tallies to the value 0.
      - Each iteration of the loop that is attempted will increment the corresponding tally by 1, provided it is not already 1, and will parse the choice normally. If the tally cannot be incremented (i.e., it has already been incremented in this loop instance), parsing will fail with a parse exception (or the lookahead will deem the loop completed).
      - During lookahead when no choice is available within the loop, either because the input does not match any choice or the input matches a choice but that choice's tally is 1, the loop exits.
      - The parser does not need to check the minimum cardinality because it is 0 and permits omission of any choices.
    - `( &1:2& A | &1& B )*` ensures `A` appears **at least once but no more than twice**, and `B` appears **exactly once**. Possible sequences include `{}`, `AB`, `BA`, `AAB`, `ABA`, `BAA`.
        - The parser will enter the loop containing `A` or `B` after initializing both tallies to 0.
        - If an `A` is the next input token, the parser checks the first tally and, if it is not already 2, increments it. If it is already 2, the loop is terminated with an exception (or lookahead will deem the loop completed).
        - If a `B` is encountered, the parser checks the second tally and, if it is not already 1, increments it.  If it is already 1, the loop is terminated with an exception (or lookahead will deem the loop completed).
        - Upon loop completion the parser will check the first tally and, if it is not at least 1, will indicate a parse exception (or if in lookahead, will deem the loop's lookahead scan to have failed). If the first tally meets the minimum check, the parser will then check the second tally in the same manner. 

### 5. Examples of Usage

#### Basic Cases

- `( &Foo | &Bar )*` → Equivalent to `( &0:1& Foo | Bar& )*` → Equivalent to `( ENSURE ASSERT {&0:1&} Foo | Bar ENSURE ASSERT {&0:1&} =>|| )*` .

This is an example of the simplest use of repetition cardinality to recognize the set of all combinations of at most one `Foo` and one `Bar`. Note that the placement of the repetition cardinality assertion usually does not affect the syntax recognized by the grammar, since recognition of the entire sequence is based on the truth of all the predicates and assertions in the sequence, but the position could affect semantic actions applied before the acceptance or rejection of the sequence by the RCA.

- `( &1& Foo | Bar )*` → Equivalent to `( &1:1& Foo | Bar )*`.

#### Constrained Elements Within Repetitions

- `( &1:2& A | B )*` → A sequence of `A` and `B` containing at least one, but not more than two `A`s, and any number of `B`s, including none, or an empty sequence.
- `( &0:4& A | &B )*` → A sequence of `A` and `B` containing no more than four `A`s and one `B`, or an empty sequence.
- `( &2& Foo )*` → A sequence of `Foo` appearing **exactly twice**, or nothing.
- `( & Bar )*` → `Bar` or nothing. Equivalent to `[Bar]`.

These constraints refine the parsing process while maintaining the expressiveness of choice-based repetitions.

#### Constrained Repetitions

* `( ( A | B ) &5&)+` → A sequence of `A`s and `B`s of length 5.
* `( &5:&( A | B ) )*` → An optional sequence of 5 or more `A`s and/or `B`s.    

### 6. Summary

Repetition cardinality provides precise control over element occurrences within repetition constructs and repetitions themselves in CongoCC. By applying identical lookahead and parsing constraints, it ensures predictable parsing behavior for cardinality-constrained syntax without semantic rules or confusingly complex syntax in grammar definitions.

