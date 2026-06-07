package org.congocc.codegen.csharp;

import org.congocc.parser.*;
import org.congocc.parser.csharp.ast.*;

import static org.congocc.parser.csharp.CSharpToken.TokenType.*;

import java.util.EnumSet;

import org.congocc.codegen.AbstractCodeFormatter;

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
public class CSharpFormatter extends AbstractCodeFormatter {
    {
        separatedBySpaces = EnumSet.of(IF,WHILE,FOR,FOREACH,WHERE,WHEN,BREAK,CONTINUE,RETURN,
                                       ASSIGN, PLUSASSIGN,MINUSASSIGN,SLASHASSIGN,
                                       REMASSIGN,ANDASSIGN,ORASSIGN,XORASSIGN,
                                       LEFT_SHIFT_ASSIGN,RIGHT_SHIFT_ASSIGN,
                                       UNSIGNED_RIGHT_SHIFT_ASSIGN, DOUBLE_HOOK_EQUALS,
                                       IS,ARROW,COLON);
    }

    void visit(Delimiter delimiter) {
        switch(delimiter.getType()) {
            case LBRACE -> {
                addSpaceIfNecessary();
                buffer.append('{');
                indent();
            }
            case RBRACE -> {
                dedent();
                buffer.append('}');
                Node parent = delimiter.getParent();
                Node gp = parent.getParent();
                boolean isControlStatement = (gp instanceof IfStatement || gp instanceof ForStatement ||
                                            gp instanceof ForeachStatement || gp instanceof WhileStatement ||
                                            gp instanceof SwitchStatement || gp instanceof TryStatement ||
                                            gp instanceof CatchClause || gp instanceof FinallyClause);
                if (gp instanceof TypeDeclaration || isControlStatement || parent instanceof PropertyBody) {
                    newLine(!isControlStatement);
                }
            }
            case LBRACKET -> {
                trimTrailingWhitespace();
                buffer.append('[');
            }
            case COMMA -> {
                trimTrailingWhitespace();
                buffer.append(", ");
            }
            case RPAREN -> {
                trimTrailingWhitespace();
                buffer.append(')');
            }
            case SEMICOLON -> {
                buffer.append(';');
                if (!(delimiter.getParent() instanceof ForStatement)) {
                    newLine();
                } else {
                    buffer.append(' ');
                }
            }
            default -> defaultTokenOutput(delimiter);
        }
    }

    void visit(TypeDeclaration decl) {
        newLine(true);
        recurse(decl);
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

    void visit(Type type)  {
        recurse(type);
        addSpaceIfNecessary();
    }

    void visit(MethodDeclaration md) {
        newLine(true);
        recurse(md);
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
        switch (op.getType()) {
            case DOT -> {
                trimTrailingWhitespace();
                buffer.append('.');
            }
            case LT,GT -> {
                if (op.getParent() instanceof TypeParameterList || op.getParent() instanceof TypeArgumentList) {
                    trimTrailingWhitespace();
                    buffer.append(op);
                    if (op.getType() == GT && op.nextCachedToken().getType() != GT) buffer.append(' ');
                } else {
                    defaultTokenOutput(op);
                }
            }
            default -> defaultTokenOutput(op);
        }
    }

    void visit(Comment comment) {
        buffer.append(comment.toString());
        newLine();
    }

    void visit(KeyWord kw) {
        switch (kw.getType()) {
            case RETURN,CONTINUE,BREAK -> {
                if (kw.nextCachedToken().getType() == SEMICOLON) {
                    addSpaceIfNecessary();
                    buffer.append(kw);
                } else {
                    defaultTokenOutput(kw);
                }
            }
            default -> defaultTokenOutput(kw);
        }
    }

    void visit(InterpolatedString irs) {
        buffer.append(irs.getSource());
    }
}
