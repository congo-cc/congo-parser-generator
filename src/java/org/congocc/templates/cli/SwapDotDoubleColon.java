package org.congocc.templates.cli;

import org.congocc.templates.core.parser.*;
import org.congocc.templates.core.parser.Token.TokenType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;

// A little utility to swap the dot and double-colon
// in a CTL template.
public class SwapDotDoubleColon {
    StringBuilder buf = new StringBuilder();
    static public void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java -cp congocc.jar org.congocc.templates.cli.SwapDotDoubleColon <template files>");
        }
        for (String arg : args) {
            var path = FileSystems.getDefault().getPath(".", arg);
            var ctlParser = new CTLParser(path);
            var root = ctlParser.Root();
            System.out.println("Converting file: " + path);
            var children = root.getAllTokens(true);
            StringBuilder buf = new StringBuilder();
            for (Node n : children) {
                if (n instanceof Token tok) {
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
            try (var out = Files.newBufferedWriter(path)) {
                out.write(buf.toString());
            }
        }
    }
}