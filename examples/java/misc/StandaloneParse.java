import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

import javax.tools.JavaFileObject;
import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.io.IOException;

/**
 * A test harness to benchmark the Java parser inside of javac.
 * The magic incantation to compile is:
   javac --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
      StandaloneParse.java
 * and the magic incantation to run it is:
   java --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
      --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
      StandaloneParse files
 *  You can add the -p flag to have it run in multiple threads
 *  or the -q to have it run more quietly
 *  or the -r to have it retain the ASTs in memory, if you want to
 *  test memory usage.
 */
public class StandaloneParse {
    int successes, failures;
    boolean parallel, quiet, retainTrees;
    ArrayList<Object> roots = new ArrayList<>();
    public void main(String[] args) throws IOException {
        long start = System.currentTimeMillis();
        ArrayList<String> filenames = new ArrayList<>();
        for (var arg : args) {
            if (arg.charAt(0) == '-') {
                if (arg.substring(1).startsWith("p")) parallel = true;
                else if (arg.substring(1).startsWith("q")) quiet = true;
                else if (arg.substring(1).startsWith("r")) retainTrees = true;
            }
            else filenames.add(arg);
        }
        if (parallel) IO.println("Parsing in multiple threads");
        Stream<String> stream = parallel ? filenames.parallelStream() : filenames.stream();
        stream.forEach(f->parse(f));
        IO.println("Parsed " + successes + " files successfully.");
        IO.println("Failed on " + failures + " files.");
        IO.println("Duration: " + (System.currentTimeMillis() - start) + " milliseconds.");
    }

    public void parse(String filename) {
        if (!quiet) IO.println("Parsing " + filename);
        var path = FileSystems.getDefault().getPath(filename);
        byte[] bb = null;
        try {
            bb = Files.readAllBytes(path);
        } catch (IOException ioe) {

        }
        var source = new String(bb);
        Context context = new Context();

        // Sets up diagnostic reporting so parse errors don't just vanish
        JavacFileManager.preRegister(context);

        JavaFileObject fileObject = new SimpleSourceFileObject(source);

        Log log = Log.instance(context);
        log.useSource(fileObject);

        ParserFactory parserFactory = ParserFactory.instance(context);
        JavacParser parser = parserFactory.newParser(
            source,
            /* keepDocComments */ true,
            /* keepEndPos */ true,
            /* keepLineMap */ true
        );
        try {
            JCCompilationUnit tree = parser.parseCompilationUnit();
            ++successes;
            if (retainTrees) roots.add(tree);
        } catch (Exception e) {
            e.printStackTrace();
            ++failures;
        }
    }

    // A minimal in-memory JavaFileObject wrapping a String
    static class SimpleSourceFileObject extends javax.tools.SimpleJavaFileObject {
        private final String code;
        SimpleSourceFileObject(String code) {
            super(java.net.URI.create("string:///Bar.java"), Kind.SOURCE);
            this.code = code;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}