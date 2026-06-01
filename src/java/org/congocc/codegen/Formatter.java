package org.congocc.codegen;
import java.util.Set;

import org.congocc.parser.Node;

abstract public class Formatter extends Node.Visitor {
    {this.visitUnparsedTokens = true;}

    protected String eol = "\n";
    protected StringBuilder buffer = new StringBuilder();
    protected int currentIndentation, indentAmount=4;
    protected int maxLineLength = 80;

    protected Set<? extends Node.NodeType> alwaysPrependSpace;
    protected Set<? extends Node.NodeType> alwaysAppendSpace;

    public String format(Node code) {
        buffer = new StringBuilder();
        visit(code);
        return buffer.toString();
    }

    protected void addSpaceIfNecessary() {
        if (buffer.length()==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        if (!Character.isWhitespace(lastChar)) buffer.append(' ');
    }

    protected void startNewLineIfNecessary() {
        if (buffer.length() == 0) {
            return;
        }
        int lastNL = buffer.lastIndexOf(eol);
        if (lastNL + eol.length() == buffer.length()) {
            return;
        }
        String line = buffer.substring(lastNL+ eol.length());
        if (line.trim().length() ==0) {
            buffer.setLength(lastNL+eol.length());
        } else {
            buffer.append(eol);
        }
    }

    protected void newLine() {
        newLine(false);
    }

    protected void newLine(boolean ensureBlankLine) {
        startNewLineIfNecessary();
        if (ensureBlankLine) {
            buffer.append(eol);
        }
        for (int i = 0; i<currentIndentation; i++ ) {
            buffer.append(' ');
        }
    }

    protected int currentLineLength() {
        return buffer.length() - buffer.lastIndexOf(eol) - eol.length();
    }

    protected boolean startsNewLine(Node.TerminalNode t) {
        Node.TerminalNode previousCachedToken = t.getTokenSource().previousCachedToken(t.getBeginOffset());
        return previousCachedToken == null || previousCachedToken.getEndLine() != t.getBeginLine();
    }

    protected void trimTrailingWhitespace() {
        if (buffer.length() ==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        while (Character.isWhitespace(lastChar)) {
            buffer.setLength(buffer.length()-1);
            if (lastChar > 0xFFFF) buffer.setLength(buffer.length()-1);
            if (buffer.length() == 0) break;
            lastChar = buffer.codePointBefore(buffer.length());
        }
    }
}