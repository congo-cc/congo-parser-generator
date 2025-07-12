import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.Path;
import org.parsers.java.JavaParser;
import org.parsers.java.Node;

/**
 * A test harness for parsing Java files from the command line.
 */
public class JParse {
    static List<Node> roots = new ArrayList<>();
    static private List<Path> paths = new ArrayList<Path>(),
                              failures = new ArrayList<Path>(),
                              successes = new ArrayList<Path>();
    static private boolean tolerantParsing, parallelParsing, retainInMemory, quiet;
    static private FileSystem fileSystem = FileSystems.getDefault();

    static public void main(String args[]) throws IOException {
        for (String arg : args) {
            Path path = null;
            if (arg.equals("-t")) {
                tolerantParsing = true;
                continue;
            }
            if (arg.equals("-p")) {
                System.out.println("Will parse in multiple threads.");
                parallelParsing = true;
                roots = Collections.synchronizedList(roots);
                failures = Collections.synchronizedList(failures);
                successes = Collections.synchronizedList(successes);
                continue;
            }
            if (arg.equals("-q")) {
                quiet = true;
                continue;
            }
            if (arg.equals("-r")) {
                retainInMemory = true;
                continue;
            }
            if (arg.equals("-s")) {
                String classLibLocation = System.getProperty("java.home") + "/lib/src.zip";
                if (System.getProperty("java.version").startsWith("1.8")) {
                    classLibLocation = System.getProperty("java.home") + "/../src.zip";
                }
                path = fileSystem.getPath(classLibLocation);
                if (Files.exists(path)) {
                    System.out.println("Parsing source in " + classLibLocation);
                    addPaths(path, paths);
                } else {
                    System.err.println("Could not find src.zip at: " + classLibLocation);
                }
                continue;
            }
            path = fileSystem.getPath(arg);
            if (!Files.exists(path)) {
                System.err.println("File " + path + " does not exist.");
                continue;
            }
            addPaths(path, paths);
        }
        if (paths.isEmpty()) {
            usage();
            return;
        }
        long startTime = System.currentTimeMillis();
        Stream<Path> stream = parallelParsing
                               ? paths.parallelStream()
                               :  paths.stream();
        stream.forEach(path -> parseFile(path));
        for (Path path : failures) {
            System.out.println("Parse failed on: " + path);
        }
        System.out.println("\nParsed " + successes.size() + " files successfully");
        System.out.println("Failed on " + failures.size() + " files");
        System.out.println("\nDuration: " + (System.currentTimeMillis() - startTime) + " milliseconds");
        if (!failures.isEmpty()) System.exit(-1);
    }

    static void addPaths(Path path, List<Path> paths) throws IOException {
        Files.walk(path).forEach(p->{
            if (!Files.isDirectory(p)) {
                if (p.toString().endsWith(".java")) {
                    paths.add(p);
                }
                else if (p.toString().endsWith(".jar") || p.toString().endsWith(".zip")) {
                    try {
                        FileSystem zfs = FileSystems.newFileSystem(p, (ClassLoader) null);
                        p = zfs.getRootDirectories().iterator().next();
                        addPaths(p, paths);
                    }
                    catch (IOException ioe) {
                        ioe.printStackTrace();
                        System.exit(1);
                    }
                }
            }
        });
    }



    static public void parseFile(Path path) {
        try {
            JavaParser parser = new JavaParser(path);
            parser.setParserTolerant(tolerantParsing);
            //Node root = parser.CompilationUnit();
            Node root = parser.Root();
            if (retainInMemory) roots.add(root);
            if (paths.size()==1) {
                root.dump("");
            }
            if (!quiet) {
                System.out.println(path.getFileName().toString() + " parsed successfully.");
            }
            successes.add(path);
            if (successes.size() % 1000 == 0) {
                System.out.println("Successfully parsed " + successes.size() + " files...");
            }
        }
        catch (Exception e) {
          failures.add(path);
          e.printStackTrace();
        }
    }

    static public void usage() {
        System.out.println("Usage: java JParse <sourcefiles or directories>");
        System.out.println("If you just pass it one java source file, it dumps the AST");
        System.out.println("Use the -p flag to set whether to parse in multiple threads");
        System.out.println("Use the -q flag for quieter output");
        System.out.println("Use the -r flag to retain all the parsed AST's in memory");
        System.out.println("Use the -t flag to set whether to parse in tolerant mode");
        System.out.println("Use the -s flag to parse the files in $JAVA_HOME/lib/src.zip");
        System.exit(-1);
    }
}
