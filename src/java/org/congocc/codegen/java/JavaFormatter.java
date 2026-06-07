package org.congocc.codegen.java;

import org.congocc.parser.*;
import org.congocc.parser.tree.*;
import org.congocc.parser.Token.TokenType;
import org.congocc.codegen.AbstractCodeFormatter;
import static org.congocc.parser.Token.TokenType.*;

import java.util.EnumSet;

/**
 * A Node.Visitor subclass for pretty-printing java source code.
 * Doubtless it has some rough edges, but is good enough for our purposes.
 * @author revusky
 */
public class JavaFormatter extends AbstractCodeFormatter {
    {
        separatedBySpaces = EnumSet.of(ANDASSIGN, ASSIGN, BIT_AND, BIT_OR,
            CATCH, CASE, COLON, DO, EQ, EXTENDS, FOR, GE, GT, HOOK, IF,
            INSTANCEOF, LAMBDA, LBRACE, LE, LSHIFT, LSHIFTASSIGN, LT, MINUS,
            MINUSASSIGN, NE, ORASSIGN, PLUS, PLUSASSIGN, REM, REMASSIGN,
            RSIGNEDSHIFT, RSIGNEDSHIFTASSIGN, RUNSIGNEDSHIFT,
            RUNSIGNEDSHIFTASSIGN, SC_AND, SC_OR, SLASH, SLASHASSIGN, STAR,
            STARASSIGN, THROWS, WHILE, XOR, XORASSIGN);
    }

    void visit(TypeParameters tps) {
        addSpaceIfNecessary(); // spaced from method modifiers
        recurse(tps);
    }

    protected void visit(Operator op) {
        switch (op.getType()) {
            case LT:
                if (op.getParent() instanceof RelationalExpression) {
                    defaultTokenOutput(op);
                } else {
                    buffer.append(op);
                }
                break;
            case GT:
                if (op.getParent() instanceof RelationalExpression) {
                    defaultTokenOutput(op);
                } else {
                    buffer.append(op);
                    TokenType tokenType = op.nextCachedToken().getType();
                    if (tokenType != GT && tokenType != COMMA && tokenType != LPAREN && tokenType != RPAREN && tokenType != LBRACE)
                        addSpaceIfNecessary();
                }
                break;
            case HOOK:
                if (op.getParent() instanceof TernaryExpression) {
                    defaultTokenOutput(op);
                } else {
                    buffer.append(op);
                    if (op.nextCachedToken().getType() != GT) buffer.append(' ');
                }
                break;
            case STAR:
                if (op.getParent() instanceof ImportDeclaration)
                    buffer.append(op); // no spaces for import statements
                else {
                    defaultTokenOutput(op);
                }
                break;
            case MINUS:
                if (op.getPrevious().getType() == RPAREN || op.getPrevious() instanceof Identifier) {
                    addSpaceIfNecessary();
                }
                buffer.append(op);
                int nextChar = op.getNext().toString().codePointAt(0);
                if (op.getPrevious() instanceof Identifier // for -1 or 2 - 1
                        || op.getPrevious() instanceof Delimiter
                        || !Character.isDigit(nextChar))
                    addSpaceIfNecessary();
                break;
            default : defaultTokenOutput(op);
        }
    }

    void visit(KeyWord kw) {
        defaultTokenOutput(kw);
        if (kw.getType() == RETURN) {
            if (kw.getNext().getType() != SEMICOLON) addSpaceIfNecessary();
        }
    }

    void visit(Delimiter delimiter) {
        switch (delimiter.getType()) {
            case COMMA -> {
                defaultTokenOutput(delimiter);
                if (currentLineLength() > maxLineLength
                    && (delimiter.getParent() instanceof ArrayInitializer
                        || delimiter.getParent() instanceof EnumBody))
                {
                        newLine();
                }
                else buffer.append(' ');
            }
            case RBRACKET -> {
                defaultTokenOutput(delimiter);
                TokenType nextType = delimiter.getNext().getType();
                if (nextType != LBRACKET && nextType != SEMICOLON && nextType != GT
                        && nextType != RPAREN && nextType != COMMA && nextType != DOT)
                    addSpaceIfNecessary();
            }
            case LBRACE -> {
                defaultTokenOutput(delimiter);
                if (!(delimiter.getParent() instanceof ArrayInitializer)) {
                    currentIndentation += indentAmount;
                    newLine();
                }
            }
            case RBRACE -> {
                boolean endOfArrayInitializer = delimiter.getParent() instanceof ArrayInitializer;
                if (!endOfArrayInitializer) {
                    currentIndentation -= indentAmount;
                    newLine();
                }
                buffer.append(delimiter);
                Token token = delimiter.getNext();
                if (!endOfArrayInitializer && token != null && token.getType() != SEMICOLON) {
                    if (token.getType()==CATCH || token.getType() == ELSE || token.getType()==FINALLY)
                        addSpaceIfNecessary(); // space for multi block statements
                    else newLine();
                }
            }
            case RPAREN -> {
                buffer.append(delimiter);
                if (delimiter.getParent() instanceof CastExpression)
                    addSpaceIfNecessary();
            }
            case SEMICOLON -> {
                if (buffer.charAt(buffer.length() - 1) != ' ') { // detect rogue semicolons
                    buffer.append(delimiter);
                    if (!(delimiter.getParent() instanceof ForStatement)
                            && !(delimiter.getParent() instanceof ImportDeclaration))
                        newLine();
                } else for (int i = 1; i <= 6; i++) // remove rogue semicolons
                    if (buffer.charAt(buffer.length() - 1) == ' ' || buffer.charAt(buffer.length() - 1) == '\n')
                        buffer.setLength(buffer.length() - 1);
            }
            default -> defaultTokenOutput(delimiter);
        }
    }

    void visit(MultiLineComment comment) {
        startNewLineIfNecessary();
        appendIndentedText(comment.toString());
        newLine();
    }

    void visit(SingleLineComment comment) {
        if (startsNewLine(comment)) {
            newLine();
        }
        else {
            addSpaceIfNecessary();
        }
        buffer.append(comment);
        newLine();
    }

    void visit(Whitespace ws) {}

    void visit(TypeDeclaration td) {
        newLine(true);
        recurse(td);
        newLine(true);
    }

    void visit(Statement stmt) {
        if (stmt.getParent() instanceof IfStatement)
            addSpaceIfNecessary();
        recurse(stmt);
    }

    void visit(PackageDeclaration pd) {
        recurse(pd);
        newLine(true);
    }

    void visit(ImportDeclaration id) {
        recurse(id);
        buffer.append('\n');
        if (!(id.nextSibling() instanceof ImportDeclaration)) {
            buffer.append('\n');
        }
    }

    void visit(MethodDeclaration md) {
        if (!(md.previousSibling() instanceof MethodDeclaration) && !(md.previousSibling() instanceof ConstructorDeclaration)) newLine(true);
        recurse(md);
        newLine(true);
    }

    void visit(ConstructorDeclaration cd) {
        if (!(cd.previousSibling() instanceof MethodDeclaration) && !(cd.previousSibling() instanceof ConstructorDeclaration)) newLine(true);
        recurse(cd);
        newLine(true);
    }

    void visit(FieldDeclaration fd) {
        if (!(fd.previousSibling() instanceof FieldDeclaration)) {
            newLine();
        }
        recurse(fd);
        newLine();
    }

    void visit(LocalVariableDeclaration lvd) {
        boolean inForStatement = (lvd.getParent() instanceof ForStatement);
        if (!inForStatement) newLine();
        recurse(lvd);
        if (!inForStatement) newLine();
    }

    void visit(Annotation ann) {
        if (!(ann.previousSibling() instanceof Annotation)) {
            newLine();
        }
        recurse(ann);
        newLine();
    }

    void visit(ClassicCaseStatement ccs)  {
        visit(ccs.firstChildOfType(ClassicSwitchLabel.class));
        currentIndentation += indentAmount;
        newLine();
        for (Statement stmt : ccs.childrenOfType(Statement.class)) {
            visit(stmt);
        }
        currentIndentation-=indentAmount;
        newLine();
    }
}
