#var extension = globals::getStringSetting("TEST_EXTENSION", "")
#var testProduction = globals::getStringSetting("TEST_PRODUCTION", "")
package ${settings.parserPackage}.test;

#if !extension.empty && !testProduction.empty
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.Path;
import ${settings.parserPackage}.${settings.parserClassName};

/#if

/**
 * A test harness for parsing Java files from the command line.
 */
public class ParseFiles {
#if !extension.empty && !testProduction.empty
    static private List<Path> paths = new ArrayList<>(),
                              failures = new ArrayList<>(),
                              successes = new ArrayList<>();
    static private FileSystem fileSystem = FileSystems.getDefault();

    static private void addPaths(Path path, List<Path> paths) throws IOException {
        Files.walk(path).forEach(p -> {
            if (!Files.isDirectory(p)) {
                if (p.toString().endsWith(".${extension}")) {
                    paths.add(p);
                }
                else if (p.toString().endsWith(".zip") || p.toString().endsWith(".jar")) {
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

    static private void parseFile(Path path) {
        try {
            ${settings.parserClassName} parser = new ${settings.parserClassName}(path);

            parser.${testProduction}();
            String s = String.format("The Java impl parsed %s.", path);

            System.out.println(s);
            successes.add(path);
        }
        catch (Exception e) {
            failures.add(path);
            String s = String.format("The Java impl failed to parse %s:", path);
            System.out.println(s);
            e.printStackTrace();
        }
    }

/#if

#if !extension.empty && !testProduction.empty
    static public void main(String args[]) throws IOException {
#else
    static public void main(String args[]) {
/#if
#if extension.empty
        System.out.println();
#elif testProduction.empty
#else
        for (String arg : args) {
            Path path = fileSystem.getPath(arg);
            if (!Files.exists(path)) {
                System.err.println("File " + path + " does not exist.");
                continue;
            }
            addPaths(path, paths);
        }
        if (paths.isEmpty()) {
            System.out.println("Usage: java ParseFiles <sourcefiles or directories>");
            System.exit(1);
        }
        long startTime = System.currentTimeMillis();
        Stream<Path> stream = paths.stream();
        stream.forEach(path -> parseFile(path));
        System.out.println("\nParsed " + successes.size() + " files successfully");
        System.out.println("Failed on " + failures.size() + " files");
        System.out.println("\nDuration: " + (System.currentTimeMillis() - startTime) + " milliseconds");
/#if
    }
}
