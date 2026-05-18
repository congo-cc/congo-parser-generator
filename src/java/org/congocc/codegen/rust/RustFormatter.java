package org.congocc.codegen.rust;

import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import org.congocc.parser.rust.RustToken;
import org.congocc.parser.rust.RustToken.TokenType;
import org.congocc.parser.rust.ast.*;

import static org.congocc.parser.rust.RustToken.TokenType.*;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Pretty-printer for Rust source parsed by the internal {@code org.congocc.parser.rust}
 * grammar. Intended to produce readable generated {@code .rs} without requiring rustfmt.
 * Not a full rustfmt replacement.
 */
public class RustFormatter extends Node.Visitor {

    {this.visitUnparsedTokens = true;}

    private final StringBuilder buffer = new StringBuilder();
    private int currentIndentation;
    private final int indentAmount = 4;
    private final String eol = "\n";
    private int parenNesting;
    private int bracketNesting;
    private int braceNesting;

    private static final EnumSet<TokenType> KEYWORD_SPACE_AFTER = EnumSet.of(
            IF, ELSE, WHILE, FOR, MATCH, LET, FN, RETURN, PUB, USE, WHERE, IN, AS,
            ASYNC, UNSAFE, EXTERN, LOOP, MOVE, MUT, REF, DYN, CONST, STATIC, TRAIT,
            IMPL, ENUM, STRUCT, MOD, CRATE, SUPER, AWAIT, BREAK, CONTINUE, YIELD,
            MACRO, TYPE, TRY, DO, MACRO_RULES, UNION, RAW
    );

    private static final EnumSet<TokenType> BINARY_OPERATOR = EnumSet.of(
            EQ, NOT_EQUALS, LTE, GTE, LT, GT, PLUS, MINUS, STAR, SLASH, PERCENT,
            BIT_AND, BIT_OR, CARET, SC_AND, SC_OR, LT_LT, GT_GT, ASSIGN,
            PLUS_EQUALS, MINUS_EQUALS, STAR_EQUALS, SLASH_EQUALS, MOD_EQUALS,
            AND_EQUALS, OR_EQUALS, CARET_EQUALS, LT_LT_EQUALS, GT_GT_EQUALS,
            RIGHT_ARROW, RIGHT_ARROW2
    );

    public String format(Node node) {
        return format(node, 0);
    }

    public String format(Node node, int indentLevel) {
        buffer.setLength(0);
        currentIndentation = indentLevel * indentAmount;
        parenNesting = bracketNesting = braceNesting = 0;
        visit(node);
        return getText();
    }

    public String getText() {
        if (buffer.length() == 0 || buffer.charAt(buffer.length() - 1) != '\n') {
            buffer.append(eol);
        }
        return buffer.toString();
    }

    void visit(Whitespace ws) {
        // Normalized by explicit spacing rules.
    }

    void visit(RustToken tok) {
        if (tok.getType() == EOF) {
            return;
        }
        appendTokenImage(tok);
    }

    void visit(Comment comment) {
        if (!atLineStart()) {
            newLine();
        }
        indentLine();
        buffer.append(comment);
        newLine();
    }

    void visit(Literal literal) {
        ensureSpaceBeforeToken();
        buffer.append(literal);
    }

    void visit(Ident id) {
        ensureSpaceBeforeToken();
        buffer.append(id);
    }

    void visit(KeyWord kw) {
        ensureSpaceBeforeToken();
        buffer.append(kw);
        TokenType type = kw.getType();
        if (KEYWORD_SPACE_AFTER.contains(type)) {
            RustToken next = nextParsed(kw);
            if (next != null && next.getType() != SEMICOLON && next.getType() != RPAREN
                    && next.getType() != RBRACE && next.getType() != COMMA) {
                addSpaceIfNecessary();
            }
        }
    }

    void visit(Delimiter delim) {
        TokenType type = delim.getType();
        switch (type) {
            case LBRACE -> {
                addSpaceIfNecessary();
                buffer.append('{');
                braceNesting++;
                if (!lineJoining()) {
                    indent();
                }
            }
            case RBRACE -> {
                braceNesting = Math.max(0, braceNesting - 1);
                if (!lineJoining()) {
                    dedent();
                }
                buffer.append('}');
                handleClosingBrace(delim);
            }
            case LPAREN -> {
                trimTrailingWhitespace();
                buffer.append('(');
                parenNesting++;
            }
            case RPAREN -> {
                parenNesting = Math.max(0, parenNesting - 1);
                trimTrailingWhitespace();
                buffer.append(')');
                RustToken next = nextParsed(delim);
                if (next != null && needsSpaceAfterCloseParen(next.getType())) {
                    addSpaceIfNecessary();
                }
            }
            case LBRACKET -> {
                trimTrailingWhitespace();
                buffer.append('[');
                bracketNesting++;
            }
            case RBRACKET -> {
                bracketNesting = Math.max(0, bracketNesting - 1);
                trimTrailingWhitespace();
                buffer.append(']');
            }
            default -> appendTokenImage(delim);
        }
    }

    void visit(Punctuation punct) {
        TokenType type = punct.getType();
        Node parent = punct.getParent();

        if (type == DOT) {
            trimTrailingWhitespace();
            buffer.append('.');
            return;
        }
        if (type == DOUBLE_COLON) {
            trimTrailingWhitespace();
            buffer.append("::");
            return;
        }
        if (type == COMMA) {
            trimTrailingWhitespace();
            buffer.append(',');
            if (isInsideMatchExpression(parent)) {
                newLine();
            } else if (!lineJoining()) {
                buffer.append(' ');
            }
            return;
        }
        if (type == SEMICOLON) {
            buffer.append(';');
            if (!lineJoining()) {
                newLine();
            }
            return;
        }
        if (type == COLON) {
            buffer.append(':');
            addSpaceIfNecessary();
            return;
        }
        if (type == HASH || type == EXCLAM) {
            trimTrailingWhitespace();
            buffer.append(punct);
            return;
        }
        if (type == MINUS && parent instanceof UnaryExpression) {
            buffer.append('-');
            return;
        }
        if (type == STAR && parent instanceof UnaryExpression) {
            buffer.append('*');
            return;
        }
        if (type == BIT_AND && parent instanceof UnaryExpression) {
            buffer.append('&');
            return;
        }
        if ((type == LT || type == GT) && isGenericAngleContext(parent)) {
            trimTrailingWhitespace();
            buffer.append(punct);
            return;
        }
        if (type == RIGHT_ARROW) {
            addSpaceIfNecessary();
            buffer.append("->");
            addSpaceIfNecessary();
            return;
        }
        if (type == RIGHT_ARROW2) {
            addSpaceIfNecessary();
            buffer.append("=>");
            addSpaceIfNecessary();
            return;
        }
        if (BINARY_OPERATOR.contains(type)) {
            addSpaceIfNecessary();
            buffer.append(punct);
            addSpaceIfNecessary();
            return;
        }
        appendTokenImage(punct);
    }

    void visit(Crate crate) {
        recurse(crate);
    }

    void visit(Item item) {
        if (item.previousSibling() instanceof Item) {
            newLine(true);
        }
        recurse(item);
    }

    void visit(UseDeclaration useDecl) {
        if (!(useDecl.previousSibling() instanceof UseDeclaration)) {
            Node prev = useDecl.previousSibling();
            if (prev instanceof Item || prev instanceof UseDeclaration) {
                newLine(true);
            }
        }
        recurse(useDecl);
    }

    void visit(Function function) {
        Node prev = function.previousSibling();
        if (prev != null && !(prev instanceof OuterAttribute)) {
            newLine(true);
        }
        recurse(function);
        newLine(true);
    }

    void visit(Implementation impl) {
        newLine(true);
        recurse(impl);
        newLine(true);
    }

    void visit(TraitImpl traitImpl) {
        newLine(true);
        recurse(traitImpl);
        newLine(true);
    }

    void visit(InherentImpl inherentImpl) {
        newLine(true);
        recurse(inherentImpl);
        newLine(true);
    }

    void visit(OuterAttribute attr) {
        if (!atLineStart()) {
            newLine();
        }
        recurse(attr);
        newLine();
    }

    void visit(InnerAttribute attr) {
        recurse(attr);
        newLine();
    }

    void visit(BlockExpression block) {
        recurse(block);
    }

    void visit(MatchExpression matchExpr) {
        recurse(matchExpr);
    }

    void visit(MatchArm arm) {
        if (!atLineStart()) {
            newLine();
        }
        recurse(arm);
    }

    void visit(IfExpression ifExpr) {
        recurse(ifExpr);
    }

    void visit(LetStatement letStmt) {
        recurse(letStmt);
    }

    private void handleClosingBrace(Delimiter delim) {
        Node parent = delim.getParent();
        Node grandparent = parent != null ? parent.getParent() : null;
        RustToken next = nextParsed(delim);

        if (grandparent instanceof IfExpression && next != null && next.getType() == ELSE) {
            addSpaceIfNecessary();
            return;
        }
        if (next != null && next.getType() == ELSE) {
            addSpaceIfNecessary();
            return;
        }
        if (grandparent instanceof MatchExpression
                || parent instanceof BlockExpression
                || parent instanceof Function
                || parent instanceof Implementation
                || parent instanceof TraitImpl
                || parent instanceof InherentImpl
                || parent instanceof MatchExpression) {
            newLine();
        }
    }

    private boolean isInsideMatchExpression(Node node) {
        while (node != null) {
            if (node instanceof MatchExpression) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private boolean isGenericAngleContext(Node parent) {
        while (parent != null) {
            if (parent instanceof GenericParams
                    || parent instanceof GenericArgs
                    || parent instanceof UseBoundGenericArgs) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private boolean needsSpaceAfterCloseParen(TokenType nextType) {
        return switch (nextType) {
            case LBRACE, IF, WHILE, FOR, MATCH, LET, RETURN, ID, SELF, SELF2,
                    TRUE, FALSE, INTEGER_LITERAL, FLOATING_POINT_LITERAL, STRING_LITERAL,
                    RAW_IDENTIFIER, LIFETIME_OR_LABEL, RIGHT_ARROW -> true;
            default -> false;
        };
    }

    private boolean lineJoining() {
        return parenNesting > 0 || bracketNesting > 0;
    }

    private static RustToken nextParsed(RustToken tok) {
        RustToken t = tok.nextCachedToken();
        while (t != null && t.isUnparsed()) {
            t = t.nextCachedToken();
        }
        return t;
    }

    private void appendTokenImage(RustToken tok) {
        ensureSpaceBeforeToken();
        buffer.append(tok);
    }

    private void ensureSpaceBeforeToken() {
        if (buffer.length() == 0) {
            return;
        }
        int preceding = buffer.codePointBefore(buffer.length());
        if (Character.isLetterOrDigit(preceding) || preceding == '}' || preceding == ')') {
            addSpaceIfNecessary();
        }
    }

    private void addSpaceIfNecessary() {
        if (buffer.length() == 0) {
            return;
        }
        int lastChar = buffer.codePointBefore(buffer.length());
        if (!Character.isWhitespace(lastChar)) {
            buffer.append(' ');
        }
    }

    private void indent() {
        currentIndentation += indentAmount;
        newLine();
    }

    private void dedent() {
        currentIndentation = Math.max(0, currentIndentation - indentAmount);
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
        indentLine();
    }

    private void indentLine() {
        buffer.append(" ".repeat(currentIndentation));
    }

    private void trimTrailingWhitespace() {
        while (buffer.length() > 0) {
            int lastChar = buffer.codePointBefore(buffer.length());
            if (!Character.isWhitespace(lastChar)) {
                break;
            }
            buffer.setLength(buffer.length() - 1);
            if (lastChar > 0xFFFF) {
                buffer.setLength(buffer.length() - 1);
            }
        }
    }

    private boolean atLineStart() {
        for (int pos = buffer.length() - 1; pos >= 0; pos--) {
            char ch = buffer.charAt(pos);
            if (ch == '\n') {
                return true;
            }
            if (!Character.isWhitespace(ch)) {
                return false;
            }
        }
        return true;
    }

    static void formatFile(String filename) throws IOException {
        Path path = FileSystems.getDefault().getPath(filename);
        Crate root = CongoCCParser.parseRustFile(path);
        RustFormatter formatter = new RustFormatter();
        formatter.visit(root);
        System.out.print(formatter.getText());
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java org.congocc.codegen.rust.RustFormatter <filename>");
            return;
        }
        formatFile(args[0]);
    }
}
