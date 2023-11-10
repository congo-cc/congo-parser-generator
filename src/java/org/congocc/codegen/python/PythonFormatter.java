package org.congocc.codegen.python;

import org.congocc.parser.Node;
import org.congocc.parser.python.PythonToken;
import org.congocc.parser.python.ast.*;

public class PythonFormatter extends Node.Visitor {
    {this.visitUnparsedTokens = true;}

    private static final String defaultIndent = "    ";
    private String currentIndent = "";
    private StringBuilder buffer;

//    private EnumSet<TokenType> alwaysPrependSpace = EnumSet.of(AND, AS, FOR, IMPORT, LAMBDA, NOT, OR, 
//                                                               IN, IF, ELSE, ELIF, IS, WHILE);
//    private EnumSet<TokenType> alwaysAppendSpace = EnumSet.of(AND, AS, DEF, EXCEPT, FOR, LAMBDA, OR, 
//                                                              EQ, CLASS, COMMA, FROM, IN, IS, IF, 
//                                                              ELSE, ELIF, IMPORT, NOT, RETURN, 
//                                                              WHILE, WITH);


    protected String indent(String current, String indent, int level) {
        StringBuilder result = new StringBuilder();

        result.append(current);
        for (int i = 0; i < level; i++) {
            result.append(indent);
        }
        return result.toString();
    }

    public String format(Node code, int indentLevel) {
        buffer = new StringBuilder();
        currentIndent = indent(currentIndent, defaultIndent, indentLevel);
        visit(code);
        return buffer.toString();
    }

    public String format(Node code) {
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

    void visit(PythonToken tok) {
//        if (alwaysPrependSpace.contains(tok.getType())) addSpaceIfNecessary();
        addSpaceIfNecessary();
        buffer.append(tok.toString());
//        if (alwaysAppendSpace.contains(tok.getType())) addSpaceIfNecessary();
        addSpaceIfNecessary();
    }

    private void addSpaceIfNecessary() {
        if (buffer.length()==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        if (!Character.isWhitespace(lastChar)) buffer.append(' ');
    }
}