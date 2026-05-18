import org.congocc.codegen.java.JavaFormatter;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.tree.CompilationUnit;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression for end-of-line {@code //} comments when {@code visitUnparsedTokens} is enabled.
 */
public class JavaFormatterCommentTest {

    public static void main(String[] args) throws Exception {
        String source = """
                class C {
                    int x = 1; // trailing
                    int y = 2;
                }
                """;
        Path path = Files.createTempFile("JavaFormatterCommentTest", ".java");
        Files.writeString(path, source);
        CompilationUnit cu = CongoCCParser.parseJavaFile(path);
        String formatted = new JavaFormatter().format(cu);
        if (!formatted.contains("1; // trailing")) {
            System.err.println("--- formatted ---");
            System.err.print(formatted);
            System.err.println("--- end ---");
            throw new AssertionError("EOL comment should stay on the same line as the statement");
        }
        System.out.println("OK: end-of-line comment preserved");
    }
}
