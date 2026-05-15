# Sandbox: JTB-style trees in CongoCC

This directory is a **minimal, compiling** example of CongoCC’s JTB-style synthetic tree options (`X_SYNTHETIC_NODES_ENABLED`, `X_JTB_PARSE_TREE`) without Java actions, SCAN, or a large grammar—useful when migrating from a JavaCC/JTB stack or validating tree behavior.

## Contents

| File | Purpose |
|------|---------|
| `JtbDemo.ccc` | Tiny expression grammar (`+`, `*`, digits, parentheses), JTB settings, stub `Terminal` / `Sequence` / `Choice` / … productions (`FAIL`), and a minimal `INJECT Choice` so the generated parser can call `setChoice`. |
| `samples/trivial.sum` | Example input (kept long enough that the generated C# `TokenSource` BOM probe does not read past the end of the buffer on very short files). |
| `build.xml` | Targets: **`compile`** (generate Java/Python/C#, compile Java + C#), **`test`** / **`test-all`** (then run `ParseFiles` for each language on `samples/`). |
| `WIKI-JTB-style-trees.md` | Offline copy of the project wiki page on JTB-style trees (canonical URL inside). |

## Build and run

From the **repository root** (so `../../congocc.jar` resolves, same pattern as `sandbox/cardinality`):

```bash
cd sandbox/JTB
ant clean test
```

Requires **Python 3** and **.NET SDK** (`dotnet`) on `PATH`, same as other multi-language examples in the repo.

To regenerate parsers only:

```bash
cd sandbox/JTB
ant java-parser-gen
ant python-parser-gen
ant csharp-parser-gen
```

Generated output (gitignored except the grammar): `org/` (Java), `jtbdemoparser/` (Python), `cs-jtbdemoparser/` (C#).

The generator also emits `org/parsers/jtbdemo/test/ParseFiles.java` (standard test harness for `TEST_EXTENSION` files).

## Relation to the wiki

The long-form guide, optional `INJECT` blocks for JTB-compatible `NodeToken` / `NodeSequence` / …, and visitor migration notes live in **`WIKI-JTB-style-trees.md`** (mirrored from the GitHub wiki). The grammar here only carries what is **required** for this tiny grammar to compile and run; a full JTB port follows the wiki injections more completely.

## Codegen note (repo templates)

Synthetic-choice JTB tracking emits a call on the current node; the Java / C# / Python `ParserProductions` templates now use `${globals::translateIdentifier("setChoice")}` so the emitted name matches each language (`setChoice`, `SetChoice`, `set_choice`) and stays aligned with `INJECT` method names.
