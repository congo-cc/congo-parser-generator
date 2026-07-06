import org.congocc.templates.core.parser.*;
import org.congocc.templates.core.parser.Token.TokenType;
import java.io.IOException;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.FileSystems;


// A little utility to swap the dot and double-colon
// in a CTL template.
public class SwapDotDoubleColon extends Node.Visitor {
    {visitUnparsedTokens = true;}
    StringBuilder buf = new StringBuilder();
    static public void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java -cp congocc.jar SwapDotDoubleColon.java <template files>");
        }
        for (String arg : args) {
            var path = FileSystems.getDefault().getPath(".", arg);
            var ctlParser = new CTLParser(path);
            var root = ctlParser.Root();
            var visitor = new SwapDotDoubleColon();
            System.out.println("Converting file: " + path);
            visitor.visit(root);
            try (var out = Files.newBufferedWriter(path)) {
                out.write(visitor.buf.toString());
            }
        }
    }

    void visit(Token tok) {
        buf.append(tok.getPrecedingSkippedChars());
        if (tok.getType() == TokenType.DOUBLE_COLON) {
            buf.append(".");
        }
        else if (tok.getType() == TokenType.DOT) {
            buf.append("::");
        }
        else {
            buf.append(tok.getSource());
        }
    }
}