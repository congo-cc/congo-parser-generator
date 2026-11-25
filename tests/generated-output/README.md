# Generated-output snapshots

`GeneratedOutputSnapshot.java` checks that a change to CongoCC does not change
the code it generates for a target language.  The Java, Python and C# targets
generate complete parsers that need no runtime library, so if the generated
files are byte for byte the same, the parsers behave the same.

The tool has two commands.  `snapshot` generates every grammar of the
repository for every target language with a given `congocc.jar` and stores
the generated files, the generator's messages and its exit status.  `compare`
compares two snapshots.

## Usage

With Ant, from the repository root:

```sh
# 1. With the unchanged sources:
ant snapshot-generated-output -Dgenerated-output.dir=/tmp/before
# 2. Make the change, then:
ant snapshot-generated-output -Dgenerated-output.dir=/tmp/after
# 3. Compare the Java, Python and C# output:
ant compare-generated-output -Dgenerated-output.baseline=/tmp/before \
                             -Dgenerated-output.candidate=/tmp/after
```

`snapshot-generated-output` builds `congocc.jar` first and runs four
generators at a time (`-Dgenerated-output.jobs=N` changes that).
`compare-generated-output` compares Java, Python and C# by default; set
`-Dgenerated-output.langs=java,python,csharp,rust` to choose others.  The
target fails if the snapshots differ.  It first runs
`ant test-generated-output-tool`, the tool's checks of its own comparison
(`self-test` below), which take less than a second.

By hand:

```sh
java tests/generated-output/GeneratedOutputSnapshot.java snapshot \
     --jar congocc.jar --out /tmp/before [--repo .] [--langs java,python,csharp,rust] \
     [--work DIR] [--jobs N] [--timeout-seconds N] [--identity-hash constant|default]
java tests/generated-output/GeneratedOutputSnapshot.java compare /tmp/before /tmp/after \
     [--langs java,python,csharp]
java tests/generated-output/GeneratedOutputSnapshot.java self-test
```

| Option | Meaning |
|---|---|
| `--jar` | The `congocc.jar` to run. |
| `--out` | The snapshot directory.  It must be empty, absent, or an earlier snapshot (which is replaced), and it must not be inside `examples` or `src`. |
| `--repo` | The repository whose grammars are generated (default: the current directory).  Using an older checkout here takes a snapshot of its grammars. |
| `--langs` | The languages to generate (default: all four) or to compare (default: `java,python,csharp`). |
| `--work` | Where the generator runs (default: `build/generated-output-work`; removed afterwards). |
| `--jobs` | How many generators run at once (default: 1). |
| `--timeout-seconds` | How long one generator run may take (default: 900). |
| `--identity-hash` | `constant` (the default) or `default`; see below. |

`snapshot` exits with status 0 when the snapshot is written.  `compare` exits
with status 0 when the snapshots are identical, 1 when they differ (each
difference is listed, with the first differing line of each file), and 2 for
a usage error or snapshots taken with different `--identity-hash` modes.
`self-test` checks the comparison on small synthetic snapshots, without
running the generator: the handling of stack trace frames described below,
the comparison of two output trees, and the exit statuses of `compare`.  It
prints a `FAIL` line, with what was expected and found, for each failed
check, and exits with status 1 if a check failed.

## What is compared

Every `.ccc` file under `examples`, `src/grammars`, `sandbox` and `tests` is
generated, include files too.  The generator runs in a private copy of the
repository that holds only the grammar files: some grammars set
`BASE_SRC_DIR`, which takes precedence over `-d`, and generating them in the
repository itself would overwrite files such as `build/generated-java`.  The
generator always runs from the same directory with the same relative grammar
path, so the source locations that CongoCC writes into the generated code are
the same between runs.

For each grammar and language, the snapshot holds `generator.log` (the exit
status, then the generator's messages, with the paths of the copy and the
jar replaced by placeholders) and `files/` (every file the generator wrote).
`MANIFEST.tsv` lists the exit status, the number of files and a hash of each
output; `SNAPSHOT-INFO.txt` records the jar, its hash, the Java version and the
identity hash mode.

A grammar that fails to generate is compared like any other: its messages and
exit status must be the same.  When the generator fails with an exception,
its messages contain a stack trace, and the line numbers of the frames of
CongoCC's own classes (`at org.congocc....(Main.java:336)`) change whenever
the generator's source is edited.  `compare` ignores those line numbers, and
only those (not the frames of other classes, nor anything in the generated
files), and lists every log that differs only in them as a `NOTE`.  A grammar that exists in only one snapshot is
listed as a note and not compared.  A change to a grammar file shows up as a
difference, if only in the line numbers of the source locations in the
generated code.

## Identity hash codes

Some of the generator's data structures are hash sets of objects that do not
define `hashCode()`, for example the sets of NFA states of the lexer
(`org.congocc.core.nfa`).  The order in which such a set is iterated decides,
among other things, the numbers of the NFA states in the generated lexers.
With the JVM's default identity hash codes, that order depends on how many
identity hash codes were requested before, by any code, so any change to the
generator, even code that is never run for a given target, can renumber the
states of every lexer without changing what the lexer does.  The same jar
gives the same output every time, but two different jars may not.

So `snapshot` runs the generator with constant identity hash codes
(`-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`, `--identity-hash
constant`).  Every object then has the same identity hash code, the iteration
order depends only on the order in which the generator adds the objects, and
the output depends only on what the generator does.  This is slower (the hash
sets degenerate into lists) but makes the comparison meaningful.
`--identity-hash default` runs the generator as users run it; both snapshots
of a comparison must use the same mode.
