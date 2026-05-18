# Rust macro parse regression matrix (Phases 1–3)

Focused `.rs` fixtures for the CongoCC Rust grammar (`Rust.ccc`). Each file is parsed with `RParse`; success means the grammar accepts the input and builds an AST (no macro expansion).

## Running

From `examples/rust`:

```bash
ant compile
java -cp . RParse -q testfiles/macros
# or
ant test-macros
```

Full corpus (includes this directory):

```bash
ant test
```

## Fixture matrix

| File | Exercises |
|------|-----------|
| `macro_rules_repetition.rs` | `macro_rules!`, `$(...)`, `,` / `*` / `+` / `?` repetitions |
| `builtin_macros.rs` | `println!`, `vec!`, `format!`, `include_str!` |
| `path_macros.rs` | `std::format!`, `crate::...!`, `$crate::...!` |
| `attr_macros.rs` | `#[derive(...)]`, `cfg_attr`, `proc_macro_derive`, `proc_macro_attribute` |
| `nested_macro_rules.rs` | `macro_rules!` nested inside another macro's `DelimTokenTree` |
| `comments_in_macro.rs` | `//` and `/* */` inside a `macro_rules!` body (unparsed token stream) |
| `macro_stmt_in_block.rs` | `macro_rules!` inside a block; macro invocation as block tail (no `;`) |
| `delim_nesting.rs` | Nested `DelimTokenTree` (`vec![mac!(a { b { c } })]`, `one(two(three()))`) |
| `match_arm_macro.rs` | `MacroInvocationSemi` inside a `match` arm |
| `item_list_macro.rs` | Item-level `macro_rules!` and `...!();` at module scope |
| `contextual_in_body.rs` | `union` / `raw` / `macro_rules` / `break` spellings inside macro bodies |
| `decl_macro_item.rs` | `macro name { ... }` items with `pub`, `pub(crate)`, `#[macro_export]` |

Negative fixtures (expected parse failure): `../../negative-testfiles/` — see that directory’s README.

## Baseline (existing `testfiles/` corpus)

As of Phase 1 on branch `rust-macro-phase1`:

- **102** `.rs` files under `testfiles/` (excluding this `macros/` subtree at first measurement)
- **0** parse failures with default `RParse` (non-tolerant)
- **114+** files with `testfiles/macros/` included — **0** failures (run `ant test` to refresh count)

### Macro-heavy files already in the wild corpus

These pass today and are useful when changing `DelimTokenTree` or macro productions:

- `assertions.rs` — `macro_rules!` with metas and `#[macro_export]`
- `join.rs` — nested `macro_rules!` inside `doc! { ... }`
- `derive_action.rs` — `proc_macro` imports
- `dynamic_spacing.rs` — `proc_macro::TokenStream`
- `propagate_stability.rs` — references to macro item kinds in comments/paths

## What “success” means here

- **Parsed:** `MacroInvocation`, `MacroRulesDefinition`, `DelimTokenTree`, etc. appear in the tree.
- **Token soup:** Inside `DelimTokenTree`, bodies are largely flat `AnyToken` children (plus **unparsed** comments/whitespace in the lexer stream, not listed in `AnyToken`).
- **Not tested:** rustc compatibility, macro expansion, or name resolution.

## Phase 1 exit criteria

- [x] Baseline pass/fail for full `testfiles/`
- [x] Minimal macro matrix under `testfiles/macros/`
- [x] This README

## Phase 2 exit criteria

- [x] `DelimTokenTree` nesting cases (`delim_nesting.rs`)
- [x] `AnyToken` includes contextual spellings `'union'`, `'raw'`, `'macro_rules'` (plus `break`)
- [x] `MacroInvocationSemi` cases: block tail, item `;`, match arm
- [x] `comments_in_macro.rs` — comments remain lexer `UNPARSED` (attached via `precedingUnparsedTokens`, not `AnyToken` alternatives)
- [x] Negative unbalanced delimiter fixture under `negative-testfiles/` (manual only)

## Phase 3 exit criteria

- [x] `MacroDefinition` production (`[Visibility] macro Identifier MacroRulesDef`)
- [x] Wired into `Item` and `Statement`
- [x] `decl_macro_item.rs` — `pub macro`, `pub(crate) macro`, `#[macro_export]`

See also `docs/rust_macro_plan.md` for Phases 4+.
