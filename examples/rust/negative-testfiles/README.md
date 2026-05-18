# Rust parse negative fixtures (manual)

These files are **expected to fail** parse (e.g. unbalanced delimiters in `DelimTokenTree`). They live **outside** `testfiles/` so `ant test` does not pick them up.

```bash
cd examples/rust
ant compile
java -cp . RParse negative-testfiles/unbalanced_fail.rs
```
