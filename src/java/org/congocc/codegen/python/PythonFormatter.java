package org.congocc.codegen.python;

import java.util.EnumSet;
import org.congocc.parser.Node;
import org.congocc.parser.python.Token;
import org.congocc.parser.python.Token.TokenType;
import static org.congocc.parser.python.Token.TokenType.*;
import org.congocc.parser.python.ast.*;

public class PythonFormatter extends Node.Visitor {
    private static final String defaultIndent = "    ";
    private String currentIndent = "";
    private StringBuilder buffer;

//    private EnumSet<TokenType> alwaysPrependSpace = EnumSet.of(AND, AS, FOR, IMPORT, LAMBDA, NOT, OR, 
//                                                               IN, IF, ELSE, ELIF, IS, WHILE);
//    private EnumSet<TokenType> alwaysAppendSpace = EnumSet.of(AND, AS, DEF, EXCEPT, FOR, LAMBDA, OR, 
//                                                              EQ, CLASS, COMMA, FROM, IN, IS, IF, 
//                                                              ELSE, ELIF, IMPORT, NOT, RETURN, 
//                                                              WHILE, WITH);


    public String format(PythonNode code, int indentLevel) {
        buffer = new StringBuilder();
        for (int i = 0; i < indentLevel; i++) {
            currentIndent += defaultIndent;
        }
        visit(code);
        return buffer.toString();
    }

    public String format(PythonNode code) {
        return format(code, 0);
    }

    void visit(IndentToken it) {
        buffer.append(defaultIndent);
        currentIndent += defaultIndent;
    }

    void visit(DedentToken dt) {
        currentIndent = currentIndent.substring(0, currentIndent.length()- defaultIndent.length());
        buffer.setLength(buffer.length() - defaultIndent.length());
    }

    void visit(Newline nl) {
        buffer.append("\n");
        buffer.append(currentIndent);
    }

    void visit(Token tok) {
//        if (alwaysPrependSpace.contains(tok.getType())) addSpaceIfNecessary();
        addSpaceIfNecessary();
        buffer.append(tok.getImage());
//        if (alwaysAppendSpace.contains(tok.getType())) addSpaceIfNecessary();
        addSpaceIfNecessary();
    }

    private void addSpaceIfNecessary() {
        if (buffer.length()==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        if (!Character.isWhitespace(lastChar)) buffer.append(' ');
    }
}