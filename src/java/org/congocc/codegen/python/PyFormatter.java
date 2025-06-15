package org.congocc.codegen.python;

import org.congocc.parser.*;
import org.congocc.parser.python.PythonToken;
import org.congocc.parser.python.ast.*;

public class PyFormatter extends Node.Visitor {
   {this.visitUnparsedTokens = true;}
    private StringBuilder buffer = new StringBuilder();
    private int currentIndentation;
    private final int indentAmount = 4;
    private final String eol = "\n";
    
    public String format(Node node) {
        visit(node);
        return getText();
    }

    public String getText() {
        if (buffer.charAt(buffer.length()-1) != '\n') buffer.append('\n');
        return buffer.toString();
    }

    void visit(PythonToken tok) {
        if (tok.startsLine()) {
            indentLine();
        }
        buffer.append(tok);
        char ch = followingChar(tok);
        if (ch == ' ') buffer.append(ch);
    }

    void visit(Comment tok) {
        String s = tok.subSequence(1,tok.length()).toString().trim().toLowerCase();
        if (s.startsWith("pywim:")) return;
        if (tok.startsLine()) {
            indentLine();
        }
        buffer.append(tok);
        buffer.append(eol);
    }

    void visit(IndentToken tok) {
        currentIndentation += indentAmount;
        assert currentIndentation >=0;
    }

    void visit(DedentToken tok) {
        currentIndentation -= indentAmount;
        assert currentIndentation >=0;
    }

    void visit(Newline tok) {
        if (!tok.isUnparsed()) {
            buffer.append(eol);
            return;
        } 
        if (tok.previousCachedToken() instanceof Newline prev) {
            if (!prev.isUnparsed()) {
                buffer.append(eol);
            }
        }
    }

    private void indentLine() {
        for (int i = 0; i < currentIndentation; i++) {
            buffer.append((char) ' ');
        }
    }

    private char followingChar(PythonToken tok) {
        try {
           return tok.getTokenSource().charAt(tok.getEndOffset());
        }
        catch (Exception e) {
            return 0xFFFF;
        }
    }
}   
