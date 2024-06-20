import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import org.parsers.csharp.CSharpParser;
import org.parsers.csharp.Node;
//import org.congocc.parser.csharp.CSharpParser;
//import org.congocc.parser.Node;
//import org.congocc.parser.CongoCCParser;

/**
 * A test harness for parsing C# source code
 */
public class CSParse {

    static public ArrayList<Node> roots = new ArrayList<>();
    static List<Path> paths = new ArrayList<>();
    static List<Path> failures = new ArrayList<>(), successes = new ArrayList<>();
    static private boolean parallelParsing, retainInMemory, quiet;
    static long startTime, parseStart, parseTime;
    static private FileSystem fileSystem = FileSystems.getDefault();

    static public void main(String args[]) throws IOException {
        if (args.length == 0) {
            usage();
        }
        for (String arg : args) {
            if (arg.equals("-p")) {
                System.out.println("Will parse in multiple threads.");
                parallelParsing = true;
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
            Path path = fileSystem.getPath(arg);
            if (!Files.exists(path)) {
                System.err.println("File " + path + " does not exist.");
                continue;
            }
            addPaths(path, paths);
        }
        startTime = System.nanoTime();
        Stream<Path> stream = parallelParsing ? paths.parallelStream() : paths.stream();
        stream.forEach(path -> {
            try {
                // A bit screwball, we'll dump the tree if there is only one arg. :-)
                parseStart = System.nanoTime();
                parseFile(path, paths.size() == 1);
                successes.add(path);
            } catch (Exception e) {
                System.err.println("Error processing file: " + path);
                e.printStackTrace();
                failures.add(path);
                return;
            }
            parseTime = System.nanoTime() - parseStart;
            String parseTimeString = "" + parseTime / 1000000.0;
            parseTimeString = parseTimeString.substring(0, parseTimeString.indexOf('.') + 2);
            if (!quiet) {
                System.out.println("Parsed " + path + " in " + parseTimeString + " milliseconds.");
            }
            if (successes.size() % 1000 == 0) {
                System.out.println("Successfully parsed " + successes.size() + " files");
            }
        });
        System.out.println();
        for (Path path : failures) {
            System.out.println("Parse failed on: " + path);
        }
        if (paths.size() > 1) {
            System.out.println("\nParsed " + successes.size() + " files successfully");
            System.out.println("Failed on " + failures.size() + " files.");
        }
        String duration = "" + (System.nanoTime() - startTime) / 1E9;
        duration = duration.substring(0, duration.indexOf('.') + 2);
        System.out.println("\nDuration: " + duration + " seconds");
        if (!failures.isEmpty())
            System.exit(-1);
    }

    static public void parseFile(Path path, boolean dumpTree) throws IOException {
        CSharpParser parser = new CSharpParser(path);
        Node root = parser.CompilationUnit();
        if (retainInMemory) roots.add(root);
        // Node root = CongoCCParser.parseCSharpFile(path);
        if (dumpTree) {
            root.dump("");
        }
        if (!quiet) {
           System.out.println(path.getFileName().toString() + " parsed successfully.");
        }
        //if ()
        //
        // Now parse using the internal CSharp compiler. We assume that no exception
        // means a successful parse.
        //
        // org.congocc.parser.Node internalRoot = new
        // org.congocc.parser.csharp.CSParser(path).CompilationUnit();
        // System.out.println(path.getFileName().toString() + " parsed successfully
        // (internal compiler).");
    }

    static void addPaths(Path path, List<Path> paths) throws IOException {
        Files.walk(path).forEach(p->{
            if (!Files.isDirectory(p)) {
                if (p.toString().endsWith(".cs")) {
                    paths.add(p);
                }
                else if (p.toString().endsWith(".zip")) {
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

    static public void usage() {
        System.out.println("Usage: java CSParse <sourcefiles or directories>");
        System.out.println("If you just pass it one C# source file, it dumps the AST");
        System.exit(-1);
    }
}
