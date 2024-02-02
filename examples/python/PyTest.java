import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.parsers.python.Node;
import org.parsers.python.PythonParser;

/**
 * A test harness for parsing Python files from the command line.
 */
public class PyTest {
    static List<Node> roots = new ArrayList<>();
    static private List<Path> paths = new ArrayList<Path>(),
                              failures = new ArrayList<Path>(),
                              successes = new ArrayList<Path>();
    static private boolean parallelParsing, retainInMemory;
    static private FileSystem fileSystem = FileSystems.getDefault();

    static public void main(String args[]) throws IOException {
        for (String arg : args) {
            if (arg.equals("-p")) {
                parallelParsing = true;
                roots = Collections.synchronizedList(roots);
                failures = Collections.synchronizedList(failures);
                successes = Collections.synchronizedList(successes);
                continue;
            }
            if (arg.equals("-r")) {
                retainInMemory = true;
                continue;
            }
            Path path = fileSystem.getPath(arg);
            if (!Files.exists(path)) {
                System.err.println("File " + path + " does not exist.");
                continue;
            }
            Files.walk(path).forEach(p->{
                if (p.toString().endsWith(".py")) {
                    paths.add(p);
                }
            });
        }
        if (paths.isEmpty()) usage();
        long startTime = System.currentTimeMillis();
        Stream<Path> stream = parallelParsing
                               ? paths.parallelStream()
                               :  paths.stream();
        stream.forEach(path -> parseFile(path));
        for (Path path : failures) {
            System.out.println("Parse failed on: " + path);
        }
        System.out.println("\nParsed " + successes.size() + " files successfully");
        System.out.println("Failed on " + failures.size() + " files.");
        System.out.println("\nDuration: " + (System.currentTimeMillis() - startTime) + " milliseconds");
    }

    static public void parseFile(Path path) {
        try {
            Node root = new PythonParser(path).Module();
            if (retainInMemory) roots.add(root);
            if (paths.size()==1) {
                root.dump("");
            }
            System.out.println(path.getFileName().toString() + " parsed successfully.");
            successes.add(path);
            if (successes.size() % 1000 == 0) {
                System.out.println("-----------------------------------------------");
                System.out.println("Successfully parsed " + successes.size() + " files.");
                System.out.println("-----------------------------------------------");
            }
            //
            // Now parse using the internal Python compiler. We assume that no exception
            // means a successful parse.
            //
            org.congocc.parser.Node internalRoot = new org.congocc.parser.python.PythonParser(path).Module();
            System.out.println(path.getFileName().toString() + " parsed successfully (internal compiler).");
        }
        catch (Exception e) {
          System.err.println("Error processing file: " + path);
          failures.add(path);
          e.printStackTrace();
        }
    }

    static public void usage() {
        System.out.println("Usage: java PyTest <sourcefiles or directories>");
        System.out.println("If you just pass it one java source file, it dumps the AST");
        System.out.println("Use the -p flag to set whether to parse in multiple threads");
        System.out.println("Use the -r flag to retain all the parsed AST's in memory");
        System.exit(-1);
    }
}
