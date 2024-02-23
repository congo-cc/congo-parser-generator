import org.parsers.preprocessor.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;

/**
 * A test harness for parsing files containing preprocessor directives from the command line.
 */
public class PPParse {
    static public void main(String[] args) throws Exception {
        List<Path> paths = new ArrayList<>();
        for (String arg : args) {
            Path path = Paths.get(arg);
            if (!Files.exists(path)) {
                System.err.println("File " + path + " does not exist.");
                continue;
            }
            Files.walk(path, 1).forEach(p -> {
                if (!Files.isDirectory(p)) {
                    paths.add(p);
                }
            });
        }
        if (paths.isEmpty()) usage();
        paths.parallelStream().forEach(path -> parseFile(path));
    }

    static public void parseFile(Path path) {
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            PreprocessorParser parser = new PreprocessorParser(path);
            BitSet lines = parser.PP_Root();
            if (path.toString().endsWith(".cs")) {
                outputLines(content, lines);
            }
        }
        catch (Exception e) {
            System.err.println("Error processing file: " + path);
            e.printStackTrace();
        }
    }

    static public void outputLines(String content, BitSet lineMarkers) {
        String[] lines = content.split("\r\n|\n|\r");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (lineMarkers.get(i + 1)) System.out.println(line);
        }
    }

    static public void usage() {
        System.out.println("Usage: java PPParse <sourcefiles_or_dirs> ...");
        System.out.println("The program just outputs the file applying the preprocessor logic.");
        System.exit(-1);
    }
}
