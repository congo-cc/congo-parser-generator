package org.congocc.codegen;
import java.util.Collections;
import java.util.Set;

import org.congocc.parser.Node;

/**
 * An abstract base class for objects that pretty-print
 * source code
 */
abstract public class AbstractCodeFormatter extends Node.Visitor {
    {this.visitUnparsedTokens = true;}

    protected StringBuilder buffer = new StringBuilder();
    protected int currentIndentation, indentAmount=4;
    protected int maxLineLength = 80;

    protected Set<? extends Node.NodeType> separatedBySpaces = Collections.emptySet();

    public String format(Node code) {
        buffer = new StringBuilder();
        visit(code);
        return getText();
    }

    public String getText() {
        if (buffer.charAt(buffer.length()-1) != '\n') buffer.append('\n');
        return buffer.toString();
    }

    protected void visit(Node.TerminalNode tok) {
        defaultTokenOutput(tok);
    }

    static public String toCRLF(String s) {
        StringBuilder buf = new StringBuilder();
        s.lines().forEach(line->{
            buf.append(line);
            buf.append("\r\n");
        });
        return buf.toString();
    }

    /**
     * A default visit handler to append a token to the buffer.
     * Takes into account that two identifier-ish tokens
     * cannot be adjacent. Also prepends/appends a space (if necessary)
     * based on the token types listed in separatedBySpaces
     */
    protected void defaultTokenOutput(Node.TerminalNode tok) {
        if (tok.getType().isEOF()) {
            buffer.append('\n');
            return;
        }
        if (buffer.length() == 0) {
            buffer.append(tok);
            if (separatedBySpaces.contains(tok.getType())) buffer.append(' ');
            return;
        }
        boolean prependSpace = separatedBySpaces.contains(tok.getType());
        if (!prependSpace) {
//            int nextChar = tok.toString().codePointAt(0);
            int nextChar = Character.codePointAt((CharSequence) tok, 0);
            int prevChar = buffer.codePointBefore(buffer.length());
            if ((Character.isUnicodeIdentifierPart(prevChar) || prevChar == ';')
                    && Character.isUnicodeIdentifierPart(nextChar)) {
                prependSpace = true;
            }
        }
        if (prependSpace) addSpaceIfNecessary();
        buffer.append(tok.toString());
        if (separatedBySpaces.contains(tok.getType())) buffer.append(' ');
    }

    protected void addSpaceIfNecessary() {
        if (buffer.length()==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        if (!Character.isWhitespace(lastChar)) buffer.append(' ');
    }

    protected void appendIndentation() {
        for (int i = 0; i<currentIndentation; i++) {
            buffer.append(' ');
        }
    }

    protected void appendIndentedText(String text) {
        text.lines().forEach(line->{
            appendIndentation();
            buffer.append(line.trim());
            buffer.append('\n');
        });
    }

    protected void startNewLineIfNecessary() {
        if (buffer.length() == 0) {
            return;
        }
        int lastNL = buffer.lastIndexOf("\n");
        if (lastNL + 1 == buffer.length()) {
            return;
        }
        String line = buffer.substring(lastNL+ 1);
        if (line.trim().length() ==0) {
            buffer.setLength(lastNL+1);
        } else {
            buffer.append('\n');
        }
    }

    protected void newLine() {
        newLine(false);
    }

    protected void newLine(boolean ensureBlankLine) {
        startNewLineIfNecessary();
        if (ensureBlankLine) {
            buffer.append('\n');
        }
        appendIndentation();
    }

    protected int currentLineLength() {
        int lastEOL = buffer.lastIndexOf("\n");
        if (lastEOL == -1) return buffer.length();
        return buffer.length() - lastEOL - 1;
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

    protected void trimTrailingSpaces() {
        if (buffer.length() ==0) return;
        while(buffer.charAt(buffer.length()-1) == ' ') {
            buffer.setLength(buffer.length()-1);
            if (buffer.length() == 0) break;
        }
    }

    protected void indent() {
        currentIndentation += indentAmount;
        newLine();
    }

    protected void dedent() {
        currentIndentation -= indentAmount;
        assert currentIndentation >= 0 : "Can't dedent into negative territory!";
        newLine();
    }
}
