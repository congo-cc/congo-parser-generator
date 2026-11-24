import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parsers.rust.Node;
import org.parsers.rust.ParseException;
import org.parsers.rust.RustLexer;
import org.parsers.rust.RustParser;
import org.parsers.rust.Token;
import org.parsers.rust.TokenSource;

/**
 * Writes the syntax trees built by the Java parser generated from Rust.ccc in
 * a canonical text format, so that they can be compared with the trees built
 * by the Rust parser generated from the same grammar (see the rust-harness
 * crate, which writes the same format and does the comparison), and measures
 * the speed of the Java parser.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java TreeDump -o &lt;output directory&gt; &lt;file or directory&gt;...
 *   java TreeDump -bench &lt;rounds&gt; &lt;file or directory&gt;...
 * </pre>
 * <p>The first form writes {@code <output directory>/<name>.tree} for every
 * {@code .rs} file, where {@code <name>} is the path of the file relative to
 * the directory given on the command line (or its file name, for a file given
 * on the command line).  It exits with status 1 if a file cannot be read or
 * written; a file that does not parse is not an error, its dump records the
 * error.</p>
 * <p>The second form reads all the files into memory, parses them all
 * {@code <rounds>} times to warm up the JVM, then {@code <rounds>} more times,
 * and prints the median and the fastest time of a round.</p>
 *
 * <h2>The tree format</h2>
 * <pre>
 *   congocc-tree 1
 *   content &lt;number of code points&gt; &lt;FNV-1a 64-bit hash of the UTF-8 bytes&gt;
 *   &lt;depth&gt; &lt;node class&gt; &lt;begin&gt; &lt;end&gt;
 *   &lt;depth&gt; Token &lt;token type&gt; &lt;begin&gt; &lt;end&gt;
 *   ...
 * </pre>
 * <p>The content line describes the text that the parser saw (the file after
 * the lexer's rewriting: tab expansion, line ending normalization...).  Then
 * the nodes follow in depth-first order, the root at depth 0.  Offsets count
 * code points from the start of that text, the end offset being exclusive;
 * code points rather than UTF-16 units (Java) or bytes (Rust) make the
 * offsets of the two parsers comparable.  When the parse fails, the node
 * lines are replaced by {@code error <line>:<column>}, the location of the
 * token where the error was detected, or by {@code crash <exception>} when
 * the parser fails with an exception other than a ParseException.</p>
 */
public class TreeDump {

    /** The first line of every dump; bump the version when the format changes. */
    static final String FORMAT_HEADER = "congocc-tree 1";

    private static final String USAGE =
        "Usage: java TreeDump -o <output directory> <file or directory>...\n"
      + "       java TreeDump -bench <rounds> <file or directory>...";

    /** A file to parse and the name of its dump. */
    record Input(Path path, String name) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || !(args[0].equals("-o") || args[0].equals("-bench"))) {
            System.err.println(USAGE);
            System.exit(2);
        }
        List<Input> inputs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            collectInputs(Paths.get(args[i]), inputs);
        }
        if (inputs.isEmpty()) {
            System.err.println("TreeDump: no .rs files found in " + String.join(" ", Arrays.asList(args).subList(2, args.length))
                + "; give the files or directories to parse.");
            System.exit(2);
        }
        if (args[0].equals("-o")) {
            System.exit(writeDumps(Paths.get(args[1]), inputs));
        }
        int rounds;
        try {
            rounds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            rounds = -1;
        }
        if (rounds < 1) {
            System.err.println("TreeDump: the number of rounds must be a positive integer, not '" + args[1] + "'.\n" + USAGE);
            System.exit(2);
        }
        benchmark(inputs, rounds);
    }

    /** Adds the .rs files of {@code path} (a file or a directory, recursively) in a stable order. */
    static void collectInputs(Path path, List<Input> inputs) throws IOException {
        if (!Files.exists(path)) {
            System.err.println("TreeDump: " + path + " does not exist; check the command line.");
            System.exit(2);
        }
        if (!Files.isDirectory(path)) {
            inputs.add(new Input(path, path.getFileName().toString()));
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".rs")).sorted().collect(Collectors.toList())) {
                inputs.add(new Input(p, path.relativize(p).toString().replace('\\', '/')));
            }
        }
    }

    static int writeDumps(Path outputDirectory, List<Input> inputs) {
        int failures = 0;
        for (Input input : inputs) {
            Path target = outputDirectory.resolve(input.name() + ".tree");
            try {
                String text = TokenSource.stringFromBytes(Files.readAllBytes(input.path()));
                Files.createDirectories(target.getParent());
                Files.writeString(target, dump(input.name(), text), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("TreeDump: cannot dump " + input.path() + " to " + target + ": " + e
                    + "; check that the file is readable and the output directory is writable.");
                failures++;
            }
        }
        System.out.println("Wrote " + (inputs.size() - failures) + " tree dumps to " + outputDirectory);
        return failures == 0 ? 0 : 1;
    }

    /** Parses {@code text} and returns its dump in the format described above. */
    static String dump(String name, String text) {
        RustLexer lexer = new RustLexer(name, text);
        RustParser parser = new RustParser(lexer);
        // TokenSource.toString() returns the text after the lexer's rewriting,
        // which is what all the offsets refer to.
        String content = lexer.toString();
        int[] codePointIndex = codePointIndex(content);
        StringBuilder out = new StringBuilder(content.length() * 2);
        out.append(FORMAT_HEADER).append('\n');
        out.append("content ").append(codePointIndex[content.length()]).append(' ')
           .append(String.format("%016x", fnv1a64(content.getBytes(StandardCharsets.UTF_8)))).append('\n');
        try {
            parser.Crate();
        } catch (ParseException e) {
            Node location = e.getToken();
            out.append("error ").append(location == null ? "?" : location.getBeginLine() + ":" + location.getBeginColumn()).append('\n');
            return out.toString();
        } catch (RuntimeException | StackOverflowError | AssertionError e) {
            out.append("crash ").append(e.getClass().getName()).append('\n');
            return out.toString();
        }
        // An explicit stack instead of recursion, so that deeply nested trees
        // cannot overflow the Java stack.
        Deque<Node> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        nodes.push(parser.rootNode());
        depths.push(0);
        while (!nodes.isEmpty()) {
            Node node = nodes.pop();
            int depth = depths.pop();
            out.append(depth).append(' ');
            if (node instanceof Token token) {
                out.append("Token ").append(token.getType());
            } else {
                out.append(node.getClass().getSimpleName());
            }
            out.append(' ').append(codePointIndex[node.getBeginOffset()])
               .append(' ').append(codePointIndex[node.getEndOffset()]).append('\n');
            for (int i = node.size() - 1; i >= 0; i--) {
                nodes.push(node.get(i));
                depths.push(depth + 1);
            }
        }
        return out.toString();
    }

    /**
     * @return a table giving, for each UTF-16 offset in {@code content} (and
     *         its length), the number of code points before it
     */
    static int[] codePointIndex(String content) {
        int[] index = new int[content.length() + 1];
        int codePoints = 0;
        for (int i = 0; i < content.length(); i++) {
            index[i] = codePoints;
            // The low half of a surrogate pair does not start a code point.
            if (!(Character.isLowSurrogate(content.charAt(i)) && i > 0 && Character.isHighSurrogate(content.charAt(i - 1)))) {
                codePoints++;
            }
        }
        index[content.length()] = codePoints;
        // The offset of the low half of a pair belongs to the pair's code point.
        for (int i = 1; i < content.length(); i++) {
            if (Character.isLowSurrogate(content.charAt(i)) && Character.isHighSurrogate(content.charAt(i - 1))) {
                index[i] = index[i - 1];
            }
        }
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

    static void benchmark(List<Input> inputs, int rounds) throws IOException {
        List<String> texts = new ArrayList<>();
        long bytes = 0;
        for (Input input : inputs) {
            byte[] data = Files.readAllBytes(input.path());
            bytes += data.length;
            texts.add(TokenSource.stringFromBytes(data));
        }
        long[] times = new long[rounds];
        int failures = 0;
        for (int round = -rounds; round < rounds; round++) {
            long start = System.nanoTime();
            for (String text : texts) {
                try {
                    new RustParser(new RustLexer("input", text)).Crate();
                } catch (ParseException e) {
                    failures++;
                }
            }
            long elapsed = System.nanoTime() - start;
            if (round >= 0) {
                times[round] = elapsed;
            }
        }
        Arrays.sort(times);
        double median = times[rounds / 2] / 1e6, fastest = times[0] / 1e6;
        System.out.printf("java: %d files, %d bytes, %d rounds after %d warm-up rounds: median %.2f ms, fastest %.2f ms per round (%.1f MB/s)%n",
            texts.size(), bytes, rounds, rounds, median, fastest, bytes / 1e6 / (median / 1e3));
        if (failures > 0) {
            System.out.println("java: " + failures / (2 * rounds) + " files failed to parse");
        }
    }
}
