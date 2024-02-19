import java.io.*;
import java.nio.file.Path;
import java.util.*;

import org.parsers.csharp.CSharpParser;
import org.parsers.csharp.Node;


/**
 * A test harness for parsing C# source code
 */
public class CSParse {

    static public ArrayList<Node> roots = new ArrayList<>();

    static public void main(String[] args) {
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("Input file not specified");
            }
            File file = new File(args[0]);
            if (!file.exists()) {
                throw new IllegalArgumentException("Input file does not exist: " + file);
            }
            parseFile(file, true);
            System.exit(0);
        }
        catch (Exception e) {
            System.err.println(String.format("Failed: %s", e));
            System.exit(1);
        }
    }

    static public void parseFile(File file, boolean dumpTree) throws IOException {
        Path path = file.toPath();
        CSharpParser parser = new CSharpParser(path);
        Node root = parser.CompilationUnit();
        if (dumpTree) {
            root.dump("");
        }
    }
}
