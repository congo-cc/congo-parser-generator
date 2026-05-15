# CICS Grammar 

This grammar for a sample subset of the IBM CICS command language is, as best I can tell, correct, up to CICS 6.2.

You may use this grammar freely in your own projects, though it would be nice if you acknowledged your use and provided a link to the CongoCC project.

Also included are some tests of the repetition cardinality assertion feature of CongoCC, which is the sine qua non of recursive descent parser generation for languages such as CICS.

## Build and test

From this directory, with `congocc.jar` at the repository root:

- `ant compile` — generate Java, Python, and C# parsers (if grammar changed) and compile Java plus `dotnet build` for C#.
- `ant test-java` — run `org.parsers.cics.test.ParseFiles` on `testfiles/`.
- `ant test-python` — run `cicsparser/test/parse_files.py` on `testfiles/`.
- `ant test-csharp` — run the C# `ParseFiles` harness on `testfiles/`.
- `ant test-all` — Java, Python, and C# (Rust when `-Drust.enabled=true`).

The root `ant test` target runs `examples/cics` `test-all` plus optional Rust.

