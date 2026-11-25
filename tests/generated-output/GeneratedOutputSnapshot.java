/*
 * GeneratedOutputSnapshot -- a regression guard for CongoCC's code generators.
 *
 * WHY THIS EXISTS
 * ---------------
 * CongoCC generates complete, self-contained parsers (there is no separate
 * runtime library for the Java, Python or C# targets).  Therefore, if a change
 * to the generator leaves the generated files byte-for-byte identical, the
 * generated parsers cannot have changed behavior.  This tool makes that check
 * mechanical: it generates every grammar in the repository for every target
 * language with a given congocc.jar, stores the output (plus the generator's
 * console output and exit code) in a snapshot directory, and compares two
 * snapshots.
 *
 * The typical workflow is:
 *
 *   1. Build congocc.jar from the unmodified tree and take a baseline snapshot.
 *   2. Make the change, rebuild congocc.jar and take a candidate snapshot.
 *   3. Compare the snapshots for the languages that must not change.
 *
 * USAGE
 * -----
 *   java GeneratedOutputSnapshot.java snapshot --jar congocc.jar --out DIR
 *        [--repo REPO_ROOT] [--langs java,python,csharp,rust] [--work DIR]
 *        [--jobs N] [--timeout-seconds N] [--identity-hash constant|default]
 *
 *   java GeneratedOutputSnapshot.java compare BASELINE_DIR CANDIDATE_DIR
 *        [--langs java,python,csharp]
 *
 *   java GeneratedOutputSnapshot.java self-test
 *
 * self-test checks the comparison itself on small synthetic snapshots (it
 * does not run the generator); it exits with status 1 if a check fails.
 *
 * Example (also wrapped by the Ant targets "snapshot-generated-output" and
 * "compare-generated-output" in the top-level build.xml):
 *
 *   java tests/generated-output/GeneratedOutputSnapshot.java snapshot \
 *        --jar congocc.jar --out /tmp/snap-before --jobs 4
 *   ... change the generator, run "ant jar" ...
 *   java tests/generated-output/GeneratedOutputSnapshot.java snapshot \
 *        --jar congocc.jar --out /tmp/snap-after --jobs 4
 *   java tests/generated-output/GeneratedOutputSnapshot.java compare \
 *        /tmp/snap-before /tmp/snap-after --langs java,python,csharp
 *
 * DESIGN NOTES
 * ------------
 * - Grammars are generated inside a private "mirror" of the repository that
 *   contains only the .ccc files.  This matters because some grammars (for
 *   example Preprocessor.ccc and the grammars in src/grammars) set
 *   BASE_SRC_DIR, which takes precedence over the -d option.  Generating those
 *   grammars in the real repository would overwrite build/generated-java.  In
 *   the mirror, BASE_SRC_DIR resolves to a directory inside the mirror, and
 *   every file the generator writes is collected into the snapshot.
 * - Each generator run uses the mirror root as its working directory and the
 *   grammar's mirror-relative path as its argument, so the source-location
 *   comments that CongoCC embeds in generated code ("specified at
 *   examples/rust/Rust.ccc:20:1") are identical between runs.
 * - The generator's console output is stored with the mirror and jar paths
 *   replaced by placeholders, so error messages for grammars that are
 *   expected to fail are compared as well.
 * - The comparison is byte-for-byte.  There is no normalization of generated
 *   files.  The only exception is in the generator's console output: the
 *   line numbers of the stack trace frames of CongoCC's own classes (printed
 *   when the generator fails with an exception) change whenever the
 *   generator's source is edited, so they are ignored, and every log that
 *   differs only in them is listed as a note.
 * - The generator is run with constant identity hash codes
 *   (-XX:hashCode=2, "--identity-hash constant", the default).  Some of the
 *   generator's data structures are hash sets of objects that do not override
 *   hashCode() (the NFA states of the lexer, for example), and the order in
 *   which they are iterated decides, among other things, the numbers of the
 *   NFA states in the generated lexers.  With the JVM's default identity
 *   hash codes, that order depends on how many identity hash codes the
 *   generator happened to request before, so any change to the generator's
 *   code, even one that is never executed for a given target, can renumber
 *   the states of every lexer without changing what it does.  Two runs of the
 *   same jar give the same output either way.  With constant identity hash
 *   codes, the output depends only on what the generator's code does, which
 *   is what the comparison is meant to check.  "--identity-hash default"
 *   runs the generator as users do; compare refuses to compare snapshots
 *   taken in different modes.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GeneratedOutputSnapshot {

    /** Directories (relative to the repository root) that are searched for grammars. */
    private static final List<String> GRAMMAR_ROOTS = List.of("examples", "src/grammars", "sandbox", "tests");

    /**
     * Directory names that never contain grammars of their own but can contain
     * large amounts of generated or compiled output.  Skipping them keeps the
     * grammar discovery fast and avoids picking up copies of grammars.
     */
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", "node_modules", "bin", "obj");

    /** The default set of target languages, i.e. every language CongoCC supports. */
    private static final List<String> ALL_LANGS = List.of("java", "python", "csharp", "rust");

    private static final String LOG_FILE = "generator.log";

    private GeneratedOutputSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usageAndExit("No command given.");
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int status;
        switch (command) {
            case "snapshot" -> status = snapshot(Options.parse(rest, false));
            case "compare" -> status = compare(Options.parse(rest, true));
            case "self-test" -> {
                if (rest.length != 0) {
                    usageAndExit("self-test takes no arguments.");
                }
                status = SelfTest.run();
            }
            default -> {
                usageAndExit("Unknown command '" + command + "'.");
                return;
            }
        }
        System.exit(status);
    }

    // ------------------------------------------------------------------
    // Command line handling
    // ------------------------------------------------------------------

    /** Parsed command-line options; see the class comment for their meaning. */
    private static final class Options {
        Path jar;
        Path out;
        Path repo = Paths.get("").toAbsolutePath();
        Path work;
        List<String> langs;
        int jobs = 1;
        long timeoutSeconds = 900;
        /** "constant" (-XX:hashCode=2) or "default"; see the class comment. */
        String identityHash = "constant";
        final List<Path> positional = new ArrayList<>();

        static Options parse(String[] args, boolean forCompare) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--jar" -> o.jar = Paths.get(value(args, ++i, a)).toAbsolutePath().normalize();
                    case "--out" -> o.out = Paths.get(value(args, ++i, a)).toAbsolutePath().normalize();
                    case "--repo" -> o.repo = Paths.get(value(args, ++i, a)).toAbsolutePath().normalize();
                    case "--work" -> o.work = Paths.get(value(args, ++i, a)).toAbsolutePath().normalize();
                    case "--langs" -> o.langs = parseLangs(value(args, ++i, a));
                    case "--jobs" -> o.jobs = parsePositiveInt(value(args, ++i, a), a);
                    case "--timeout-seconds" -> o.timeoutSeconds = parsePositiveInt(value(args, ++i, a), a);
                    case "--identity-hash" -> {
                        o.identityHash = value(args, ++i, a);
                        if (!o.identityHash.equals("constant") && !o.identityHash.equals("default")) {
                            usageAndExit("--identity-hash must be 'constant' or 'default', not '" + o.identityHash + "'.");
                        }
                    }
                    default -> {
                        if (a.startsWith("--")) {
                            usageAndExit("Unknown option '" + a + "'.");
                        }
                        o.positional.add(Paths.get(a).toAbsolutePath().normalize());
                    }
                }
            }
            if (forCompare) {
                if (o.positional.size() != 2) {
                    usageAndExit("compare needs exactly two snapshot directories, got " + o.positional.size() + ".");
                }
                if (o.langs == null) {
                    // By default compare the languages that a Rust-target change must not affect.
                    o.langs = List.of("java", "python", "csharp");
                }
            } else {
                if (o.jar == null || o.out == null) {
                    usageAndExit("snapshot needs --jar and --out.");
                }
                if (!Files.isRegularFile(o.jar)) {
                    usageAndExit("The jar " + o.jar + " does not exist.");
                }
                if (!o.positional.isEmpty()) {
                    usageAndExit("snapshot takes no positional arguments, got " + o.positional + ".");
                }
                if (o.langs == null) {
                    o.langs = ALL_LANGS;
                }
                if (o.work == null) {
                    o.work = o.repo.resolve("build").resolve("generated-output-work");
                }
                if (o.out.startsWith(o.repo.resolve("examples")) || o.out.startsWith(o.repo.resolve("src"))) {
                    usageAndExit("--out must not be inside the grammar directories of the repository.");
                }
            }
            return o;
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) {
                usageAndExit("Option " + option + " needs a value.");
            }
            return args[i];
        }

        private static List<String> parseLangs(String spec) {
            List<String> langs = Arrays.stream(spec.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            for (String lang : langs) {
                if (!ALL_LANGS.contains(lang)) {
                    usageAndExit("Unknown language '" + lang + "'. Known languages: " + ALL_LANGS + ".");
                }
            }
            if (langs.isEmpty()) {
                usageAndExit("--langs must name at least one language.");
            }
            return langs;
        }

        private static int parsePositiveInt(String s, String option) {
            try {
                int n = Integer.parseInt(s);
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the error below
            }
            usageAndExit("Option " + option + " needs a positive integer, got '" + s + "'.");
            return -1; // not reached
        }
    }

    private static void usageAndExit(String problem) {
        System.err.println("GeneratedOutputSnapshot: " + problem);
        System.err.println("""
                Usage:
                  java GeneratedOutputSnapshot.java snapshot --jar congocc.jar --out DIR
                       [--repo REPO_ROOT] [--langs java,python,csharp,rust] [--work DIR]
                       [--jobs N] [--timeout-seconds N] [--identity-hash constant|default]
                  java GeneratedOutputSnapshot.java compare BASELINE_DIR CANDIDATE_DIR
                       [--langs java,python,csharp]
                  java GeneratedOutputSnapshot.java self-test""");
        System.exit(2);
    }

    // ------------------------------------------------------------------
    // snapshot
    // ------------------------------------------------------------------

    /** One unit of work: generate one grammar for one target language. */
    private record Task(String grammar, String lang) {
    }

    private static int snapshot(Options o) throws Exception {
        List<String> grammars = discoverGrammars(o.repo);
        if (grammars.isEmpty()) {
            System.err.println("No grammars found under " + GRAMMAR_ROOTS + " in " + o.repo + ".");
            return 2;
        }
        // Both directories are deleted and recreated.  Refuse to do that to a
        // directory that does not look like something this tool created, so a
        // mistyped --out or --work cannot wipe unrelated files.
        if (!isDisposable(o.out, "MANIFEST.tsv") || !isDisposable(o.work, "mirror-")) {
            return 2;
        }
        deleteRecursively(o.out);
        Files.createDirectories(o.out);
        deleteRecursively(o.work);

        ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<>();
        for (String g : grammars) {
            for (String lang : o.langs) {
                queue.add(new Task(g, lang));
            }
        }
        int total = queue.size();
        System.out.printf("Generating %d grammars x %d languages = %d runs with %s%n",
                grammars.size(), o.langs.size(), total, o.jar);

        // Every worker owns a private mirror, so that grammars which write to a
        // fixed location (BASE_SRC_DIR) cannot interfere with each other.
        // Dedicated thread pools (rather than the common ForkJoinPool) guarantee
        // that the threads draining generator output can never be starved by
        // the worker threads waiting for those generators.
        ExecutorService workerPool = Executors.newFixedThreadPool(o.jobs);
        ExecutorService readerPool = Executors.newCachedThreadPool();
        try {
            List<CompletableFuture<Void>> workers = new ArrayList<>();
            for (int slot = 0; slot < o.jobs; slot++) {
                Path mirror = o.work.resolve("mirror-" + slot);
                createMirror(o.repo, grammars, mirror);
                workers.add(CompletableFuture.runAsync(() -> {
                    Task t;
                    while ((t = queue.poll()) != null) {
                        runTask(o, mirror, t, readerPool);
                    }
                }, workerPool));
            }
            CompletableFuture.allOf(workers.toArray(new CompletableFuture<?>[0])).join();
        } finally {
            workerPool.shutdownNow();
            readerPool.shutdownNow();
        }
        writeManifest(o, grammars);
        deleteRecursively(o.work);
        System.out.println("Snapshot written to " + o.out);
        return 0;
    }

    /**
     * A directory may be deleted if it does not exist, is empty, or contains an
     * entry whose name starts with {@code marker} (a previous snapshot's
     * MANIFEST.tsv, or a previous run's mirror directories).
     */
    private static boolean isDisposable(Path dir, String marker) throws IOException {
        if (!Files.exists(dir)) {
            return true;
        }
        if (!Files.isDirectory(dir)) {
            System.err.println(dir + " exists and is not a directory; refusing to replace it.");
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> entries = s.toList();
            if (entries.isEmpty() || entries.stream().anyMatch(e -> e.getFileName().toString().startsWith(marker))) {
                return true;
            }
        }
        System.err.println(dir + " is not empty and was not created by this tool (no '" + marker
                + "' entry); refusing to delete it. Choose another directory or remove it yourself.");
        return false;
    }

    /** Returns the repository-relative paths (with '/' separators) of all grammar files, sorted. */
    private static List<String> discoverGrammars(Path repo) throws IOException {
        TreeSet<String> result = new TreeSet<>();
        for (String root : GRAMMAR_ROOTS) {
            Path dir = repo.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return SKIP_DIRS.contains(d.getFileName().toString()) && !d.equals(dir)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (f.getFileName().toString().endsWith(".ccc")) {
                        result.add(slashes(repo.relativize(f)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new ArrayList<>(result);
    }

    /** Copies every grammar file into {@code mirror}, preserving repository-relative paths. */
    private static void createMirror(Path repo, List<String> grammars, Path mirror) throws IOException {
        for (String g : grammars) {
            Path target = mirror.resolve(g);
            Files.createDirectories(target.getParent());
            Files.copy(repo.resolve(g), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Runs the generator for one grammar and language inside {@code mirror},
     * then moves everything the generator wrote into the snapshot.
     */
    private static void runTask(Options o, Path mirror, Task t, ExecutorService readerPool) {
        Path dest = o.out.resolve(t.grammar()).resolve(t.lang());
        Path outDir = mirror.resolve("out");
        try {
            Files.createDirectories(dest);
            String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            List<String> cmd = new ArrayList<>(List.of(javaExe, "-ea"));
            if (o.identityHash.equals("constant")) {
                cmd.addAll(CONSTANT_IDENTITY_HASH);
            }
            cmd.addAll(List.of("-jar", o.jar.toString(), "-n", "-q", "-lang", t.lang(), "-d", outDir.toString(),
                    t.grammar()));
            Process p = new ProcessBuilder(cmd).directory(mirror.toFile()).redirectErrorStream(true).start();
            // Drain the output on another thread so that a chatty generator cannot
            // block on a full pipe while we wait for it with a timeout.
            CompletableFuture<byte[]> output =
                    CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()), readerPool);
            boolean finished = p.waitFor(o.timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly().waitFor();
            }
            String log = new String(output.join(), StandardCharsets.UTF_8)
                    .replace(mirror.toString(), "<MIRROR>")
                    .replace(o.jar.toString(), "<JAR>");
            String status = finished ? "exit=" + p.exitValue() : "TIMEOUT after " + o.timeoutSeconds + "s";
            Files.writeString(dest.resolve(LOG_FILE), status + "\n" + log, StandardCharsets.UTF_8);
            collectGeneratedFiles(mirror, dest.resolve("files"));
            System.out.printf("  %-60s %-7s %s%n", t.grammar(), t.lang(), status);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate " + t + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating " + t, e);
        }
    }

    /**
     * Moves every file in the mirror that is not one of the original grammar
     * files (that is, everything the generator wrote, wherever it wrote it)
     * into {@code dest}, preserving mirror-relative paths, and removes the
     * now-empty directories so the mirror is clean for the next run.
     */
    private static void collectGeneratedFiles(Path mirror, Path dest) throws IOException {
        List<Path> written;
        try (Stream<Path> s = Files.walk(mirror)) {
            written = s.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith(".ccc") || f.startsWith(mirror.resolve("out")))
                    .toList();
        }
        for (Path f : written) {
            Path target = dest.resolve(mirror.relativize(f).toString());
            Files.createDirectories(target.getParent());
            Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
        }
        removeEmptyDirectories(mirror);
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (!d.equals(root)) {
                    try (Stream<Path> s = Files.list(d)) {
                        if (s.findAny().isEmpty()) {
                            Files.delete(d);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Writes MANIFEST.tsv: one line per grammar and language with the exit status and a content hash. */
    private static void writeManifest(Options o, List<String> grammars) throws IOException {
        StringBuilder sb = new StringBuilder("grammar\tlang\tstatus\tfiles\tsha256\n");
        for (String g : grammars) {
            for (String lang : o.langs) {
                Path dir = o.out.resolve(g).resolve(lang);
                String status = Files.readAllLines(dir.resolve(LOG_FILE), StandardCharsets.UTF_8).get(0);
                Map<String, byte[]> files = readTree(dir);
                sb.append(g).append('\t').append(lang).append('\t').append(status).append('\t')
                        .append(files.size()).append('\t').append(hashTree(files)).append('\n');
            }
        }
        Files.writeString(o.out.resolve("MANIFEST.tsv"), sb.toString(), StandardCharsets.UTF_8);
        Files.writeString(o.out.resolve("SNAPSHOT-INFO.txt"),
                "jar\t" + o.jar + "\njar-sha256\t" + sha256(Files.readAllBytes(o.jar)) + "\nlangs\t" + o.langs
                        + "\njava\t" + System.getProperty("java.version") + "\n" + IDENTITY_HASH_KEY + "\t"
                        + o.identityHash + "\n",
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // compare
    // ------------------------------------------------------------------

    private static int compare(Options o) throws IOException {
        Path baseline = o.positional.get(0);
        Path candidate = o.positional.get(1);
        for (Path p : o.positional) {
            if (!Files.isRegularFile(p.resolve("MANIFEST.tsv"))) {
                System.err.println(p + " is not a snapshot directory (no MANIFEST.tsv).");
                return 2;
            }
        }
        String baselineHash = identityHashMode(baseline);
        String candidateHash = identityHashMode(candidate);
        if (!baselineHash.equals(candidateHash)) {
            System.err.println("The snapshots were taken with different identity hash modes (" + baseline + ": "
                    + baselineHash + ", " + candidate + ": " + candidateHash + "), so the numbering of NFA states in "
                    + "the generated lexers may differ for no reason; take both with the same --identity-hash.");
            return 2;
        }
        TreeSet<String> baseGrammars = grammarsInSnapshot(baseline);
        TreeSet<String> candGrammars = grammarsInSnapshot(candidate);
        TreeSet<String> common = new TreeSet<>(baseGrammars);
        common.retainAll(candGrammars);

        int differences = 0;
        int identical = 0;
        int identicalExceptFrames = 0;
        for (String g : common) {
            for (String lang : o.langs) {
                Path a = baseline.resolve(g).resolve(lang);
                Path b = candidate.resolve(g).resolve(lang);
                if (!Files.isDirectory(a) && !Files.isDirectory(b)) {
                    continue;
                }
                List<String> frameOnly = new ArrayList<>();
                List<String> problems = compareTrees(a, b, frameOnly);
                if (problems.isEmpty()) {
                    identical++;
                    if (!frameOnly.isEmpty()) {
                        identicalExceptFrames++;
                        frameOnly.forEach(f -> System.out.println("NOTE  " + g + " [" + lang + "]: " + f
                                + " differs only in the line numbers of the stack trace frames of CongoCC's classes"));
                    }
                } else {
                    differences++;
                    System.out.println("DIFFERENT  " + g + " [" + lang + "]");
                    problems.forEach(p -> System.out.println("    " + p));
                }
            }
        }
        for (String g : baseGrammars) {
            if (!candGrammars.contains(g)) {
                System.out.println("NOTE  grammar only in baseline (removed?): " + g);
            }
        }
        for (String g : candGrammars) {
            if (!baseGrammars.contains(g)) {
                System.out.println("NOTE  grammar only in candidate (new, not compared): " + g);
            }
        }
        System.out.printf("%d grammar/language outputs identical (%d of them except for the line numbers of CongoCC's"
                + " stack trace frames in %s), %d different (languages compared: %s)%n",
                identical, identicalExceptFrames, LOG_FILE, differences, o.langs);
        return differences == 0 ? 0 : 1;
    }

    /** The JVM options that make identity hash codes constant (see the class comment). */
    private static final List<String> CONSTANT_IDENTITY_HASH =
            List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2");

    private static final String IDENTITY_HASH_KEY = "identity-hash";

    /**
     * @return the identity hash mode a snapshot was taken with; "default" for
     *         snapshots written before the mode was recorded (they were all
     *         taken with the JVM's default identity hash codes)
     */
    private static String identityHashMode(Path snapshot) throws IOException {
        Path info = snapshot.resolve("SNAPSHOT-INFO.txt");
        if (Files.isRegularFile(info)) {
            for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                if (line.startsWith(IDENTITY_HASH_KEY + "\t")) {
                    return line.substring(IDENTITY_HASH_KEY.length() + 1);
                }
            }
        }
        return "default";
    }

    /** A snapshot stores each grammar's output in a directory named after the grammar file (ending in .ccc). */
    private static TreeSet<String> grammarsInSnapshot(Path snapshot) throws IOException {
        try (Stream<Path> s = Files.walk(snapshot)) {
            return s.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().endsWith(".ccc"))
                    .map(d -> slashes(snapshot.relativize(d)))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * A stack trace frame of one of CongoCC's own classes, such as
     * "\tat org.congocc.app.Main.mainProgram(Main.java:336)", with the line
     * number as group 2.  Only frames of the classes in org.congocc, which are
     * the generator's own code, are matched: the frames of the JDK depend only
     * on the JDK, and the generator's messages about the grammar (for example
     * "at Parser.cs:1611:13 in Block(...)") have another form.
     */
    private static final Pattern GENERATOR_FRAME =
            Pattern.compile("(?m)^(\\s*at org\\.congocc\\.[\\w.$]+\\([\\w$]+\\.java:)(\\d+)(\\))$");

    /** The generator's console output with the line numbers of its own stack trace frames removed. */
    static String withoutGeneratorFrameLines(String log) {
        return GENERATOR_FRAME.matcher(log).replaceAll("$1<line>$3");
    }

    /**
     * Compares two directory trees byte-for-byte and describes every
     * difference.  The generator logs are the exception: two logs that differ
     * only in the line numbers of CongoCC's stack trace frames are not a
     * difference; their names are added to {@code frameOnly} instead.
     */
    private static List<String> compareTrees(Path a, Path b, List<String> frameOnly) throws IOException {
        Map<String, byte[]> left = Files.isDirectory(a) ? readTree(a) : Map.of();
        Map<String, byte[]> right = Files.isDirectory(b) ? readTree(b) : Map.of();
        List<String> problems = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(left.keySet());
        names.addAll(right.keySet());
        for (String name : names) {
            byte[] l = left.get(name);
            byte[] r = right.get(name);
            if (l == null) {
                problems.add("only in candidate: " + name);
            } else if (r == null) {
                problems.add("only in baseline:  " + name);
            } else if (!Arrays.equals(l, r)) {
                if (name.equals(LOG_FILE)
                        && withoutGeneratorFrameLines(new String(l, StandardCharsets.UTF_8))
                                .equals(withoutGeneratorFrameLines(new String(r, StandardCharsets.UTF_8)))) {
                    frameOnly.add(name);
                } else {
                    problems.add("content differs:   " + name + "  " + firstDifference(l, r));
                }
            }
        }
        return problems;
    }

    /** Describes the first differing line of two text files, to make a report readable without a diff tool. */
    private static String firstDifference(byte[] l, byte[] r) {
        String[] ll = new String(l, StandardCharsets.UTF_8).split("\n", -1);
        String[] rl = new String(r, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < Math.max(ll.length, rl.length); i++) {
            String x = i < ll.length ? ll[i] : "<end of file>";
            String y = i < rl.length ? rl[i] : "<end of file>";
            if (!x.equals(y)) {
                return "(line " + (i + 1) + ": baseline=" + abbreviate(x) + " candidate=" + abbreviate(y) + ")";
            }
        }
        return "(line endings or trailing bytes differ)";
    }

    private static String abbreviate(String s) {
        String t = s.strip();
        return "\"" + (t.length() > 70 ? t.substring(0, 70) + "..." : t) + "\"";
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Map<String, byte[]> readTree(Path dir) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                files.put(slashes(dir.relativize(f)), Files.readAllBytes(f));
            }
        }
        return files;
    }

    private static String hashTree(Map<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue());
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted((m, n) -> n.getNameCount() - m.getNameCount()).toList()) {
                Files.delete(x);
            }
        }
    }

    // ------------------------------------------------------------------
    // self-test
    // ------------------------------------------------------------------

    /**
     * Checks of the comparison (the "self-test" command): the normalization of
     * the generator's stack trace frames, the comparison of two output trees,
     * and the exit statuses of compare.  The snapshots are synthetic, written
     * to a temporary directory that is removed afterwards, so the checks run
     * in a fraction of a second and need no congocc.jar.
     */
    private static final class SelfTest {
        private int failures;
        private int checks;

        static int run() throws IOException {
            SelfTest t = new SelfTest();
            t.frameNormalization();
            Path dir = Files.createTempDirectory("generated-output-self-test");
            try {
                t.treeComparison(dir.resolve("trees"));
                t.compareStatuses(dir.resolve("snapshots"));
            } finally {
                deleteRecursively(dir);
            }
            System.out.printf("self-test: %d checks, %d failed%n", t.checks, t.failures);
            return t.failures == 0 ? 0 : 1;
        }

        /** Records one check; a failure names the check and shows what was expected and found. */
        private void check(String what, Object expected, Object actual) {
            checks++;
            if (!expected.equals(actual)) {
                failures++;
                StackTraceElement caller = new Throwable().getStackTrace()[1];
                System.out.println("FAIL  " + what + " (GeneratedOutputSnapshot.java:" + caller.getLineNumber() + ")");
                System.out.println("      expected: " + expected);
                System.out.println("      found:    " + actual);
            }
        }

        private void frameNormalization() {
            check("a frame of a CongoCC class loses its line number",
                    "\tat org.congocc.app.Main.mainProgram(Main.java:<line>)",
                    withoutGeneratorFrameLines("\tat org.congocc.app.Main.mainProgram(Main.java:336)"));
            check("a frame of a nested class or a lambda loses its line number",
                    "\tat org.congocc.core.Grammar$1.lambda$run$0(Grammar.java:<line>)",
                    withoutGeneratorFrameLines("\tat org.congocc.core.Grammar$1.lambda$run$0(Grammar.java:12)"));
            check("frames are found on every line, and other lines are kept",
                    "Exception in thread \"main\"\n\tat org.congocc.A.f(A.java:<line>)\nCaused by: x\n"
                            + "\tat org.congocc.B.g(B.java:<line>)\n",
                    withoutGeneratorFrameLines("Exception in thread \"main\"\n\tat org.congocc.A.f(A.java:1)\n"
                            + "Caused by: x\n\tat org.congocc.B.g(B.java:22)\n"));
            String[] kept = {
                // A JDK frame: its line numbers depend only on the JDK.
                "\tat java.base/java.lang.reflect.Method.invoke(Method.java:565)",
                // A frame of another package, such as a parser generated by CongoCC.
                "\tat org.parsers.java.JavaParser.parse(JavaParser.java:5)",
                // A location in a grammar or in generated code, reported by the generator.
                "        at Parser.cs:1611:13 in Block(examples/csharp/CSharp.ccc:1032:21,CSharpParser.java:8466)",
                // A CongoCC frame that is not alone on its line.
                "see \tat org.congocc.app.Main.main(Main.java:275)",
                "\tat org.congocc.app.Main.main(Main.java:275) and more",
                // A frame without a line number.
                "\tat org.congocc.app.Main.main(Native Method)",
            };
            for (String line : kept) {
                check("a line that is not a frame of a CongoCC class is kept: " + line, line,
                        withoutGeneratorFrameLines(line));
            }
        }

        private void treeComparison(Path dir) throws IOException {
            Path a = dir.resolve("a");
            Path b = dir.resolve("b");
            write(a.resolve(LOG_FILE), "exit=1\nfailed\n\tat org.congocc.app.Main.main(Main.java:266)\n");
            write(b.resolve(LOG_FILE), "exit=1\nfailed\n\tat org.congocc.app.Main.main(Main.java:275)\n");
            write(a.resolve("files/out/P.java"), "class P {}\n");
            write(b.resolve("files/out/P.java"), "class P {}\n");
            List<String> frameOnly = new ArrayList<>();
            check("trees that differ only in a log's frame line numbers are identical", List.of(),
                    compareTrees(a, b, frameOnly));
            check("the log that differs only in frame line numbers is reported", List.of(LOG_FILE), frameOnly);

            write(b.resolve(LOG_FILE), "exit=1\nfailed differently\n\tat org.congocc.app.Main.main(Main.java:275)\n");
            frameOnly.clear();
            check("a log that differs in a message is a difference",
                    List.of("content differs:   " + LOG_FILE + "  (line 2: baseline=\"failed\" candidate=\"failed differently\")"),
                    compareTrees(a, b, frameOnly));
            check("a log with other differences is not reported as differing only in frames", List.of(), frameOnly);

            write(b.resolve(LOG_FILE), "exit=1\nfailed\n\tat org.congocc.app.Main.main(Main.java:266)\n");
            write(a.resolve("files/out/T.java"), "// at org.congocc.A.f(A.java:1)\n");
            write(b.resolve("files/out/T.java"), "// at org.congocc.A.f(A.java:2)\n");
            frameOnly.clear();
            check("a generated file is compared byte for byte, even where it looks like a frame",
                    List.of("content differs:   files/out/T.java  (line 1: baseline=\"// at org.congocc.A.f(A.java:1)\""
                            + " candidate=\"// at org.congocc.A.f(A.java:2)\")"),
                    compareTrees(a, b, frameOnly));

            Files.delete(b.resolve("files/out/T.java"));
            write(b.resolve("files/out/New.java"), "class New {}\n");
            check("files that exist in one tree only are differences",
                    List.of("only in candidate: files/out/New.java", "only in baseline:  files/out/T.java"),
                    compareTrees(a, b, frameOnly));
        }

        private void compareStatuses(Path dir) throws IOException {
            Path base = snapshotWith(dir.resolve("base"), "constant", "class P {}\n");
            Path same = snapshotWith(dir.resolve("same"), "constant", "class P {}\n");
            Path changed = snapshotWith(dir.resolve("changed"), "constant", "class P { int x; }\n");
            Path otherMode = snapshotWith(dir.resolve("other-mode"), "default", "class P {}\n");
            Path unrecorded = snapshotWith(dir.resolve("unrecorded"), null, "class P {}\n");
            Path defaultMode = snapshotWith(dir.resolve("default-mode"), "default", "class P {}\n");
            check("compare of identical snapshots exits with 0", 0, compareQuietly(base, same));
            check("compare of different snapshots exits with 1", 1, compareQuietly(base, changed));
            check("compare of snapshots taken in different identity hash modes exits with 2", 2,
                    compareQuietly(base, otherMode));
            check("a snapshot that does not record its identity hash mode was taken in the default mode", 0,
                    compareQuietly(unrecorded, defaultMode));
            check("a directory without MANIFEST.tsv is refused with 2", 2, compareQuietly(base, dir.resolve("none")));
        }

        /** A snapshot of one grammar (g.ccc) for Java, whose single generated file has the given content. */
        private static Path snapshotWith(Path dir, String identityHash, String content) throws IOException {
            write(dir.resolve("MANIFEST.tsv"), "grammar\tlang\tstatus\tfiles\tsha256\n");
            if (identityHash != null) {
                write(dir.resolve("SNAPSHOT-INFO.txt"), IDENTITY_HASH_KEY + "\t" + identityHash + "\n");
            }
            write(dir.resolve("g.ccc/java/" + LOG_FILE), "exit=0\n");
            write(dir.resolve("g.ccc/java/files/out/P.java"), content);
            return dir;
        }

        /** Runs compare with its report sent to a buffer, so that the self-test output stays readable. */
        private static int compareQuietly(Path baseline, Path candidate) throws IOException {
            java.io.PrintStream out = System.out;
            java.io.PrintStream err = System.err;
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.io.PrintStream quiet = new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8)) {
                System.setOut(quiet);
                System.setErr(quiet);
                Options o = new Options();
                o.positional.add(baseline);
                o.positional.add(candidate);
                o.langs = List.of("java");
                return compare(o);
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
        }

        private static void write(Path file, String content) throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }
}
