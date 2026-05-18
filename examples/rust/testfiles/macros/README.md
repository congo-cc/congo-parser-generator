# Rust macro parse regression matrix (Phase 1)

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

## Baseline (existing `testfiles/` corpus)

As of Phase 1 on branch `rust-macro-phase1`:

- **102** `.rs` files under `testfiles/` (excluding this `macros/` subtree at first measurement)
- **0** parse failures with default `RParse` (non-tolerant)
- **109** files with `testfiles/macros/` included — **0** failures

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

See also `docs/rust_macro_plan.md` on the `rust-macro-plan-docs` branch for Phases 2+.
