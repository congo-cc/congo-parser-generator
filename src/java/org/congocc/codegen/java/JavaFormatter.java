package org.congocc.codegen.java;

import org.congocc.parser.*;
import org.congocc.parser.tree.*;
import org.congocc.parser.Token.TokenType;
import static org.congocc.parser.Token.TokenType.*;

import java.util.EnumSet;

/**
 * A Node.Visitor subclass for pretty-printing java source code.
 * Doubtless it has some rough edges, but is good enough for our purposes.
 * @author revusky
 */
public class JavaFormatter extends Node.Visitor {

    {this.visitUnparsedTokens = true;}

    protected StringBuilder buf;
    private String indent = "    ";
    private String currentIndent = "";
    private String eol = "\n";
    private EnumSet<TokenType> alwaysPrependSpace = EnumSet.of(ASSIGN, COLON, LBRACE, THROWS, EQ, NE, LE, GE, PLUS, MINUS, SLASH, SC_AND, SC_OR, BIT_AND, BIT_OR, XOR, REM, LSHIFT, PLUSASSIGN, MINUSASSIGN, STARASSIGN, SLASHASSIGN, ANDASSIGN, ORASSIGN, XORASSIGN, REMASSIGN, LSHIFTASSIGN, RSIGNEDSHIFT, RUNSIGNEDSHIFT, RSIGNEDSHIFTASSIGN, RUNSIGNEDSHIFTASSIGN, LAMBDA, INSTANCEOF);
    private EnumSet<TokenType> alwaysAppendSpace = EnumSet.of(ASSIGN, COLON, DO, CATCH, CASE, FOR, IF, WHILE, THROWS, EXTENDS, EQ, NE, LE, GE, PLUS, SLASH, SC_AND, SC_OR, BIT_AND, BIT_OR, XOR, REM, LSHIFT, PLUSASSIGN, MINUSASSIGN, STARASSIGN, SLASHASSIGN, ANDASSIGN, ORASSIGN, XORASSIGN, REMASSIGN, LSHIFTASSIGN, RSIGNEDSHIFT, RUNSIGNEDSHIFT, RSIGNEDSHIFTASSIGN, RUNSIGNEDSHIFTASSIGN, LAMBDA, INSTANCEOF);
    private static final int MAX_LINE_LENGTH = 80;
    
    public String format(BaseNode code, int indentLevel) {
        buf = new StringBuilder();
        for (int i = 0; i < indentLevel; i++) {
            currentIndent += indent;
        }
        visit(code);
        return buf.toString();
    }

    public String format(BaseNode code) {
        return format(code, 0);
    }

    private void outputToken(Token tok) {
        if (buf.length() > 0) {
            int nextChar = tok.getImage().codePointAt(0);
            int prevChar = buf.codePointBefore(buf.length());
            if ((Character.isJavaIdentifierPart(prevChar) || prevChar == ';')
                    && Character.isJavaIdentifierPart(nextChar)) {
                addSpaceIfNecessary();
            }
            else if (alwaysPrependSpace.contains(tok.getType())) addSpaceIfNecessary();
        }
        buf.append(tok);
        if (alwaysAppendSpace.contains(tok.getType())) addSpaceIfNecessary();
    }

    void visit(Token tok) {
        if (tok.getType() == EOF) buf.append("\n");
        else outputToken(tok);
    }

    void visit(TypeParameters tps) {
        addSpaceIfNecessary(); // spaced from method modifiers
        recurse(tps);
    }

    void visit(Operator op) {
        switch (op.getType()) {
            case LT:
                if (op.getParent() instanceof RelationalExpression) {
                    addSpaceIfNecessary();
                    buf.append(op);
                    buf.append(' ');
                } else {
                    buf.append(op);
                }
                break;
            case GT:
                if (op.getParent() instanceof RelationalExpression) {
                    addSpaceIfNecessary();
                    buf.append(op);
                    buf.append(' ');
                } else {
                    buf.append(op);
                    TokenType tokenType = op.nextCachedToken().getType();
                    if (tokenType != GT && tokenType != COMMA && tokenType != LPAREN && tokenType != RPAREN && tokenType != LBRACE)
                        addSpaceIfNecessary();
                }
                break;
            case HOOK:
                if (op.getParent() instanceof TernaryExpression) {
                    addSpaceIfNecessary();
                    buf.append(op);
                    buf.append(' ');
                } else {
                    buf.append(op);
                    if (op.nextCachedToken().getType() != GT) buf.append(' ');
                }
                break;
            case STAR:
                if (op.getParent() instanceof ImportDeclaration)
                    buf.append(op); // no spaces for import statements
                else {
                    addSpaceIfNecessary();
                    buf.append(op);
                    buf.append(' ');
                }
                break;
            case MINUS:
                if (op.getPrevious().getType() == RPAREN || op.getPrevious() instanceof Identifier)
                    addSpaceIfNecessary();
                buf.append(op);
                int nextChar = op.getNext().getImage().codePointAt(0);
                if (op.getPrevious() instanceof Identifier // for -1 or 2 - 1
                        || op.getPrevious() instanceof Delimiter
                        || !Character.isDigit(nextChar))
                    addSpaceIfNecessary();
                break;
            default : outputToken(op);
        }
    }

    void visit(KeyWord kw) {
        outputToken(kw);
        if (kw.getType() == RETURN) {
            if (kw.getNext().getType() != SEMICOLON) addSpaceIfNecessary();
        }
    }

    void visit(Delimiter delimiter) {
        switch (delimiter.getType()) {
            case COMMA :
                outputToken(delimiter);
                if (currentLineLength() > MAX_LINE_LENGTH 
                    && (delimiter.getParent() instanceof ArrayInitializer 
                        || delimiter.getParent() instanceof EnumBody)) 
                {
                        newLine();
                }
                else buf.append(' ');
                break;
            case RBRACKET :
                outputToken(delimiter);
                TokenType nextType = delimiter.getNext().getType();
                if (nextType != LBRACKET && nextType != SEMICOLON && nextType != GT
                        && nextType != RPAREN && nextType != COMMA && nextType != DOT)
                    addSpaceIfNecessary();
                break;
            case LBRACE :
                outputToken(delimiter);
                if (!(delimiter.getParent() instanceof ArrayInitializer)) {
                    currentIndent += indent;
                    newLine();
                }
                break;
            case RBRACE :
                boolean endOfArrayInitializer = delimiter.getParent() instanceof ArrayInitializer;
                if (!endOfArrayInitializer) {
                    newLine();
                    dedent();
                }
                buf.append(delimiter);
                Token token = delimiter.getNext();
                if (!endOfArrayInitializer && null != token && token.getType() != SEMICOLON) {
                    if (token.getType()==CATCH || token.getType() == ELSE || token.getType()==FINALLY) 
                        addSpaceIfNecessary(); // space for multi block statements
                    else newLine();
                }
                break;
            case RPAREN:
                buf.append(delimiter);
                if (delimiter.getParent() instanceof CastExpression)
                    addSpaceIfNecessary();
                break;
            case SEMICOLON:
                if (buf.charAt(buf.length() - 1) != ' ') { // detect rogue semicolons
                    buf.append(delimiter);
                    if (!ForStatement.class.isInstance(delimiter.getParent())
                            && !ImportDeclaration.class.isInstance(delimiter.getParent()))
                        newLine();
                } else for (int i = 1; i <= 6; i++) // remove rogue semicolons
                    if (buf.charAt(buf.length() - 1) == ' ' || buf.charAt(buf.length() - 1) == '\n')
                        buf.setLength(buf.length() - 1);
                break;
            default : outputToken(delimiter);
        }
    }

    void visit(MultiLineComment comment) {
        startNewLineIfNecessary();
        buf.append(indentText(comment.getImage()));
        newLine();
    }

    void visit(SingleLineComment comment) {
        if (startsNewLine(comment)) {
            newLine();
        } else if (comment.getPrevious().getType() == SEMICOLON) {
            if (buf.charAt(buf.length()-1) == '\n') {
                buf.setLength(buf.length()-1);
                buf.append(' ');
            }
        }
        buf.append(comment.getImage().trim());
        newLine(); // comments may have trailing whitespace
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

    // Add a space if the last output char was not whitespace
    private void addSpaceIfNecessary() {
        if (buf.length()==0) return;
        int lastChar = buf.codePointBefore(buf.length());
        if (!Character.isWhitespace(lastChar)) buf.append(' ');
    }

    private void dedent() {
        String finalPart = buf.substring(buf.length() - indent.length(), buf.length());
        if (finalPart.equals(indent)) {
            buf.setLength(buf.length() - indent.length());
        }
        currentIndent = currentIndent.substring(0, currentIndent.length() - indent.length());
    }

    private boolean startsNewLine(Token t) {
        Token previousCachedToken = t.previousCachedToken();
        return previousCachedToken == null || previousCachedToken.getEndLine() != t.getBeginLine();
    }

    private String indentText(String text) {
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n")) {
            buf.append(currentIndent);
            buf.append(line.trim());
            buf.append("\n");
        }
        return buf.toString();
    }

    protected void visit(PackageDeclaration pd) {
        recurse(pd);
        newLine(true);
    }

    protected void visit(ImportDeclaration id) {
        recurse(id);
        buf.append(eol);
        if (!(id.nextSibling() instanceof ImportDeclaration)) {
            buf.append(eol);
        }
    }

    protected void visit(MethodDeclaration md) {
        if (!(md.previousSibling() instanceof MethodDeclaration) && !(md.previousSibling() instanceof ConstructorDeclaration)) newLine(true);
        recurse(md);
        newLine(true);
    }

    protected void visit(ConstructorDeclaration cd) {
        if (!(cd.previousSibling() instanceof MethodDeclaration) && !(cd.previousSibling() instanceof ConstructorDeclaration)) newLine(true);
        recurse(cd);
        newLine(true);
    }

    protected void visit(FieldDeclaration fd) {
        if (!(fd.previousSibling() instanceof FieldDeclaration)) {
            newLine();
        }
        recurse(fd);
        newLine();
    }

    protected void visit(LocalVariableDeclaration lvd) {
        boolean inForStatement = (lvd.getParent() instanceof ForStatement);
        if (!inForStatement) newLine();
        recurse(lvd);
        if (!inForStatement) newLine();
    }

    protected void visit(Annotation ann) {
        if (!(ann.previousSibling() instanceof Annotation)) {
            newLine();
        }
        recurse(ann);
        newLine();
    }

    void visit(ClassicCaseStatement ccs)  {
        visit(ccs.firstChildOfType(ClassicSwitchLabel.class));
        currentIndent += indent;
        newLine();
        for (Statement stmt : ccs.childrenOfType(Statement.class)) {
            visit(stmt);
        }
        dedent();
        newLine();
    }

    private void startNewLineIfNecessary() {
        if (buf.length() == 0) {
            return;
        }
        int lastNL = buf.lastIndexOf(eol);
        if (lastNL + eol.length() == buf.length()) {
            return;
        }
        String line = buf.substring(lastNL+ eol.length());
        if (line.trim().length() ==0) {
            buf.setLength(lastNL+eol.length());
        } else {
            buf.append(eol);
        }
    }

    private void newLine() {
        newLine(false);
    }

    private void newLine(boolean ensureBlankLine) {
        startNewLineIfNecessary();
        if (ensureBlankLine) {
            buf.append(eol);
        }
        buf.append(currentIndent);
    }

    private int currentLineLength() {
        return buf.length() - buf.lastIndexOf(eol) - eol.length();
    }
}
