package org.congocc.codegen.rust;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Tests of CongoCC's Rust target ({@code -lang rust}).
 *
 * <h2>Running the tests</h2>
 * <pre>
 *   ant test-rust-codegen
 * </pre>
 * <p>or, by hand, after {@code ant jar} (from the repository root):</p>
 * <pre>
 *   javac --release 17 -cp congocc.jar -d build/rust-codegen-tests/classes \
 *         tests/rust-codegen/org/congocc/codegen/rust/RustCodegenTest.java
 *   java -cp build/rust-codegen-tests/classes:congocc.jar \
 *        org.congocc.codegen.rust.RustCodegenTest [--repo .] [--jar congocc.jar] \
 *        [--work build/rust-codegen-tests] [--only &lt;text&gt;]...
 * </pre>
 * <p>{@code --only} runs the tests whose name contains the text (it can be
 * repeated); the options can also be written with one dash ({@code -only}).  The tests need {@code cargo} on the {@code PATH}.  The program
 * prints one {@code PASS} or {@code FAIL} line per test, with the reason and
 * the line of this file of every failure, and exits with status 1 if a test
 * failed.</p>
 *
 * <h2>What is tested</h2>
 * <dl>
 * <dt>{@code reserved-names/...}</dt>
 * <dd>The names that injected code may not use in the generated Rust
 *     ({@link RustCodeEmitter#RESERVED_PARSER_NAMES},
 *     {@link RustCodeEmitter#RESERVED_LEXER_NAMES}) are exactly the fields and
 *     methods that the templates define.</dd>
 * <dt>{@code cli/...}</dt>
 * <dd>The {@code -rust-allow-stubs} flag, and the messages and exit status of
 *     the generator.</dd>
 * <dt>{@code diagnostics/...}</dt>
 * <dd>The grammars in {@code tests/rust-codegen/diagnostics}: Java code that
 *     the Rust target cannot translate is an error where it changes what is
 *     parsed and a warning elsewhere; the generated crate then contains a
 *     {@code compile_error!} (so that it cannot be built by mistake), or a
 *     stub with {@code -rust-allow-stubs} (and then it builds).</dd>
 * <dt>{@code features/...}</dt>
 * <dd>For each grammar {@code tests/rust-codegen/features/<name>.ccc}, the
 *     Java parser and the Rust parser generated from it build the same tree,
 *     or report a syntax error at the same place, for every input in
 *     {@code features/<name>/}.  Inputs named {@code ok-*} must parse and
 *     inputs named {@code bad-*} must not; every feature has both kinds.  The
 *     trees are compared in the format of {@code examples/rust/TreeDump.java}.
 *     An optional {@code features/<name>/rust-code.expect} lists text that the
 *     generated Rust code must contain, one {@code <file>: <text>} per line.</dd>
 * </dl>
 * <p>Generated files go to the work directory, which is
 * {@code build/rust-codegen-tests} by default.  It is not deleted at the end,
 * so that a failure can be investigated, and its Cargo target directories make
 * later runs faster.</p>
 */
public final class RustCodegenTest {

    /** The first line of every tree dump; the same as in TreeDump.java and the Rust harnesses. */
    private static final String FORMAT_HEADER = "congocc-tree 1";

    /** The longest time a generator, compiler or harness run may take. */
    private static final long PROCESS_TIMEOUT_SECONDS = 600;

    /** The longest time the Java parser may take for one feature input. */
    private static final long PARSE_TIMEOUT_SECONDS = 30;

    private final Path repo;
    private final Path jar;
    private final Path work;
    private final List<String> only;

    private int passed;
    private final List<String> failures = new ArrayList<>();

    private RustCodegenTest(Path repo, Path jar, Path work, List<String> only) {
        this.repo = repo;
        this.jar = jar;
        this.work = work;
        this.only = only;
    }

    public static void main(String[] args) throws IOException {
        Path repo = Path.of(".");
        Path jar = null;
        Path work = null;
        List<String> only = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            // '-only' is accepted for '--only' (and so on), as CongoCC accepts
            // both spellings: an Ant property can then pass it (an XML comment,
            // which documents the property, cannot contain '--').
            String option = args[i].startsWith("--") ? args[i] : "-" + args[i];
            String usage = "Usage: java " + RustCodegenTest.class.getName()
                + " [--repo <repository root>] [--jar <congocc.jar>] [--work <directory>] [--only <text>]...";
            if (!Set.of("--repo", "--jar", "--work", "--only").contains(option)) {
                System.err.println("RustCodegenTest: unexpected argument '" + args[i] + "'.\n" + usage);
                System.exit(2);
            }
            if (i + 1 >= args.length) {
                System.err.println("RustCodegenTest: the option '" + args[i] + "' needs a value.\n" + usage);
                System.exit(2);
            }
            String value = args[++i];
            switch (option) {
                case "--repo" -> repo = Path.of(value);
                case "--jar" -> jar = Path.of(value);
                case "--work" -> work = Path.of(value);
                default -> only.add(value);
            }
        }
        repo = repo.toAbsolutePath().normalize();
        jar = (jar == null ? repo.resolve("congocc.jar") : jar).toAbsolutePath().normalize();
        work = (work == null ? repo.resolve("build/rust-codegen-tests") : work).toAbsolutePath().normalize();
        if (!Files.isDirectory(repo.resolve("src/templates/rust")) || !Files.isDirectory(repo.resolve("tests/rust-codegen"))) {
            System.err.println("RustCodegenTest: " + repo + " is not the root of the CongoCC repository "
                + "(no src/templates/rust or tests/rust-codegen); run from the repository root or pass --repo.");
            System.exit(2);
        }
        if (!Files.isRegularFile(jar)) {
            System.err.println("RustCodegenTest: " + jar + " does not exist; build it with 'ant jar' or pass --jar.");
            System.exit(2);
        }
        Files.createDirectories(work);
        RustCodegenTest test = new RustCodegenTest(repo, jar, work, only);
        test.runAll();
        System.exit(test.failures.isEmpty() ? 0 : 1);
    }

    private void runAll() {
        long start = System.nanoTime();
        System.out.println("Rust code generation tests (generator " + jar + ", work directory " + work + ")");
        test("reserved-names/parser", () -> reservedNamesMatchTemplate("parser.rs.ctl", "Parser",
            RustCodeEmitter.RESERVED_PARSER_NAMES));
        test("reserved-names/lexer", () -> reservedNamesMatchTemplate("lexer.rs.ctl", "Lexer",
            RustCodeEmitter.RESERVED_LEXER_NAMES));
        test("names/snake-case", this::snakeCaseNames);
        test("names/types-with-articles", this::typesWithArticles);
        test("cli/clean-grammar-generates-without-diagnostics", this::cleanGrammarGeneratesWithoutDiagnostics);
        test("cli/allow-stubs-is-ignored-for-java", this::allowStubsIsIgnoredForJava);
        test("cli/allow-stubs-accepts-two-dashes", this::allowStubsAcceptsTwoDashes);
        test("diagnostics/untranslatable-code-is-an-error", this::untranslatableCodeIsAnError);
        test("diagnostics/untranslatable-code-is-stubbed-with-allow-stubs", this::untranslatableCodeIsStubbed);
        test("diagnostics/reserved-member-names", this::reservedMemberNames);
        test("diagnostics/rust-name-collisions", this::rustNameCollisions);
        test("diagnostics/token-arguments", this::tokenArguments);
        test("diagnostics/reachability", this::reachability);
        test("diagnostics/outside-the-subset", this::outsideTheSubset);
        test("diagnostics/unimplemented-features-are-warnings", this::unimplementedFeaturesAreWarnings);
        test("diagnostics/unimplemented-features-for-other-targets-only", this::unimplementedFeaturesForOtherTargetsOnly);
        test("diagnostics/try-and-attempt-are-errors", this::tryAndAttemptAreErrors);
        test("diagnostics/try-and-attempt-with-allow-stubs", this::tryAndAttemptWithAllowStubs);
        featureTests();
        System.out.printf(Locale.ROOT, "%n%d passed, %d failed (%.0f s)%n", passed, failures.size(),
            (System.nanoTime() - start) / 1e9);
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
    }

    // =====================================================================
    // The test runner
    // =====================================================================

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /** A failed check: the message says what was expected and what was found. */
    private static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private boolean selected(String name) {
        return only.isEmpty() || only.stream().anyMatch(name::contains);
    }

    private void test(String name, Body body) {
        test(name, body, false);
    }

    /**
     * Runs a test and prints its result.
     *
     * @param required run it even if {@code --only} does not select it: a
     *        selected test depends on it
     */
    private void test(String name, Body body, boolean required) {
        if (!required && !selected(name)) {
            return;
        }
        long start = System.nanoTime();
        try {
            body.run();
            passed++;
            System.out.printf(Locale.ROOT, "PASS %s (%.1f s)%n", name, (System.nanoTime() - start) / 1e9);
        } catch (Throwable t) {
            failures.add(name);
            String message = t instanceof Failure ? t.getMessage() : t.toString();
            System.out.println("FAIL " + name + ": " + message.replace("\n", "\n    ") + "\n    (" + failureSite(t) + ")");
            if (!(t instanceof Failure)) {
                t.printStackTrace(System.out);
            }
        }
    }

    /** @return the line of this file where the failed check is */
    private static String failureSite(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith(RustCodegenTest.class.getName()) && !e.getMethodName().startsWith("check")
                && !e.getMethodName().equals("fail")) {
                return "RustCodegenTest.java:" + e.getLineNumber() + " in " + e.getMethodName();
            }
        }
        return "no location";
    }

    private static Failure fail(String message) {
        throw new Failure(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    private static void checkContains(String text, String expected, String what) {
        if (!text.contains(expected)) {
            fail(what + " does not contain\n  " + expected + "\nIt is:\n" + indent(text));
        }
    }

    private static void checkNotContains(String text, String unexpected, String what) {
        if (text.contains(unexpected)) {
            int at = text.indexOf(unexpected);
            fail(what + " contains\n  " + unexpected + "\nat: " + excerpt(text, at));
        }
    }

    private static String indent(String text) {
        String trimmed = text.length() > 6000 ? text.substring(0, 6000) + "\n[... " + (text.length() - 6000)
            + " more characters]" : text;
        return "  | " + trimmed.replace("\n", "\n  | ");
    }

    private static String excerpt(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at) + 1;
        int lineEnd = text.indexOf('\n', at);
        return text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd).trim();
    }

    // =====================================================================
    // Processes
    // =====================================================================

    /** The result of a process: its exit status and what it printed. */
    private record Run(List<String> command, int status, String stdout, String stderr) {
        String describe() {
            return "'" + String.join(" ", command) + "' exited with status " + status
                + (stdout.isBlank() ? "" : "\nstdout:\n" + indent(stdout))
                + (stderr.isBlank() ? "" : "\nstderr:\n" + indent(stderr));
        }

        /** @return the lines of standard error that start with the given text */
        List<String> stderrLines(String prefix) {
            return stderr.lines().filter(l -> l.startsWith(prefix)).collect(Collectors.toList());
        }
    }

    private Run run(Path directory, Map<String, String> environment, List<String> command) throws IOException {
        Path out = Files.createTempFile(work, "stdout", ".txt");
        Path err = Files.createTempFile(work, "stderr", ".txt");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
            builder.environment().putAll(environment);
            Process process = builder.start();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("'" + String.join(" ", command) + "' did not finish within " + PROCESS_TIMEOUT_SECONDS + " seconds");
            }
            return new Run(command, process.exitValue(), Files.readString(out), Files.readString(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running " + command, e);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    /**
     * Runs the generator under test on a grammar.
     *
     * @param lang   "java" or "rust"
     * @param output the output directory (created afresh)
     * @param flags  more command line flags
     */
    private Run generate(Path grammar, String lang, Path output, String... flags) throws IOException {
        deleteRecursively(output);
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-jar", jar.toString(), "-n"));
        if (!lang.equals("java")) {
            command.addAll(List.of("-lang", lang));
        }
        command.addAll(Arrays.asList(flags));
        // An absolute -d: a relative one is created relative to the current
        // directory but used relative to the grammar's directory.
        command.addAll(List.of("-d", output.toString(), grammar.toString()));
        return run(grammar.getParent(), Map.of(), command);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Builds a generated crate with cargo; the result is returned, not checked. */
    private Run cargoBuild(Path crate) throws IOException {
        return run(crate, Map.of("CARGO_TARGET_DIR", work.resolve("cargo-target/diagnostics").toString()),
            List.of("cargo", "build", "--color", "never"));
    }

    /** @return the lines of cargo's output that report a warning or an error */
    private static List<String> cargoProblems(Run build) {
        return build.stderr().lines().filter(l -> l.startsWith("warning") || l.startsWith("error"))
            .collect(Collectors.toList());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private Path diagnosticsGrammar(String name) {
        return repo.resolve("tests/rust-codegen/diagnostics").resolve(name);
    }

    // =====================================================================
    // reserved-names: the names of the generated structs
    // =====================================================================

    private static final Pattern FIELD = Pattern.compile("^\\s+(?:pub(?:\\([a-z]+\\))?\\s+)?([a-z_][a-z0-9_]*)\\s*:.*");
    private static final Pattern METHOD =
        Pattern.compile("^    (?:pub(?:\\([a-z]+\\))?\\s+)?(?:const\\s+)?fn\\s+([a-z_][a-z0-9_]*)\\s*[(<]");

    /**
     * Checks that {@code reserved} is the set of the field names of
     * {@code pub struct <name>} and the method names of the {@code impl <name>}
     * blocks in the template: a name missing from the set would let injected
     * code define a member that does not compile, and a name in the set that
     * the template does not define would reject valid injected code.  Members
     * whose names are computed ({@code fn parse_${...}}) are not literal and
     * are not counted.
     */
    private void reservedNamesMatchTemplate(String template, String struct, Set<String> reserved) {
        Path file = repo.resolve("src/templates/rust").resolve(template);
        List<String> lines = read(file).lines().collect(Collectors.toList());
        Set<String> defined = new TreeSet<>();
        int structs = 0;
        int impls = 0;
        Pattern structStart = Pattern.compile("^pub struct " + struct + "\\b.*\\{$");
        Pattern implStart = Pattern.compile("^impl(?:<[^>]*>)? " + struct + "\\s*\\{$");
        for (int i = 0; i < lines.size(); i++) {
            boolean isStruct = structStart.matcher(lines.get(i)).matches();
            boolean isImpl = implStart.matcher(lines.get(i)).matches();
            if (!isStruct && !isImpl) {
                continue;
            }
            int end = i + 1;
            while (end < lines.size() && !lines.get(end).equals("}")) {
                end++;
            }
            check(end < lines.size(), template + ":" + (i + 1) + ": no line '}' closes '" + lines.get(i) + "'");
            for (String line : lines.subList(i + 1, end)) {
                Matcher m = (isStruct ? FIELD : METHOD).matcher(line);
                if (isStruct ? m.matches() : m.lookingAt()) {
                    defined.add(m.group(1));
                }
            }
            if (isStruct) structs++; else impls++;
            i = end;
        }
        check(structs == 1, template + " should define 'pub struct " + struct + "' once, at the start of a line; found "
            + structs + " (update this test if the template changed shape)");
        check(impls >= 1, template + " has no 'impl " + struct + " {' block at the start of a line");
        check(defined.size() >= 20, "only " + defined.size() + " names found in " + template + ": " + defined
            + "; the patterns of this test no longer match the template");
        Set<String> missing = new TreeSet<>(defined);
        missing.removeAll(reserved);
        Set<String> stale = new TreeSet<>(reserved);
        stale.removeAll(defined);
        check(missing.isEmpty() && stale.isEmpty(), "the reserved " + struct + " names of RustCodeEmitter differ from "
            + "the members of " + struct + " in " + template + ":\n  defined by the template but not reserved: "
            + missing + "\n  reserved but not defined by the template: " + stale
            + "\nUpdate RustCodeEmitter.RESERVED_" + struct.toUpperCase(Locale.ROOT) + "_NAMES.");
    }

    // =====================================================================
    // names: Java identifiers in Rust
    // =====================================================================

    private void snakeCaseNames() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("count", "count");
        expected.put("lexerPos", "lexer_pos");
        expected.put("isInConditionButNotParenthesized", "is_in_condition_but_not_parenthesized");
        expected.put("MAX_DEPTH", "max_depth");
        expected.put("X", "x");
        // '$' cannot be in a Rust identifier (CongoCC's hooks are named tokenHook$xxx).
        expected.put("tokenHook$counting", "token_hook_counting");
        expected.put("$count", "_count");
        expected.put("a$", "a_");
        expected.put("$", "__");
        // Rust keywords: raw identifiers, or a suffix for those that cannot be raw.
        expected.put("type", "r#type");
        expected.put("match", "r#match");
        expected.put("self", "self_");
        expected.put("Self", "self_");
        expected.put("crate", "crate_");
        // Other Unicode identifiers are kept.
        expected.put("größe", "größe");
        expected.put("café$", "café_");
        List<String> wrong = new ArrayList<>();
        expected.forEach((java, rust) -> {
            String actual = RustJavaTranslator.snakeCase(java);
            if (!actual.equals(rust)) {
                wrong.add(java + " -> " + actual + " (expected " + rust + ")");
            }
        });
        check(wrong.isEmpty(), "RustJavaTranslator.snakeCase gives wrong Rust names:\n  " + String.join("\n  ", wrong));
    }

    /** The type names in diagnostics take the right article ("an int", not "a int"). */
    private void typesWithArticles() {
        Map<RustType, String> expected = new LinkedHashMap<>();
        expected.put(RustType.BOOL, "a boolean");
        expected.put(RustType.INT, "an int");
        expected.put(RustType.LONG, "a long");
        expected.put(RustType.CHAR, "a char");
        expected.put(RustType.STRING, "a String");
        expected.put(RustType.TOKEN_TYPE, "a TokenType");
        expected.put(RustType.TOKEN, "a Token");
        expected.put(RustType.OPT_TOKEN, "a Token");
        expected.put(RustType.LEXICAL_STATE, "a LexicalState");
        expected.put(RustType.NT_CALL, "a NonTerminalCall");
        expected.put(RustType.STACK_ITER, "a ListIterator<NonTerminalCall>");
        expected.put(RustType.NULL, "a null");
        expected.put(RustType.VOID, "a void");
        check(expected.size() == RustType.values().length, "the test does not cover every RustType: "
            + expected.size() + " of " + RustType.values().length + "; add the missing ones");
        List<String> wrong = new ArrayList<>();
        expected.forEach((type, text) -> {
            String actual = type.withArticle();
            if (!actual.equals(text)) {
                wrong.add(type + " -> '" + actual + "' (expected '" + text + "')");
            }
        });
        check(wrong.isEmpty(), "RustType.withArticle gives wrong texts:\n  " + String.join("\n  ", wrong));
    }

    // =====================================================================
    // cli: command line flags and messages
    // =====================================================================

    private void cleanGrammarGeneratesWithoutDiagnostics() throws IOException {
        Path output = work.resolve("cli/clean");
        Run gen = generate(diagnosticsGrammar("clean.ccc"), "rust", output);
        check(gen.status() == 0, "the generation should succeed: " + gen.describe());
        checkContains(gen.stdout(), "Parser generated successfully.", "the generator's output");
        check(gen.stderrLines("Error").isEmpty() && gen.stderrLines("Warning").isEmpty(),
            "a grammar whose Java code can all be translated should give no diagnostics: " + gen.describe());
        String fixme = read(output.resolve("FIXME.md"));
        checkContains(fixme, "All the Java code embedded in the grammar was translated to Rust.", "FIXME.md");
        // FIXME.md tells how to write raw Rust code for the parts that are not translated.
        checkContains(fixme, "The Rust code is copied as it is\ninto a method of the generated parser or lexer", "FIXME.md");
        Run build = cargoBuild(output);
        check(build.status() == 0 && cargoProblems(build).isEmpty(),
            "the crate should build without warnings: " + build.describe());
    }

    private void allowStubsIsIgnoredForJava() throws IOException {
        Path output = work.resolve("cli/java-with-allow-stubs");
        Run gen = generate(diagnosticsGrammar("clean.ccc"), "java", output, "-rust-allow-stubs");
        check(gen.status() == 0, "generating Java with -rust-allow-stubs should succeed: " + gen.describe());
        checkContains(gen.stderr(), "The -rust-allow-stubs flag only applies when generating Rust (-lang rust); it is ignored.",
            "the generator's error output");
        check(Files.isRegularFile(output.resolve("rcgtest/clean/CleanParser.java")),
            "the Java parser rcgtest/clean/CleanParser.java was not generated in " + output);
        checkNotContains(gen.stderr(), "Ignoring unknown flag", "the generator's error output");
    }

    private void allowStubsAcceptsTwoDashes() throws IOException {
        Path output = work.resolve("cli/two-dashes");
        Run gen = generate(diagnosticsGrammar("untranslatable.ccc"), "rust", output, "--rust-allow-stubs");
        check(gen.status() == 0, "--rust-allow-stubs should be accepted like -rust-allow-stubs: " + gen.describe());
        checkNotContains(gen.stderr(), "Ignoring unknown flag", "the generator's error output");
        check(gen.stderrLines("Error").isEmpty(), "with --rust-allow-stubs there should be no errors: " + gen.describe());
    }

    // =====================================================================
    // diagnostics: what cannot be translated
    // =====================================================================

    /**
     * The Java code of untranslatable.ccc that the Rust target cannot
     * translate, at the sites where it changes what is parsed: each is an
     * error that names the site, the reason and the remedies.
     */
    private static final List<String> UNTRANSLATABLE_ERRORS = List.of(
        "untranslatable.ccc:28:11:Cannot translate the semantic lookahead 'System.nanoTime() > 0' to Rust: unknown "
            + "identifier 'System'.",
        "untranslatable.ccc:33:13:Cannot translate the assertion 'System.nanoTime() > 0' to Rust: unknown identifier "
            + "'System'.",
        "untranslatable.ccc:37:21:Cannot translate the code action 'mode = System.identityHashCode(this);' to Rust: "
            + "unknown identifier 'System'.");

    /** The untranslatable Java code of untranslatable.ccc that does not change what is parsed: warnings. */
    private static final List<String> UNTRANSLATABLE_WARNINGS = List.of(
        "untranslatable.ccc:37:54:Cannot translate the code action 'System.out.println(\"logging only\");' to Rust: "
            + "unknown identifier 'System'. The statement is not executed by the Rust parser; its Java code is kept "
            + "as a comment.",
        "untranslatable.ccc:42:29:Cannot translate the INJECT PARSER_CLASS member 'private void unused()' to Rust: "
            + "unknown identifier 'System' (at ");

    private void untranslatableCodeIsAnError() throws IOException {
        Path output = work.resolve("diagnostics/untranslatable-errors");
        Run gen = generate(diagnosticsGrammar("untranslatable.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        checkContains(gen.stdout(), "Parser generated with " + UNTRANSLATABLE_ERRORS.size()
            + " error(s); the generated code is incomplete (see below).", "the generator's output");
        checkDiagnostics(gen, "Error", UNTRANSLATABLE_ERRORS);
        checkDiagnostics(gen, "Warning", UNTRANSLATABLE_WARNINGS);
        for (String error : gen.stderrLines("Error")) {
            checkContains(error, "Possible remedies: rewrite the code using the subset of Java that the Rust target "
                + "translates", "the error");
            checkContains(error, "-rust-allow-stubs", "the error");
        }
        String parser = read(output.resolve("parser.rs"));
        for (String error : UNTRANSLATABLE_ERRORS) {
            // The compile_error! carries the message without its location.
            String message = error.substring(error.indexOf("Cannot translate"));
            checkContains(parser, "compile_error!(\"" + message.replace("\"", "\\\""), "parser.rs");
        }
        checkContains(read(output.resolve("FIXME.md")), "System.nanoTime() > 0", "the translation report in FIXME.md");
        // The crate must not build: the compile_error! carries the message.
        Run build = cargoBuild(output);
        check(build.status() != 0, "a crate with untranslated parse-relevant code must not build: " + build.describe());
        checkContains(build.stderr(), "error: Cannot translate the semantic lookahead 'System.nanoTime() > 0' to Rust",
            "cargo's output");
    }

    private void untranslatableCodeIsStubbed() throws IOException {
        Path output = work.resolve("diagnostics/untranslatable-stubs");
        Run gen = generate(diagnosticsGrammar("untranslatable.ccc"), "rust", output, "-rust-allow-stubs");
        check(gen.status() == 0, "with -rust-allow-stubs the generation should succeed: " + gen.describe());
        // The summary line is not checked: for every target, CongoCC prints
        // "Parser generated successfully." when there are only warnings
        // (Errors.getWarningCount() always returns 0).
        checkNotContains(gen.stdout(), "error(s)", "the generator's output");
        List<String> stubbed = new ArrayList<>(UNTRANSLATABLE_ERRORS);
        stubbed.addAll(UNTRANSLATABLE_WARNINGS);
        checkDiagnostics(gen, "Warning", stubbed);
        for (String warning : gen.stderrLines("Warning")) {
            boolean wasError = UNTRANSLATABLE_ERRORS.stream().anyMatch(e -> warning.contains(e));
            if (wasError) {
                checkContains(warning, "This is stubbed because of -rust-allow-stubs, so the Rust parser may behave "
                    + "differently from the Java parser.", "the warning");
            }
        }
        String parser = read(output.resolve("parser.rs"));
        checkNotContains(parser, "compile_error!", "parser.rs");
        checkContains(parser, "FIXME(congocc): System.nanoTime() > 0", "parser.rs");
        Run build = cargoBuild(output);
        check(build.status() == 0 && cargoProblems(build).isEmpty(),
            "the stubbed crate should build without warnings: " + build.describe());
    }

    /**
     * Checks that the diagnostics of a kind ("Error" or "Warning") are exactly
     * the expected ones: one per expected text, each line containing it.
     */
    private static void checkDiagnostics(Run gen, String kind, List<String> expected) {
        List<String> actual = gen.stderrLines(kind);
        List<String> unmatched = new ArrayList<>(actual);
        List<String> missing = new ArrayList<>();
        for (String text : expected) {
            String line = unmatched.stream().filter(l -> l.contains(text)).findFirst().orElse(null);
            if (line == null) {
                missing.add(text);
            } else {
                unmatched.remove(line);
            }
        }
        check(missing.isEmpty() && unmatched.isEmpty(), "unexpected " + kind.toLowerCase(Locale.ROOT) + "s:\n"
            + "  expected but not reported: " + missing + "\n  reported but not expected: " + unmatched
            + "\nAll the " + kind.toLowerCase(Locale.ROOT) + "s:\n" + indent(String.join("\n", actual)));
    }

    private void reservedMemberNames() throws IOException {
        Path output = work.resolve("diagnostics/reserved");
        Run gen = generate(diagnosticsGrammar("reserved.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        checkDiagnostics(gen, "Error", List.of(
            "reserved.ccc:21:17:Cannot translate the INJECT PARSER_CLASS member 'private int lexerPos;' to Rust: its Rust "
                + "name 'lexer_pos' is already used by the generated Parser; rename the field.",
            "reserved.ccc:23:5:Cannot translate the INJECT PARSER_CLASS member 'boolean typeMatches(int kind)' to Rust: "
                + "its Rust name 'type_matches' is already used by the generated Parser; rename the method.",
            "reserved.ccc:30:17:Cannot translate the INJECT LEXER_CLASS member 'private int chars;' to Rust: its Rust "
                + "name 'chars' is already used by the generated Lexer; rename the field.",
            "reserved.ccc:17:12:Cannot translate the semantic lookahead 'typeMatches(lexerPos)' to Rust: the injected "
                + "method 'typeMatches' could not be translated",
            "reserved.ccc:27:20:Cannot translate the lexical action '{chars++;}' to Rust: the field 'chars' is not "
                + "available in Rust"));
        checkDiagnostics(gen, "Warning", List.of(
            "reserved.ccc:22:17:Cannot translate the INJECT PARSER_CLASS member 'private int pos;' to Rust: its Rust "
                + "name 'pos' is already used by the generated Parser; rename the field.",
            "reserved.ccc:24:5:Cannot translate the INJECT PARSER_CLASS member 'void parseRoot()' to Rust: its Rust "
                + "name 'parse_root' is already used by the function generated for the production root; rename the "
                + "method."));
    }

    private void rustNameCollisions() throws IOException {
        Path output = work.resolve("diagnostics/collisions");
        Run gen = generate(diagnosticsGrammar("collisions.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        String reason = "the injected members 'fooBar', 'foo_bar' would all be called 'foo_bar' in Rust; rename them";
        checkDiagnostics(gen, "Error", List.of(
            "collisions.ccc:19:17:Cannot translate the INJECT PARSER_CLASS member 'private int fooBar;' to Rust: " + reason,
            "collisions.ccc:20:5:Cannot translate the INJECT PARSER_CLASS member 'private boolean foo_bar()' to Rust: "
                + reason,
            "collisions.ccc:16:15:Cannot translate the semantic lookahead 'fooBar > 0 || foo_bar()' to Rust"));
        String unused = "the injected members 'x$y', 'x_y' would all be called 'x_y' in Rust; rename them";
        checkDiagnostics(gen, "Warning", List.of(
            "collisions.ccc:21:17:Cannot translate the INJECT PARSER_CLASS member 'private int x$y;' to Rust: " + unused,
            "collisions.ccc:22:17:Cannot translate the INJECT PARSER_CLASS member 'private int x_y;' to Rust: " + unused));
    }

    /**
     * An injected method's Token parameter is a '&mut Token' in Rust: a token
     * that is not held in a variable cannot be passed, a local variable can.
     */
    private void tokenArguments() throws IOException {
        Path output = work.resolve("diagnostics/token-arguments");
        Run gen = generate(diagnosticsGrammar("token-arguments.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        checkDiagnostics(gen, "Error", List.of(
            "token-arguments.ccc:14:19:Cannot translate the semantic lookahead 'isA(getToken(1))' to Rust: the injected "
                + "method 'isA' takes a Token, which the Rust target passes as a '&mut Token', so the argument must be "
                + "a local variable, a Token parameter or matchedToken; assign the token to a local variable first (for "
                + "example 'Token next = getToken(1);' in a code block) and pass the variable."));
        checkDiagnostics(gen, "Warning", List.of());
        String parser = read(output.resolve("parser.rs"));
        checkContains(parser, "let mut next: Token = self.get_token(1);", "parser.rs (the local of Other)");
        checkContains(parser, "self.is_a(&mut next)", "parser.rs (the call with the local of Other)");
    }

    private void reachability() throws IOException {
        Path output = work.resolve("diagnostics/reachability");
        Run gen = generate(diagnosticsGrammar("reachability.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        // The lexical action calls calledFromAction; the semantic lookahead
        // calls check, which calls calledIndirectly: all four are errors.
        checkDiagnostics(gen, "Error", List.of(
            "reachability.ccc:23:39:Cannot translate the INJECT LEXER_CLASS member 'private void calledFromAction()' "
                + "to Rust: unknown identifier 'System'",
            "reachability.ccc:18:49:Cannot translate the INJECT PARSER_CLASS member 'private boolean calledIndirectly()' "
                + "to Rust: unknown identifier 'System'",
            "reachability.ccc:17:38:Cannot translate the INJECT PARSER_CLASS member 'private boolean check()' to Rust: "
                + "the injected method 'calledIndirectly' could not be translated",
            "reachability.ccc:12:20:Cannot translate the lexical action '{calledFromAction();}' to Rust",
            "reachability.ccc:14:15:Cannot translate the semantic lookahead 'check()' to Rust"));
        checkDiagnostics(gen, "Warning", List.of(
            "reachability.ccc:24:34:Cannot translate the INJECT LEXER_CLASS member 'private void neverCalled()' to Rust: "
                + "unknown identifier 'System' (at ",
            "reachability.ccc:19:40:Cannot translate the INJECT PARSER_CLASS member 'private void neverCalledEither()' "
                + "to Rust: unknown identifier 'System' (at ",
            "reachability.ccc:25:5:Cannot translate the grammar feature 'tokenHook' to Rust: the lexer token hook "
                + "'tokenHook' is Java code that the generated Java lexer calls automatically. The Rust lexer does not "
                + "call it"));
    }

    /**
     * Code just outside the translated Java subset (a LexicalState field, a
     * String field, a mutable static field, a cast to char, a compound
     * assignment of a long to an int) is reported where it is written, and so
     * is every site that uses it.
     */
    private void outsideTheSubset() throws IOException {
        Path output = work.resolve("diagnostics/outside-the-subset");
        Run gen = generate(diagnosticsGrammar("outside-the-subset.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        checkDiagnostics(gen, "Error", List.of(
            "outside-the-subset.ccc:17:26:Cannot translate the INJECT LEXER_CLASS member 'private LexicalState next = "
                + "LexicalState.DEFAULT;' to Rust: fields of type 'LexicalState' are not supported (supported: boolean, "
                + "int, long, char, TokenType and Token). The field is not generated, and code using it cannot be "
                + "translated. Possible remedies: ",
            "outside-the-subset.ccc:21:20:Cannot translate the INJECT PARSER_CLASS member 'private String lastName;' to "
                + "Rust: String fields are not supported: a Rust parser cannot hold strings computed at run time in a "
                + "'&'static str'. The field is not generated",
            "outside-the-subset.ccc:22:24:Cannot translate the INJECT PARSER_CLASS member 'private static int "
                + "instances;' to Rust: mutable static fields (shared by all parser instances in Java) are not "
                + "supported. The field is not generated",
            "outside-the-subset.ccc:23:58:Cannot translate the INJECT PARSER_CLASS member 'private boolean "
                + "nextLetterIsB(int offset)' to Rust: the cast from int to char is not supported (at ",
            "outside-the-subset.ccc:24:54:Cannot translate the INJECT PARSER_CLASS member 'private boolean grows(long "
                + "step)' to Rust: compound assignment of a long to an int is not supported (at ",
            "outside-the-subset.ccc:14:29:Cannot translate the lexical action '{switchTo(next);}' to Rust: the field "
                + "'next' is not available in Rust: fields of type 'LexicalState' are not supported",
            "outside-the-subset.ccc:29:15:Cannot translate the semantic lookahead 'nextLetterIsB(1)' to Rust: the "
                + "injected method 'nextLetterIsB' could not be translated (the cast from int to char is not supported",
            "outside-the-subset.ccc:30:15:Cannot translate the semantic lookahead 'grows(1)' to Rust: the injected "
                + "method 'grows' could not be translated (compound assignment of a long to an int is not supported",
            "outside-the-subset.ccc:31:15:Cannot translate the semantic lookahead 'lastName == null' to Rust: the "
                + "field 'lastName' is not available in Rust: String fields are not supported",
            "outside-the-subset.ccc:32:15:Cannot translate the semantic lookahead 'instances == 0' to Rust: the "
                + "field 'instances' is not available in Rust: mutable static fields"));
        checkDiagnostics(gen, "Warning", List.of());
    }

    private void unimplementedFeaturesAreWarnings() throws IOException {
        Path output = work.resolve("diagnostics/unimplemented");
        Run gen = generate(diagnosticsGrammar("unimplemented.ccc"), "rust", output);
        check(gen.status() == 0, "unimplemented features are warnings, so the generation should succeed: " + gen.describe());
        checkDiagnostics(gen, "Error", List.of());
        checkDiagnostics(gen, "Warning", List.of(
            "unimplemented.ccc:17:9:Cannot translate the grammar feature '&1:2&' to Rust: repetition cardinality "
                + "constraints are not implemented by the Rust target. The constraint is not checked (the Rust parser "
                + "accepts any number of repetitions). If the Rust parser must reject the same inputs, count the "
                + "repetitions in a code action and check the count with an ASSERT instead.",
            "unimplemented.ccc:8:1:Cannot translate the grammar feature 'FAULT_TOLERANT' to Rust: fault-tolerant parsing "
                + "is not implemented by the Rust target. The Rust parser stops at the first syntax error. If the Rust "
                + "parser must behave like the Java parser, do not use this setting; to keep it for the other targets "
                + "only, put it inside '#if !__rust__ ... #endif'.",
            "unimplemented.ccc:9:1:Cannot translate the grammar feature 'UNPARSED_TOKENS_ARE_NODES' to Rust: adding "
                + "unparsed tokens (such as comments) to the tree is not implemented by the Rust target.",
            "unimplemented.ccc:21:5:Cannot translate the grammar feature 'tokenHook$counting' to Rust: the parser token "
                + "hook 'tokenHook$counting' is Java code that the generated Java parser calls automatically. The Rust "
                + "parser does not call it, so it may see different tokens than the Java parser. If the Rust parser "
                + "needs what the hook does, do it in the grammar's code actions instead; to keep the hook for the "
                + "other targets only, put it inside '#if !__rust__ ... #endif'.",
            "unimplemented.ccc:22:5:Cannot translate the grammar feature 'openNodeHook$counting' to Rust: the open node "
                + "scope hook",
            "unimplemented.ccc:23:5:Cannot translate the grammar feature 'closeNodeHook$counting' to Rust: the close "
                + "node scope hook",
            // The node hooks take a Node, which the Rust target has no type for.
            "unimplemented.ccc:22:40:Cannot translate the INJECT PARSER_CLASS member 'private void "
                + "openNodeHook$counting(Node node)' to Rust: the Java type 'Node' has no counterpart in the Rust target",
            "unimplemented.ccc:23:41:Cannot translate the INJECT PARSER_CLASS member 'private void "
                + "closeNodeHook$counting(Node node)' to Rust: the Java type 'Node' has no counterpart in the Rust target"));
        Run build = cargoBuild(output);
        check(build.status() == 0 && cargoProblems(build).isEmpty(),
            "the crate should build without warnings: " + build.describe());
    }

    /**
     * The remedy that the warnings of unimplemented.ccc suggest: the same
     * features inside '#if !__rust__ ... #endif' are kept for the Java parser
     * and give no diagnostics for the Rust target.
     */
    private void unimplementedFeaturesForOtherTargetsOnly() throws IOException {
        Path grammar = diagnosticsGrammar("unimplemented-other-targets.ccc");
        Path output = work.resolve("diagnostics/unimplemented-other-targets");
        Run gen = generate(grammar, "rust", output);
        check(gen.status() == 0 && gen.stderrLines("Error").isEmpty() && gen.stderrLines("Warning").isEmpty(),
            "the features inside '#if !__rust__' should not be seen by the Rust target: " + gen.describe());
        Path java = work.resolve("diagnostics/unimplemented-other-targets-java");
        Run javaGen = generate(grammar, "java", java);
        check(javaGen.status() == 0, "the Java parser generation should succeed: " + javaGen.describe());
        String parser = read(java.resolve("rcgtest/unimplemented/otherTargets/OtherTargetsParser.java"));
        checkContains(parser, "tokenHook$counting", "the Java parser (the hook is kept for Java)");
        // Only a fault-tolerant Java parser has the recovery state.
        checkContains(parser, "pendingRecovery", "the Java parser (FAULT_TOLERANT is kept for Java)");
    }

    private void tryAndAttemptAreErrors() throws IOException {
        Path output = work.resolve("diagnostics/try-attempt-errors");
        Run gen = generate(diagnosticsGrammar("tryattempt.ccc"), "rust", output);
        check(gen.status() == 1, "the generation should fail with status 1: " + gen.describe());
        checkDiagnostics(gen, "Error", List.of(
            "tryattempt.ccc:16:5:Cannot translate the grammar feature 'try { \"a\" \";\" } catch (ParseException e) { }' "
                + "to Rust: TRY/CATCH blocks are not implemented by the Rust target. The generated Rust code contains a "
                + "compile_error! here (with -rust-allow-stubs, the expansion is parsed without the CATCH and FINALLY "
                + "code). Possible remedies: write this part of the grammar differently for the Rust target (with "
                + "'#if __rust__ ... #else ... #endif'); or generate with -rust-allow-stubs, which replaces it as described.",
            "tryattempt.ccc:18:5:Cannot translate the grammar feature 'ATTEMPT \"b\" \";\" RECOVER (\"b\")' to Rust: "
                + "ATTEMPT/RECOVER blocks are not implemented by the Rust target."));
        String parser = read(output.resolve("parser.rs"));
        checkContains(parser, "compile_error!(\"Cannot translate the grammar feature 'try", "parser.rs");
        checkContains(parser, "compile_error!(\"Cannot translate the grammar feature 'ATTEMPT", "parser.rs");
        Run build = cargoBuild(output);
        check(build.status() != 0, "a crate with an unimplemented construct must not build: " + build.describe());
        checkContains(build.stderr(), "TRY/CATCH blocks are not implemented by the Rust target", "cargo's output");
    }

    private void tryAndAttemptWithAllowStubs() throws IOException {
        Path output = work.resolve("diagnostics/try-attempt-stubs");
        Run gen = generate(diagnosticsGrammar("tryattempt.ccc"), "rust", output, "-rust-allow-stubs");
        check(gen.status() == 0, "with -rust-allow-stubs the generation should succeed: " + gen.describe());
        checkDiagnostics(gen, "Warning", List.of(
            "tryattempt.ccc:16:5:Cannot translate the grammar feature 'try { \"a\" \";\" } catch (ParseException e) { }' "
                + "to Rust: TRY/CATCH blocks are not implemented by the Rust target. The expansion is parsed without the "
                + "CATCH and FINALLY code. This is stubbed because of -rust-allow-stubs, so the Rust parser may behave "
                + "differently from the Java parser. If that matters, write this part of the grammar differently for "
                + "the Rust target (with '#if __rust__ ... #else ... #endif').",
            "tryattempt.ccc:18:5:Cannot translate the grammar feature 'ATTEMPT \"b\" \";\" RECOVER (\"b\")' to Rust: "
                + "ATTEMPT/RECOVER blocks are not implemented by the Rust target. The ATTEMPT expansion is parsed "
                + "without recovery."));
        String parser = read(output.resolve("parser.rs"));
        checkContains(parser, "// FIXME(congocc): TRY/CATCH blocks are not implemented by the Rust target", "parser.rs");
        checkContains(parser, "// FIXME(congocc): ATTEMPT/RECOVER blocks are not implemented by the Rust target",
            "parser.rs");
        Run build = cargoBuild(output);
        check(build.status() == 0 && cargoProblems(build).isEmpty(),
            "the stubbed crate should build without warnings: " + build.describe());
    }

    // =====================================================================
    // features: the Java and the Rust parser build the same trees
    // =====================================================================

    /** A feature grammar and its inputs. */
    private static final class Feature {
        final String name;
        final Path grammar;
        final Path inputs;
        final List<Path> inputFiles;
        final String parserPackage;
        final String parserClass;
        final String lexerClass;
        final Path javaSource;
        final Path javaClasses;
        final Path rustCrate;
        /** The Java parser's dumps, by input file name; null until computed. */
        Map<String, String> javaDumps;
        /** Why the feature could not be prepared; null if it was. */
        String setupFailure;

        Feature(String name, Path grammar, Path inputs, List<Path> inputFiles, Map<String, String> settings, Path work) {
            this.name = name;
            this.grammar = grammar;
            this.inputs = inputs;
            this.inputFiles = inputFiles;
            this.parserPackage = settings.get("PARSER_PACKAGE");
            this.parserClass = settings.get("PARSER_CLASS");
            this.lexerClass = settings.get("LEXER_CLASS");
            this.javaSource = work.resolve("features").resolve(name).resolve("java-src");
            this.javaClasses = work.resolve("features").resolve(name).resolve("java-classes");
            this.rustCrate = work.resolve("features").resolve(name).resolve("rust");
        }

        String crateName() {
            return parserPackage.replace('.', '_');
        }
    }

    private static final Pattern SETTING = Pattern.compile("^(PARSER_PACKAGE|PARSER_CLASS|LEXER_CLASS)\\s*=\\s*([\\w.]+)\\s*;",
        Pattern.MULTILINE);

    /** The tests of each feature, in the order they run: each needs the previous ones. */
    private static final List<String> FEATURE_STEPS = List.of("java-parser", "rust-generation", "trees");

    /**
     * Runs the feature tests.  The tests of a feature depend on each other
     * (the trees are compared once both parsers are generated), and they all
     * need the discovery and the harness: when {@code --only} selects one of
     * them, they all run.  Selecting {@code features/discover} or
     * {@code features/harness-build} runs the tests of every feature.
     */
    private void featureTests() {
        Path directory = repo.resolve("tests/rust-codegen/features");
        boolean everyFeature = selected("features/discover") || selected("features/harness-build");
        Set<String> wanted = new TreeSet<>();
        for (String name : featureNames(directory)) {
            if (everyFeature || FEATURE_STEPS.stream().anyMatch(step -> selected("features/" + name + "/" + step))) {
                wanted.add(name);
            }
        }
        if (!everyFeature && wanted.isEmpty()) {
            return;
        }
        List<Feature> features = new ArrayList<>();
        test("features/discover", () -> {
            List<Path> grammars;
            try (Stream<Path> list = Files.list(directory)) {
                grammars = list.filter(p -> p.toString().endsWith(".ccc")).sorted().collect(Collectors.toList());
            }
            check(!grammars.isEmpty(), "no feature grammars (*.ccc) in " + directory);
            for (Path grammar : grammars) {
                String name = grammar.getFileName().toString().replaceFirst("\\.ccc$", "");
                if (!wanted.contains(name)) {
                    continue;
                }
                Map<String, String> settings = new LinkedHashMap<>();
                Matcher m = SETTING.matcher(read(grammar));
                while (m.find()) {
                    settings.put(m.group(1), m.group(2));
                }
                check(settings.size() == 3, grammar + " must set PARSER_PACKAGE, PARSER_CLASS and LEXER_CLASS "
                    + "(RustCodegenTest needs them to find the Java parser); found " + settings);
                Path inputs = directory.resolve(name);
                List<Path> files;
                try (Stream<Path> list = Files.list(inputs)) {
                    files = list.filter(p -> {
                        String f = p.getFileName().toString();
                        return f.startsWith("ok-") || f.startsWith("bad-");
                    }).sorted().collect(Collectors.toList());
                }
                check(files.stream().anyMatch(p -> p.getFileName().toString().startsWith("ok-"))
                    && files.stream().anyMatch(p -> p.getFileName().toString().startsWith("bad-")),
                    inputs + " must contain inputs that parse (ok-*) and inputs that do not (bad-*)");
                features.add(new Feature(name, grammar, inputs, files, settings, work));
            }
        }, true);
        for (Feature feature : features) {
            test("features/" + feature.name + "/" + FEATURE_STEPS.get(0), () -> prepareJava(feature), true);
            test("features/" + feature.name + "/" + FEATURE_STEPS.get(1), () -> prepareRust(feature), true);
        }
        if (features.isEmpty()) {
            return;
        }
        Path harness = work.resolve("features/harness");
        boolean[] built = new boolean[1];
        test("features/harness-build", () -> {
            buildHarness(harness, features);
            built[0] = true;
        }, true);
        for (Feature feature : features) {
            test("features/" + feature.name + "/" + FEATURE_STEPS.get(2), () -> {
                check(feature.setupFailure == null, "the feature could not be prepared: " + feature.setupFailure);
                check(built[0], "the Rust harness did not build (see features/harness-build)");
                compareTrees(feature, harness);
            }, true);
        }
    }

    /**
     * @return the names of the feature grammars ({@code <name>.ccc}) in the
     *         directory, or none if it cannot be listed (the
     *         {@code features/discover} test then reports why)
     */
    private static List<String> featureNames(Path directory) {
        try (Stream<Path> list = Files.list(directory)) {
            return list.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".ccc"))
                .map(f -> f.substring(0, f.length() - ".ccc".length())).sorted().collect(Collectors.toList());
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** Generates and compiles the Java parser of a feature grammar, and parses every input with it. */
    private void prepareJava(Feature feature) throws Exception {
        try {
            Run gen = generate(feature.grammar, "java", feature.javaSource);
            check(gen.status() == 0 && gen.stderrLines("Error").isEmpty(),
                "the Java parser generation should succeed: " + gen.describe());
            compileJava(feature.javaSource, feature.javaClasses);
            feature.javaDumps = javaDumps(feature);
            for (Map.Entry<String, String> entry : feature.javaDumps.entrySet()) {
                String name = entry.getKey();
                String dump = entry.getValue();
                boolean failed = dump.lines().anyMatch(l -> l.startsWith("error ") || l.startsWith("crash "));
                check(!dump.lines().anyMatch(l -> l.startsWith("crash ")), "the Java parser crashed on "
                    + feature.inputs.resolve(name) + " (fix the feature grammar or its input):\n" + indent(dump));
                if (name.startsWith("ok-")) {
                    check(!failed, feature.inputs.resolve(name) + " should parse (its name starts with ok-), but the Java "
                        + "parser reports an error:\n" + indent(dump));
                } else {
                    check(failed, feature.inputs.resolve(name) + " should not parse (its name starts with bad-), but the "
                        + "Java parser accepts it:\n" + indent(dump));
                }
            }
        } catch (Throwable t) {
            feature.setupFailure = "the Java side failed: " + t.getMessage();
            throw t;
        }
    }

    private void prepareRust(Feature feature) throws Exception {
        try {
            Run gen = generate(feature.grammar, "rust", feature.rustCrate);
            check(gen.status() == 0, "the Rust parser generation should succeed: " + gen.describe());
            check(gen.stderrLines("Error").isEmpty() && gen.stderrLines("Warning").isEmpty(),
                "the Java code of a feature grammar should all be translated to Rust: " + gen.describe());
            checkContains(read(feature.rustCrate.resolve("FIXME.md")),
                "All the Java code embedded in the grammar was translated to Rust.", "FIXME.md");
            Path expect = feature.inputs.resolve("rust-code.expect");
            if (Files.exists(expect)) {
                for (String line : Files.readAllLines(expect)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    int colon = line.indexOf(": ");
                    check(colon > 0, expect + ": the line '" + line + "' is not of the form '<file>: <text>'");
                    String file = line.substring(0, colon);
                    checkContains(read(feature.rustCrate.resolve(file)), line.substring(colon + 2),
                        "the generated " + file + " (as " + expect.getFileName() + " requires)");
                }
            }
        } catch (Throwable t) {
            feature.setupFailure = (feature.setupFailure == null ? "" : feature.setupFailure + "; ")
                + "the Rust generation failed: " + t.getMessage();
            throw t;
        }
    }

    private static void compileJava(Path sources, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "no Java compiler: run the tests with a JDK, not a JRE");
        deleteRecursively(classes);
        Files.createDirectories(classes);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, manager, diagnostics,
                List.of("-d", classes.toString(), "-encoding", "UTF-8", "-nowarn", "-proc:none", "-Xlint:none"),
                null, manager.getJavaFileObjectsFromPaths(files)).call();
            check(ok, "the generated Java parser in " + sources + " does not compile:\n" + indent(diagnostics.getDiagnostics()
                .stream().map(d -> d.getSource() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"))));
        }
    }

    /**
     * Parses every input of a feature with its Java parser, loaded in its own
     * class loader, and returns the dumps by file name.
     */
    private static Map<String, String> javaDumps(Feature feature) throws Exception {
        Map<String, String> dumps = new LinkedHashMap<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(null, r, "java-parser", 256L * 1024 * 1024);
            t.setDaemon(true);
            return t;
        });
        try (URLClassLoader loader = new URLClassLoader(new URL[] {feature.javaClasses.toUri().toURL()},
                                                         ClassLoader.getPlatformClassLoader())) {
            JavaParserApi api = new JavaParserApi(loader, feature);
            for (Path input : feature.inputFiles) {
                byte[] bytes = Files.readAllBytes(input);
                String name = input.getFileName().toString();
                Future<String> dump = executor.submit(() -> api.dump(name, bytes));
                try {
                    dumps.put(name, dump.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    fail("the Java parser did not finish parsing " + input + " within " + PARSE_TIMEOUT_SECONDS + " seconds");
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return dumps;
    }

    /** The parts of a generated Java parser that the dump uses, found by reflection. */
    private static final class JavaParserApi {
        final Method stringFromBytes;
        final Constructor<?> newLexer;
        final Constructor<?> newParser;
        final Method root;
        final Method rootNode;
        final Class<?> token;
        final Method size;
        final Method get;
        final Method begin;
        final Method end;
        final Method type;
        final Class<?> parseException;
        final Method exceptionToken;
        final Method beginLine;
        final Method beginColumn;

        JavaParserApi(ClassLoader loader, Feature feature) throws ReflectiveOperationException {
            String p = feature.parserPackage + ".";
            Class<?> lexer = loader.loadClass(p + feature.lexerClass);
            Class<?> parser = loader.loadClass(p + feature.parserClass);
            Class<?> node = loader.loadClass(p + "Node");
            stringFromBytes = loader.loadClass(p + "TokenSource").getMethod("stringFromBytes", byte[].class);
            newLexer = lexer.getConstructor(String.class, CharSequence.class);
            newParser = parser.getConstructor(lexer);
            // The Rust parser starts with the first production; the feature
            // grammars name it Root.
            root = parser.getMethod("Root");
            rootNode = parser.getMethod("rootNode");
            token = loader.loadClass(p + "Token");
            size = node.getMethod("size");
            get = node.getMethod("get", int.class);
            begin = node.getMethod("getBeginOffset");
            end = node.getMethod("getEndOffset");
            type = token.getMethod("getType");
            parseException = loader.loadClass(p + "ParseException");
            exceptionToken = parseException.getMethod("getToken");
            beginLine = node.getMethod("getBeginLine");
            beginColumn = node.getMethod("getBeginColumn");
        }

        /** The dump of the input, in the format of examples/rust/TreeDump.java. */
        String dump(String name, byte[] bytes) throws ReflectiveOperationException {
            String text = (String) stringFromBytes.invoke(null, (Object) bytes);
            Object lexer = newLexer.newInstance(name, text);
            Object parser = newParser.newInstance(lexer);
            String content = lexer.toString();
            int[] index = codePointIndex(content);
            StringBuilder out = new StringBuilder();
            out.append(FORMAT_HEADER).append('\n');
            out.append("content ").append(index[content.length()]).append(' ')
               .append(String.format("%016x", fnv1a64(content.getBytes(StandardCharsets.UTF_8)))).append('\n');
            try {
                root.invoke(parser);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (parseException.isInstance(cause)) {
                    Object at = exceptionToken.invoke(cause);
                    out.append("error ").append(at == null ? "?" : beginLine.invoke(at) + ":" + beginColumn.invoke(at)).append('\n');
                } else {
                    out.append("crash ").append(cause).append('\n');
                }
                return out.toString();
            }
            List<Object> nodes = new ArrayList<>();
            List<Integer> depths = new ArrayList<>();
            Object rootValue = rootNode.invoke(parser);
            if (rootValue == null) {
                out.append("crash the parse succeeded without building a tree\n");
                return out.toString();
            }
            nodes.add(rootValue);
            depths.add(0);
            while (!nodes.isEmpty()) {
                Object node = nodes.remove(nodes.size() - 1);
                int depth = depths.remove(depths.size() - 1);
                out.append(depth).append(' ');
                if (token.isInstance(node)) {
                    out.append("Token ").append(type.invoke(node));
                } else {
                    out.append(node.getClass().getSimpleName());
                }
                out.append(' ').append(index[(int) begin.invoke(node)]).append(' ').append(index[(int) end.invoke(node)])
                   .append('\n');
                int children = (int) size.invoke(node);
                for (int i = children - 1; i >= 0; i--) {
                    nodes.add(get.invoke(node, i));
                    depths.add(depth + 1);
                }
            }
            return out.toString();
        }
    }

    /** @return for each UTF-16 offset of {@code content} (and its length), the number of code points before it */
    static int[] codePointIndex(String content) {
        int[] index = new int[content.length() + 1];
        int codePoints = 0;
        for (int i = 0; i < content.length(); i++) {
            index[i] = codePoints;
            if (!(Character.isLowSurrogate(content.charAt(i)) && i > 0 && Character.isHighSurrogate(content.charAt(i - 1)))) {
                codePoints++;
            } else {
                // The low half of a surrogate pair belongs to the pair's code point.
                index[i] = index[i - 1];
            }
        }
        index[content.length()] = codePoints;
        return index;
    }

    /** The 64-bit FNV-1a hash, which the Rust harness computes in the same way. */
    static long fnv1a64(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Writes the feature harness crate (Cargo.toml with one dependency per
     * feature crate, src/main.rs from tests/rust-codegen/harness, and
     * src/features.rs) and builds it.
     */
    private void buildHarness(Path harness, List<Feature> features) throws IOException {
        List<Feature> ready = features.stream().filter(f -> f.setupFailure == null).collect(Collectors.toList());
        check(!ready.isEmpty(), "no feature could be prepared");
        Files.createDirectories(harness.resolve("src"));
        StringBuilder manifest = new StringBuilder("""
            # Written by RustCodegenTest; do not edit.
            [package]
            name = "rcg-feature-harness"
            version = "0.1.0"
            edition = "2024"
            publish = false

            # Its own workspace, whatever the directories above contain.
            [workspace]

            [dependencies]
            """);
        StringBuilder registry = new StringBuilder("// Written by RustCodegenTest; do not edit.\n\n");
        StringBuilder dispatch = new StringBuilder("""

            /// The dumper of a feature, by the name of its grammar.
            fn dumper(feature: &str) -> Option<&'static Dumper> {
                match feature {
            """);
        for (Feature f : ready) {
            manifest.append(f.parserPackage.replace('.', '-')).append(" = { path = \"")
                    .append(f.rustCrate.toString().replace("\\", "/")).append("\" }\n");
            registry.append("feature_dumper!(").append(f.name).append(", ").append(f.crateName()).append(");\n");
            dispatch.append("        \"").append(f.name).append("\" => Some(&").append(f.name).append("::DUMPER),\n");
        }
        dispatch.append("        _ => None,\n    }\n}\n");
        Files.writeString(harness.resolve("Cargo.toml"), manifest);
        Files.writeString(harness.resolve("src/features.rs"), registry.append(dispatch));
        Files.copy(repo.resolve("tests/rust-codegen/harness/main.rs"), harness.resolve("src/main.rs"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Run build = run(harness, Map.of("CARGO_TARGET_DIR", work.resolve("cargo-target/features").toString()),
            List.of("cargo", "build", "--color", "never"));
        check(build.status() == 0, "the feature harness does not build: " + build.describe());
        check(cargoProblems(build).isEmpty(), "the generated crates and the harness should build without warnings: "
            + build.describe());
    }

    private void compareTrees(Feature feature, Path harness) throws IOException {
        Path out = work.resolve("features").resolve(feature.name).resolve("rust-dumps");
        deleteRecursively(out);
        Files.createDirectories(out);
        List<String> command = new ArrayList<>(List.of(
            work.resolve("cargo-target/features/debug/rcg-feature-harness").toString(), feature.name, out.toString()));
        feature.inputFiles.forEach(p -> command.add(p.toString()));
        Run run = run(harness, Map.of(), command);
        check(run.status() == 0, "the feature harness failed: " + run.describe());
        List<String> differences = new ArrayList<>();
        for (Path input : feature.inputFiles) {
            String name = input.getFileName().toString();
            String java = feature.javaDumps.get(name);
            Path rustDump = out.resolve(name + ".tree");
            check(Files.exists(rustDump), "the harness wrote no dump for " + input + " in " + out);
            String rust = read(rustDump);
            if (!java.equals(rust)) {
                differences.add(describeDifference(input, java, rust));
            }
        }
        check(differences.isEmpty(), differences.size() + " of " + feature.inputFiles.size()
            + " inputs give different results with the Java and the Rust parser:\n" + String.join("\n", differences));
    }

    private static String describeDifference(Path input, String java, String rust) {
        List<String> j = java.lines().collect(Collectors.toList());
        List<String> r = rust.lines().collect(Collectors.toList());
        int line = 0;
        while (line < j.size() && line < r.size() && j.get(line).equals(r.get(line))) {
            line++;
        }
        return input + ": the dumps differ at line " + (line + 1) + "\n    Java: "
            + (line < j.size() ? j.get(line) : "(end of dump)") + "\n    Rust: " + (line < r.size() ? r.get(line) : "(end of dump)")
            + "\n  Java dump:\n" + indent(java) + "\n  Rust dump:\n" + indent(rust);
    }
}
