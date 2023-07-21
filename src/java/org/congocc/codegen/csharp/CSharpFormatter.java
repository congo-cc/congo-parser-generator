package org.congocc.codegen.csharp;

import org.congocc.parser.*;
import org.congocc.parser.csharp.CSToken;
import org.congocc.parser.csharp.ast.Delimiter;
import org.congocc.parser.csharp.ast.ForStatement;
import org.congocc.parser.csharp.ast.Identifier;
import org.congocc.parser.csharp.ast.KeyWord;
import org.congocc.parser.csharp.ast.Literal;
import org.congocc.parser.csharp.ast.Operator;
import org.congocc.parser.csharp.ast.TypeDeclaration;
import org.congocc.parser.csharp.ast.TypeParameterList;
import org.congocc.parser.csharp.ast.UnaryExpression;
import org.congocc.parser.csharp.ast.TypeArgumentList;

import static org.congocc.parser.csharp.CSToken.TokenType.*;

/**
 * The beginnings of a Node.Visitor subclass for pretty-printing C# source code.
 * It does nothing right now, except echo the text.
 * @author revusky
 */
public class CSharpFormatter extends Node.Visitor {

    {this.visitUnparsedTokens = true;}

    private StringBuilder buffer = new StringBuilder();
    private int currentIndentation, indentAmount = 4;
    private String eol = "\n";

    public String getText() {
        if (buffer.charAt(buffer.length()-1) != '\n') buffer.append('\n');
        return buffer.toString();
    }

    void visit(TypeDeclaration decl) {
        newLine(true);
        recurse(decl);
        newLine(true);
    }

    void visit(CSToken tok) {
        CSToken.TokenType type = tok.getType();
        if (type == SINGLE_LINE_COMMENT) {
            buffer.append(tok.toString());
            newLine();
        }
        else if (type == LBRACE) {
            addSpaceIfNecessary();
            buffer.append('{');
            indent();
        }
        else if (type == RBRACE) {
            dedent();
            buffer.append('}');
        }
        else if (type == COMMA) {
            trimTrailingWhitespace();
            buffer.append(", ");
        }
        else if (type == RPAREN) {
            trimTrailingWhitespace();
            buffer.append(')');
        }
        else if (type == SEMICOLON) {
            buffer.append(';');
            if (!(tok.getParent() instanceof ForStatement)) {
                newLine();
            } else {
                buffer.append(' ');
            }
        }
        else {
            buffer.append(tok.toString());
        }
    }


    void visit(Delimiter delimiter) {
        CSToken.TokenType type = delimiter.getType();
        if (type == LBRACE) {
            addSpaceIfNecessary();
            buffer.append('{');
            indent();
        }
        else if (type == RBRACE) {
            dedent();
            buffer.append('}');
        }
        else if (type == COMMA) {
            trimTrailingWhitespace();
            buffer.append(", ");
        }
        else if (type == RPAREN) {
            trimTrailingWhitespace();
            buffer.append(')');
        }
        else if (type == SEMICOLON) {
            buffer.append(';');
            if (!(delimiter.getParent() instanceof ForStatement)) {
                newLine();
            } else {
                buffer.append(' ');
            }
        }
        else {
            buffer.append(delimiter.toString());
        }
    }

    void visit(Literal literal) {
        if (buffer.length() > 0) {
            int precedingChar = buffer.codePointBefore(buffer.length());
            if (Character.isLetterOrDigit(precedingChar) || precedingChar=='}') {
                buffer.append(' ');
            }
        }
        buffer.append(literal.toString());
    }

    void visit(Operator op) {
        CSToken.TokenType type = op.getType();
        if (type == DOT) {
            trimTrailingWhitespace();
            buffer.append('.');
        }
        else if ((type == LT || type == GT) && (op.getParent() instanceof TypeParameterList || op.getParent() instanceof TypeArgumentList)) {
            trimTrailingWhitespace();
            buffer.append(op.toString());
        }
        else if (op.getParent() instanceof UnaryExpression) {
            buffer.append(op.toString());
        }
        else {
            addSpaceIfNecessary();
            buffer.append(op.toString());
            buffer.append(' ');
        }
    }

    void visit(KeyWord kw) {
        if (buffer.length() > 0) {
            int precedingChar = buffer.codePointBefore(buffer.length());
            if (Character.isLetterOrDigit(precedingChar) || precedingChar=='}') {
                buffer.append(' ');
            }
        }
        buffer.append(kw.toString());
        CSToken.TokenType type = kw.getType();
        if (type == IF || type == WHILE || type == FOR || type == FOREACH) {
            buffer.append(' ');
        }
    }

    void visit(Identifier id) {
        if (buffer.length() > 0) {
            int precedingChar = buffer.codePointBefore(buffer.length());
            if (Character.isLetterOrDigit(precedingChar) || precedingChar=='}') {
                buffer.append(' ');
            }
        }
        buffer.append(id.toString());
    }


    private void addSpaceIfNecessary() {
        if (buffer.length()==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        if (!Character.isWhitespace(lastChar)) buffer.append(' ');
    }

    private void indent() {
        currentIndentation += indentAmount;
        newLine();
    }

    private void dedent() {
        currentIndentation -= indentAmount;
        newLine();
    }

    private void newLine() {
        newLine(false);
    }

    private void newLine(boolean ensureBlankLine) {
        trimTrailingWhitespace();
        buffer.append(eol);
        if (ensureBlankLine) {
            buffer.append(eol);
        }
        for (int i = 0; i<currentIndentation; i++) buffer.append(' ');
    }

    private void trimTrailingWhitespace() {
        if (buffer.length() ==0) return;
        int lastChar = buffer.codePointBefore(buffer.length());
        while (Character.isWhitespace(lastChar)) {
            buffer.setLength(buffer.length()-1);
            if (lastChar > 0xFFFF) buffer.setLength(buffer.length()-1);
            lastChar = buffer.codePointBefore(buffer.length());
        }
    }

    private int currentLineLength() {
        return buffer.length() - buffer.lastIndexOf(eol) - eol.length();
    }
}
