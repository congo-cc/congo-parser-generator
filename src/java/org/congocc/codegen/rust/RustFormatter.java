package org.congocc.codegen.rust;

import org.congocc.parser.Node;
import org.congocc.parser.rust.ast.*;
import org.congocc.parser.rust.RustParser;
import org.congocc.parser.rust.RustToken;
import static org.congocc.parser.rust.RustToken.TokenType.*;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public class RustFormatter extends Node.Visitor {
    private StringBuilder buffer = new StringBuilder();
    private int currentIndentation;
    private final int indentAmount = 4;
    private final String eol = "\n";

    void visit(RustToken tok) {
        switch (tok.getType()) {
            case LBRACE -> {
                buffer.append(' ');
                buffer.append(tok);
                indent();
            }
            case RBRACE -> {
                dedent();
                buffer.append(tok);
                newLine();
            }
            case SEMICOLON -> {
                buffer.append(tok);
                newLine();
            }
            default -> {buffer.append(tok);}
        }
    }

    void visit(KeyWord kw) {
        buffer.append(kw);
        buffer.append(' ');
    }

    private void dedent() {
        currentIndentation -= indentAmount;
        newLine();
    }

    private void indent() {
        buffer.append(eol);
        currentIndentation += indentAmount;
        indentLine();
    }

    private void newLine() {
        buffer.append(eol);
        indentLine();
    }

    private void indentLine() {
        for (int i=0; i<currentIndentation; i++) {
            buffer.append(' ');
        }
    }

    static private FileSystem fileSystem = FileSystems.getDefault();

    static public void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java org.congocc.codegen.rust.RustFormatter <filename>");
        }
        else formatFile(args[0]);
    }

    static void formatFile(String filename) throws IOException {
        Path path = fileSystem.getPath(filename);
        RustParser parser = new RustParser(path);
        Crate root = parser.Crate();
        RustFormatter formatter = new RustFormatter();
        formatter.visit(root);
        System.out.println(formatter.buffer);
    }
}



