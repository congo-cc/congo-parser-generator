package org.congocc.codegen.csharp;

import org.congocc.parser.*;
import org.congocc.parser.csharp.CSToken;
import org.congocc.parser.csharp.ast.Block;
import org.congocc.parser.csharp.ast.Comment;
import org.congocc.parser.csharp.ast.ConstantDeclaration;
import org.congocc.parser.csharp.ast.Delimiter;
import org.congocc.parser.csharp.ast.FieldDeclaration;
import org.congocc.parser.csharp.ast.ForStatement;
import org.congocc.parser.csharp.ast.Identifier;
import org.congocc.parser.csharp.ast.InterpolatedString;
import org.congocc.parser.csharp.ast.KeyWord;
import org.congocc.parser.csharp.ast.Literal;
import org.congocc.parser.csharp.ast.Operator;
import org.congocc.parser.csharp.ast.PropertyBody;
import org.congocc.parser.csharp.ast.PropertyDeclaration;
import org.congocc.parser.csharp.ast.Type;
import org.congocc.parser.csharp.ast.TypeDeclaration;
import org.congocc.parser.csharp.ast.TypeParameterList;
import org.congocc.parser.csharp.ast.TypeArgumentList;
import org.congocc.parser.csharp.ast.UnaryExpression;

import static org.congocc.parser.csharp.CSToken.TokenType.*;

/**
 * A fairly effective CSharp pretty printer. It is far from perfect and 
 * will doubtless be refined over the coming while. But it is good enough
 * for now. There are so many things to do. It allows us to get rid of all 
 * the messiness in the templates relating to keeping track of indentation.
 * There may be a possibility of merging this with the JavaFormatter and
 * eventually the (as yet unwritten) Python formatter. But this is still
 * only 200 LOC.
 * @author revusky
 */
public class CSharpFormatter extends Node.Visitor {

    {this.visitUnparsedTokens = true;}

    private final StringBuilder buffer = new StringBuilder();
    private int currentIndentation;
    private final int indentAmount = 4;
    private final String eol = "\n";

    public String getText() {
        if (buffer.charAt(buffer.length()-1) != '\n') buffer.append('\n');
        return buffer.toString();
    }
    
    void visit(TypeDeclaration decl) {
        newLine(true);
        recurse(decl);
        newLine(true);
    }

    void visit(FieldDeclaration fd) {
        recurse(fd);
        if (!(fd.nextSibling() instanceof FieldDeclaration)) {
            newLine(true);
        }
    }

    void visit(ConstantDeclaration cd) {
        recurse(cd);
        if (!(cd.nextSibling() instanceof ConstantDeclaration))
        newLine(true);
    }
    
    void visit(PropertyDeclaration pd) {
        recurse(pd);
        if (!(pd.nextSibling() instanceof PropertyDeclaration)) {
            newLine(true);
        }
    }

    void visit(Type type)  {
        recurse(type);
        addSpaceIfNecessary();
    }


    void visit(CSToken tok) {
        buffer.append(tok.toString());
    }

    void visit(Block block) {
        recurse(block);
        if (block.getParent().getParent() instanceof TypeDeclaration) {
            newLine(true);
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
            if (delimiter.getParent().getParent() instanceof TypeDeclaration || delimiter.getParent() instanceof PropertyBody) {
                newLine(true);
            }
        }
        else if (type == LBRACKET) {
            trimTrailingWhitespace();
            buffer.append('[');
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
            buffer.append(delimiter);
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
            buffer.append(op);
        }
        else if (op.getParent() instanceof UnaryExpression) {
            buffer.append(op);
        }
        else {
            addSpaceIfNecessary();
            buffer.append(op);
            buffer.append(' ');
        }
    }

    void visit(Comment comment) {
        buffer.append(comment.toString());
        newLine();
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
        if (type == IF || type == WHILE || type == FOR || type == FOREACH || type == WHEN) {
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

    void visit(InterpolatedString irs) {
        buffer.append(irs.getSource());
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
            if (buffer.length() == 0) break;
            lastChar = buffer.codePointBefore(buffer.length());
        }
    }

    private boolean atLineStart() {
        int pos = buffer.length() -1;
        while (pos >= 0) {
            char ch = buffer.charAt(pos--);
            if (ch == '\n') return true;
            if (!Character.isWhitespace(ch)) return false;
        }
        return true;
    }

    private int currentLineLength() {
        return buffer.length() - buffer.lastIndexOf(eol) - eol.length();
    }
}
