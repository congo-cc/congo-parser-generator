package org.congocc.codegen.rust;

import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import org.congocc.parser.rust.ast.*;
import org.congocc.parser.rust.RustToken;
import org.congocc.parser.rust.RustToken.TokenType;
import static org.congocc.parser.rust.RustToken.TokenType.*;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Formats Rust source code per the official Rust Style Guide
 * (https://doc.rust-lang.org/style-guide/). Drop-in replacement for the
 * earlier token-only formatter that only handled '{', '}' and ';'.
 *
 * <p>This implementation walks the parsed AST as a token stream and decides,
 * for each token, whether it should be preceded by a space, a newline (with
 * indentation), or nothing. Decisions are made from:
 * <ul>
 *   <li>the token's own type,</li>
 *   <li>the type of the previously emitted token,</li>
 *   <li>for ambiguous tokens ({@code &}, {@code *}, {@code -}, {@code <},
 *       {@code >}, {@code |}), the AST parent class which disambiguates
 *       unary vs binary, generics vs comparison, and closure-param vs
 *       bit-or.</li>
 * </ul>
 *
 * <p>Layout rules enforced:
 * <ul>
 *   <li>4-space indentation.</li>
 *   <li>K&amp;R brace style: opening brace stays on the parent line; the
 *       block body is on its own indented lines; the closing brace is on a
 *       line of its own at the parent indent. Empty blocks render as
 *       {@code {}}.</li>
 *   <li>{@code }} followed by {@code else}, {@code ;}, {@code ,}, {@code )},
 *       {@code ]}, {@code .}, {@code ?} stays on the same line.</li>
 *   <li>Spaces around binary operators; no space around {@code ::},
 *       {@code .}, {@code ..}, {@code ..=}.</li>
 *   <li>Space after {@code ,}, {@code :} (in type/field annotations) and
 *       {@code ;}; no space before them.</li>
 *   <li>No space between an identifier (or {@code )}, {@code ]}, {@code ?},
 *       turbofish {@code >}) and a following {@code (} or {@code [}, so that
 *       function calls and indexing render contiguously.</li>
 *   <li>Comments preserved: a line comment ({@code //}) keeps the same
 *       trailing-vs-own-line position it had in the source (decided by
 *       whether the source had a newline between the previous token and
 *       the comment).</li>
 *   <li>Blank lines from the source (runs of two or more newlines) are
 *       preserved as a single blank line between items.</li>
 * </ul>
 *
 * <p>The class preserves the original public interface
 * ({@link #main(String[])} and {@link #formatFile(String)}) so it is a
 * drop-in replacement.
 */
public class RustFormatter extends Node.Visitor {
    { this.visitUnparsedTokens = true; }

    // ----- Output state ---------------------------------------------------
    private final StringBuilder buffer = new StringBuilder();
    private int currentIndentation;
    private final int indentAmount = 4;
    private final String eol = "\n";

    /** Type of the last real (non-whitespace) token emitted, or null at start. */
    private TokenType prev;
    /** Last real token emitted; used to peek at its AST parent. */
    private RustToken prevTok;
    /** True when the write cursor is at the start of a (possibly indented) line. */
    private boolean atLineStart = true;
    /** Number of blank lines to insert before the next non-whitespace emission. */
    private int pendingBlankLines;
    /** A newline has been requested before the next non-whitespace token. */
    private boolean wantNewlineNext;
    /** Source-driven: at least one newline appeared in whitespace since last code token. */
    private boolean sawSourceNewline;
    /** True immediately after a '{' is emitted: an empty block stays inline. */
    private boolean justOpenedBrace;
    /**
     * Tracks closure-bar parity: true between the opening {@code |} and the
     * closing {@code |} of a closure parameter list. Used to give
     * "no space after opening |", "no space before closing |", and
     * "space after closing |" the right behavior without lookahead.
     */
    private boolean inClosureBars;

    // ----- Token classification helpers ----------------------------------

    /**
     * Tokens that count as "identifier-like" suffixes. When one of these is
     * followed by '(' or '[', the next token must abut (function call,
     * indexing, or generic argument list closing).
     */
    private static final Set<TokenType> IDENT_LIKE = EnumSet.of(
        ID, RAW_IDENTIFIER, SELF, SELF2, SUPER, CRATE, DOLLAR_CRATE,
        RPAREN, RBRACKET, HOOK, GT,
        // Literal tokens are atomic for the purposes of "next token abuts":
        // e.g., `42.method()` has no space, even though dot-after-literal is
        // unusual.
        INTEGER_LITERAL, FLOATING_POINT_LITERAL, CHAR_LITERAL,
        STRING_LITERAL, BYTE_LITERAL, BYTE_STRING_LITERAL,
        C_STRING_LITERAL, RAW_STRING_LITERAL, RAW_STRING_LITERAL_HASH
    );

    /**
     * Tokens that look like punctuation closers and must not be preceded by
     * a space, even when the producing rules above would otherwise add one.
     */
    private static final Set<TokenType> NO_SPACE_BEFORE = EnumSet.of(
        COMMA, SEMICOLON, RPAREN, RBRACKET, HOOK,
        DOT, DOT_DOT, DOT_DOT_EQUALS, ELLIPSIS,
        DOUBLE_COLON, COLON
    );

    /**
     * Tokens after which no space should ever be inserted (punctuation
     * openers, dotted-path joins, attribute hashes, unary-like prefixes).
     */
    private static final Set<TokenType> NO_SPACE_AFTER = EnumSet.of(
        LPAREN, LBRACKET,
        DOT, DOT_DOT, DOT_DOT_EQUALS, ELLIPSIS,
        DOUBLE_COLON,
        HASH, DOLLAR, AT,
        // '!' is special: macro indicator and unary not both want no space
        // after - handled here as a single rule.
        EXCLAM
    );

    /**
     * AST parent-class names that mark a {@code &}, {@code *}, {@code -}, or
     * {@code !} token as unary (i.e., a prefix operator with no space after).
     */
    private static final Set<String> UNARY_PARENT_NAMES = Set.of(
        "UnaryExpression", "ReferenceType", "RawPointerType",
        "ReferencePattern", "ShorthandSelf"
    );

    /**
     * AST parent-class names that mark a {@code <} or {@code >} token as a
     * generic-argument or generic-parameter delimiter rather than a
     * comparison operator.
     */
    private static final Set<String> GENERIC_PARENT_NAMES = Set.of(
        "GenericArgs", "GenericParams", "QualifiedPathType",
        "GenericArgsBinding", "GenericArgsBounds", "TypePathFn",
        "TraitBound"
    );

    /**
     * AST parent-class names that mark a {@code |} token as a closure
     * parameter delimiter rather than a bit-or operator.
     */
    private static final Set<String> CLOSURE_PARENT_NAMES = Set.of(
        "ClosureExpression", "ClosureParameters"
    );

    // ---------------------------------------------------------------------
    // Visitor entry points

    /**
     * Visits a token. Unparsed (whitespace, comment) and parsed tokens all
     * arrive here because we set {@code visitUnparsedTokens=true}. Output
     * spacing is decided here for every emission.
     */
    void visit(RustToken tok) {
        TokenType t = tok.getType();
        String text = tok.toString();

        // EOF carries no text; nothing to emit.
        if (t == EOF) return;

        // Source whitespace (the SKIP rule) is discarded by the lexer and
        // never reaches the visitor as a real token. Instead, before we
        // emit each token, we inspect the gap between the previous token
        // and this one (via offsets in the TokenSource) to recover
        // newline information.
        if (t == WHITESPACE) {
            // Defensive: in case the lexer ever delivers a WHITESPACE
            // token (it doesn't today), treat it like the gap check.
            int newlines = countNewlines(text);
            if (newlines >= 1) sawSourceNewline = true;
            if (newlines >= 2 && prev != null) {
                pendingBlankLines = Math.max(pendingBlankLines, 1);
            }
            return;
        }

        // Recover source-newline info from the gap before this token.
        updateSourceNewlineFromGap(tok);

        if (t == LINE_COMMENT) {
            emitLineComment(text);
            return;
        }
        if (t == MULTILINE_COMMMENT) {
            emitBlockComment(text);
            return;
        }

        emitToken(tok, t, text);
    }

    /**
     * Inspect the source text between the end of {@link #prevTok} and the
     * beginning of {@code tok}. If it contains newlines, update the
     * {@code sawSourceNewline} flag and the {@code pendingBlankLines}
     * counter accordingly. This is how we recover layout intent from the
     * input despite WHITESPACE being a SKIP token that the lexer
     * discards.
     */
    private void updateSourceNewlineFromGap(RustToken tok) {
        if (prevTok == null && prev == null) return;
        // We may have just emitted a comment, in which case prevTok is the
        // last code token but the gap to the current token is *not* what
        // we want (the comment's own offset is more recent). For
        // simplicity, when prev is a comment we read the gap from the
        // most-recent comment's end. We don't track that explicitly, so
        // we just fall back to a "saw a newline" assumption for comments
        // - they end the line they're on.
        if (prev == LINE_COMMENT) {
            sawSourceNewline = true;
            return;
        }
        if (prevTok == null) return;
        try {
            int gapStart = prevTok.getEndOffset();
            int gapEnd = tok.getBeginOffset();
            if (gapEnd <= gapStart) return;
            CharSequence src = tok.getTokenSource();
            if (src == null) return;
            int newlines = 0;
            for (int i = gapStart; i < gapEnd; i++) {
                if (src.charAt(i) == '\n') newlines++;
            }
            if (newlines >= 1) sawSourceNewline = true;
            if (newlines >= 2) {
                pendingBlankLines = Math.max(pendingBlankLines, 1);
            }
        } catch (Exception ignore) {
            // If offsets are wrong or source isn't available, fall back
            // silently - we just won't get blank-line preservation here.
        }
    }

    // ---------------------------------------------------------------------
    // Emission helpers

    /**
     * Emit a real (non-comment, non-whitespace) token, applying the spacing
     * and indentation rules.
     */
    private void emitToken(RustToken tok, TokenType t, String text) {
        boolean isClose = (t == RBRACE);
        boolean inlineBrace = (t == LBRACE || t == RBRACE) && isInlineBrace(tok);

        // Dedent before a closing brace so the brace itself lines up with
        // the enclosing block - but only for "real" blocks. Inline braces
        // (such as a use tree's '{...}') never changed the indentation.
        if (isClose && !inlineBrace) {
            currentIndentation = Math.max(0, currentIndentation - indentAmount);
        }

        // Empty-block fast path: '{' immediately followed by '}' stays on
        // one line with no inner newline or indent change.
        if (isClose && justOpenedBrace) {
            justOpenedBrace = false;
            wantNewlineNext = false;
            pendingBlankLines = 0;
            sawSourceNewline = false;
            buffer.append(text);
            atLineStart = false;
            prev = t;
            prevTok = tok;
            return;
        }
        justOpenedBrace = false;

        // A '}' followed by certain trailing punctuation (e.g., ';' to end
        // a struct literal, ',' inside an argument list, or 'else') stays
        // on the same line. The decision lives in needsNewlineBefore().
        boolean newlineBefore = needsNewlineBefore(t, tok);
        boolean spaceBefore = !newlineBefore && needsSpaceBefore(t, tok);

        if (newlineBefore) {
            newLine();
        } else if (spaceBefore && !atLineStart) {
            buffer.append(' ');
        }

        // Pending blank lines only take effect when at line start, so we
        // don't accidentally tear apart an expression.
        if (atLineStart && pendingBlankLines > 0) {
            for (int i = 0; i < pendingBlankLines; i++) buffer.append(eol);
            indentLine();
            pendingBlankLines = 0;
        }

        buffer.append(text);
        atLineStart = false;
        wantNewlineNext = false;
        sawSourceNewline = false;

        // Update post-emission state.
        if (t == LBRACE) {
            if (inlineBrace) {
                // Inline brace (e.g., a use tree {Foo, Bar}): no indent
                // change and no forced newline. The next token sits
                // beside the '{'.
                justOpenedBrace = false;
            } else {
                currentIndentation += indentAmount;
                wantNewlineNext = true;
                justOpenedBrace = true;
            }
        } else if (t == SEMICOLON) {
            wantNewlineNext = true;
        } else if (t == RBRACE) {
            // After a closing brace, the next item generally starts on its
            // own line. Whether that happens is decided by the next
            // token's needsNewlineBefore() check (which is overridden for
            // certain stitching tokens).
            wantNewlineNext = true;
        } else if (t == BIT_OR && isClosureBar(tok)) {
            // Toggle in/out of a closure's parameter bars. The
            // needsSpaceBefore() rule for BIT_OR consults this state so
            // that we get "no space after opening |" but "space after
            // closing |".
            inClosureBars = !inClosureBars;
        } else if (t == RBRACKET && isAttributeClose(tok)) {
            // After the ']' that closes an outer/inner attribute, force a
            // newline so the attribute sits on its own line above the
            // item it decorates - e.g.,
            //   #[derive(Clone)]
            //   struct Foo { ... }
            wantNewlineNext = true;
        } else if (t == COMMA && isItemListComma(tok)) {
            // Commas separating "definition-list" items (struct fields,
            // enum variants, tuple-struct fields) always introduce a new
            // line so each item gets its own. This produces the Rust
            // Style Guide's preferred layout for type definitions even
            // when the source happened to fit on one line.
            wantNewlineNext = true;
        }

        prev = t;
        prevTok = tok;
    }

    /**
     * Decide whether a newline should precede the current token.
     */
    private boolean needsNewlineBefore(TokenType cur, RustToken tok) {
        if (prev == null) return false; // very first token
        if (atLineStart) return false;  // already on a fresh line

        // A closing brace usually goes on its own line - except for an
        // inline brace (e.g., use-tree '{...}') which is kept on a
        // single line. The empty-block case is handled before this
        // method is called.
        if (cur == RBRACE) return !isInlineBrace(tok);

        // Tokens that "stitch" onto a prior '}' (keep them on the same line):
        // ';', ',', ')', ']', '.', '?', and 'else'.
        if (prev == RBRACE) {
            if (cur == SEMICOLON || cur == COMMA || cur == RPAREN
                    || cur == RBRACKET || cur == DOT || cur == HOOK
                    || cur == DOT_DOT || cur == DOT_DOT_EQUALS
                    || cur == ELSE) {
                return false;
            }
            return true;
        }

        return wantNewlineNext;
    }

    /**
     * Decide whether a single space should precede the current token. This
     * runs AFTER {@link #needsNewlineBefore(TokenType, RustToken)}; if a
     * newline is emitted, leading indentation replaces any space.
     */
    private boolean needsSpaceBefore(TokenType cur, RustToken tok) {
        if (prev == null) return false;
        if (atLineStart) return false;

        // Hard rules: no space before these tokens, regardless of what came
        // before.
        if (NO_SPACE_BEFORE.contains(cur)) {
            return false;
        }

        // '<' or '>' acting as a generic delimiter: no surrounding space.
        if (cur == LT && isGenericDelimiter(tok)) return false;
        if (cur == GT && isGenericDelimiter(tok)) return false;

        // '|' acting as a closure parameter delimiter: no space *before*
        // a closing '|' (we're still inside the bars) but normal rules
        // apply for the opening '|' itself.
        if (cur == BIT_OR && isClosureBar(tok) && inClosureBars) return false;

        // Hard rules: no space after these tokens, regardless of what comes
        // next.
        if (NO_SPACE_AFTER.contains(prev)) return false;

        // No space directly inside an inline brace (use trees): the
        // opening '{' abuts its first item, the closing '}' abuts the
        // last.
        if (prev == LBRACE && isInlineBrace(prevTok)) return false;
        if (cur == RBRACE && isInlineBrace(tok)) return false;

        // Closure bar previously emitted: behavior depends on whether
        // we're inside the bars (just emitted opening '|', body's first
        // token follows: no space) or outside (just emitted closing '|',
        // closure body follows: space).
        if (prev == BIT_OR && isClosureBar(prevTok)) {
            return !inClosureBars;
        }

        // Lifetime apostrophe-style tokens are atomic ('a, 'static, '_).
        // The lexer never emits a stray ' so no rule needed here.

        // Ambiguous '&', '*', '-', '&&': unary if their AST parent says so.
        // No space after a unary prefix operator.
        if (prev == BIT_AND || prev == STAR || prev == MINUS || prev == SC_AND) {
            if (isUnaryUse(prevTok)) return false;
        }

        // '<' previously emitted as a generic opener: no space after.
        if (prev == LT && isGenericDelimiter(prevTok)) return false;

        // '!' after an identifier is a macro indicator (foo!): no space.
        if (cur == EXCLAM && (prev == ID || prev == RAW_IDENTIFIER)) return false;

        // '(' or '[' immediately after identifier-like: function call /
        // indexing / tuple-struct construction - no space.
        if ((cur == LPAREN || cur == LBRACKET) && IDENT_LIKE.contains(prev)) {
            return false;
        }

        // '<' opening a generic after an identifier-like or '::': no space.
        if (cur == LT && isGenericDelimiter(tok)) {
            if (IDENT_LIKE.contains(prev) || prev == DOUBLE_COLON) return false;
        }

        // '<' after a turbofish '::' (e.g., foo::<T>()): no space (already
        // covered by NO_SPACE_AFTER for DOUBLE_COLON, but explicit here).
        if (cur == LT && prev == DOUBLE_COLON) return false;

        // After '}' we want a single space before continuation tokens that
        // stayed on the same line via the needsNewlineBefore() exception
        // (e.g., 'else'). The punctuation cases were already filtered out
        // by the NO_SPACE_BEFORE set above.
        if (prev == RBRACE) {
            return true;
        }

        // Default: insert a space between any two tokens.
        return true;
    }

    /**
     * Test whether a {@code &}, {@code *}, {@code -}, or {@code &&} token
     * acts as a unary prefix operator. We check the AST parent's class
     * name, which is set by the grammar.
     */
    private boolean isUnaryUse(RustToken tok) {
        if (tok == null) return false;
        Node parent = tok.getParent();
        if (parent == null) return false;
        return UNARY_PARENT_NAMES.contains(parent.getClass().getSimpleName());
    }

    /**
     * Test whether a {@code <} or {@code >} token is a generic-argument or
     * generic-parameter delimiter (rather than a comparison operator).
     */
    private boolean isGenericDelimiter(RustToken tok) {
        if (tok == null) return false;
        Node parent = tok.getParent();
        if (parent == null) return false;
        return GENERIC_PARENT_NAMES.contains(parent.getClass().getSimpleName());
    }

    /**
     * Test whether a {@code |} token is a closure parameter delimiter
     * (rather than a bit-or operator).
     */
    private boolean isClosureBar(RustToken tok) {
        if (tok == null) return false;
        Node parent = tok.getParent();
        if (parent == null) return false;
        return CLOSURE_PARENT_NAMES.contains(parent.getClass().getSimpleName());
    }

    /**
     * Test whether a brace token belongs to a construct that should be
     * formatted inline (no newline / indent change on '{' and no newline
     * before '}'). Currently this is just use-tree groups, which the
     * Rust Style Guide says should stay on a single line whenever they
     * fit.
     */
    private boolean isInlineBrace(RustToken tok) {
        if (tok == null) return false;
        Node parent = tok.getParent();
        if (parent == null) return false;
        return "UseTree".equals(parent.getClass().getSimpleName());
    }

    /**
     * Test whether a {@code ,} token separates items in a type-definition
     * list (struct/union fields, enum variants, tuple-struct fields).
     * Such items render one per line even when the original source put
     * them on a single line - the Rust Style Guide's recommendation.
     */
    private boolean isItemListComma(RustToken tok) {
        if (tok == null) return false;
        Node parent = tok.getParent();
        if (parent == null) return false;
        String name = parent.getClass().getSimpleName();
        return "StructFields".equals(name) || "EnumItems".equals(name)
                || "TupleFields".equals(name);
    }

    /**
     * Test whether a {@code ]} token closes an outer or inner attribute
     * (e.g., the {@code ]} in {@code #[derive(Clone)]}).
     */
    private boolean isAttributeClose(RustToken tok) {
        if (tok == null) return false;
        Node parent = tok.getParent();
        if (parent == null) return false;
        String name = parent.getClass().getSimpleName();
        return "OuterAttribute".equals(name) || "InnerAttribute".equals(name);
    }

    // ---------------------------------------------------------------------
    // Comment handling

    /**
     * Emit a line comment ({@code //...}). A line comment that the source
     * placed on its own line keeps that placement; one that trailed code
     * on the same line keeps that placement too.
     */
    private void emitLineComment(String text) {
        // The lexer typically excludes the terminating newline, but be
        // defensive.
        String stripped = text.replaceAll("\\s+$", "");

        // Decide own-line vs trailing position.
        boolean ownLine = sawSourceNewline || wantNewlineNext
                          || prev == RBRACE || prev == LINE_COMMENT
                          || atLineStart;
        if (!atLineStart && ownLine) {
            newLine();
        } else if (!atLineStart) {
            // Trailing comment: separate by a single space.
            if (!endsWithSpace()) buffer.append(' ');
        }

        if (atLineStart && pendingBlankLines > 0) {
            for (int i = 0; i < pendingBlankLines; i++) buffer.append(eol);
            indentLine();
            pendingBlankLines = 0;
        }

        buffer.append(stripped);
        newLine();
        wantNewlineNext = false;
        sawSourceNewline = false;
        prev = LINE_COMMENT;
        // prevTok intentionally NOT updated: spacing decisions about
        // ambiguous operators look at the *last code* token, not the
        // comment.
    }

    /**
     * Emit a block (/* ... *\/) comment. Multi-line comments preserve their
     * internal layout but are re-indented so each line aligns with the
     * surrounding code.
     */
    private void emitBlockComment(String text) {
        boolean multiLine = text.contains("\n");
        if (multiLine) {
            // Always put a multi-line block comment on its own line.
            if (!atLineStart) newLine();
            if (pendingBlankLines > 0) {
                for (int i = 0; i < pendingBlankLines; i++) buffer.append(eol);
                indentLine();
                pendingBlankLines = 0;
            }
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (i == 0) {
                    buffer.append(line);
                } else {
                    newLine();
                    // Preserve the comment's relative indentation by
                    // trimming the source's leading whitespace and
                    // re-indenting from currentIndentation.
                    String trimmedLeft = line.replaceFirst("^\\s*", "");
                    if (!trimmedLeft.isEmpty()) buffer.append(trimmedLeft);
                }
            }
            newLine();
            wantNewlineNext = false;
            sawSourceNewline = false;
            prev = MULTILINE_COMMMENT;
        } else {
            // Inline /* ... */: surround with a space if not at line start.
            boolean ownLine = sawSourceNewline || wantNewlineNext;
            if (!atLineStart && ownLine) {
                newLine();
            } else if (!atLineStart && !endsWithSpace()) {
                buffer.append(' ');
            }
            buffer.append(text);
            atLineStart = false;
            sawSourceNewline = false;
            prev = MULTILINE_COMMMENT;
        }
    }

    // ---------------------------------------------------------------------
    // Low-level output primitives

    private void newLine() {
        // Trim trailing spaces on the line we are about to leave so we
        // don't emit dangling whitespace.
        int len = buffer.length();
        int end = len;
        while (end > 0) {
            char c = buffer.charAt(end - 1);
            if (c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        if (end != len) buffer.setLength(end);
        buffer.append(eol);
        indentLine();
        atLineStart = true;
    }

    private void indentLine() {
        for (int i = 0; i < currentIndentation; i++) buffer.append(' ');
    }

    private boolean endsWithSpace() {
        int n = buffer.length();
        if (n == 0) return true;
        char c = buffer.charAt(n - 1);
        return c == ' ' || c == '\t' || c == '\n';
    }

    private int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    // ---------------------------------------------------------------------
    // Line wrapping (post-processing)

    /**
     * Recommended maximum line width per the Rust Style Guide. Lines that
     * exceed this width are post-processed to break at the outermost
     * bracketed group containing commas.
     */
    private static final int MAX_LINE_WIDTH = 100;

    /**
     * Indentation used when breaking a long line. Matches {@link #indentAmount}.
     */
    private static final int WRAP_INDENT = 4;

    /**
     * Maximum number of recursive wrap passes per line. Prevents pathological
     * inputs from running away.
     */
    private static final int WRAP_RECURSION_LIMIT = 8;

    /**
     * Reformat the buffer's contents so that any line longer than
     * {@link #MAX_LINE_WIDTH} is broken at the outermost bracketed group
     * (with top-level commas) on that line. The output is multi-line, with
     * the inner items at {@code outer-indent + 4} spaces, the trailing
     * close bracket back at {@code outer-indent}.
     *
     * <p>Strings and char literals are tracked so that brackets and commas
     * inside them are NOT considered "top level". Line comments
     * ({@code //...}) and block comments ({@code /*...*\/}) are similarly
     * skipped over so their contents aren't accidentally treated as
     * structural tokens.
     */
    private String wrapLongLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i <= text.length()) {
            int lineEnd = text.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(i, lineEnd);
            out.append(wrapLine(line, WRAP_RECURSION_LIMIT));
            if (lineEnd < text.length()) out.append('\n');
            i = lineEnd + 1;
            if (lineEnd == text.length()) break;
        }
        return out.toString();
    }

    /**
     * Wrap a single line if needed, recursively wrapping the result. Returns
     * the original line if it fits or no suitable break point is found.
     */
    private String wrapLine(String line, int depth) {
        if (depth <= 0 || line.length() <= MAX_LINE_WIDTH) return line;

        // Leading indentation.
        int lead = 0;
        while (lead < line.length() && line.charAt(lead) == ' ') lead++;
        String leadStr = line.substring(0, lead);

        // Find the outermost wrappable bracket group: the first '{', '[' or
        // '(' whose matching close is later on this line and that contains
        // at least one top-level comma. Skip brackets inside string/char
        // literals or comments.
        int openPos = findOuterOpenBracket(line, lead);
        if (openPos < 0) {
            // No openable bracket on this line. This commonly happens to a
            // "continuation" line of an already-broken multi-line group,
            // e.g. the field list inside a struct literal where '{' is on
            // the previous line. If the line has top-level commas, break
            // at each so every item appears on its own line.
            return wrapAtTopLevelCommas(line, leadStr, depth);
        }

        char open = line.charAt(openPos);
        char close;
        switch (open) {
            case '[': close = ']'; break;
            case '(': close = ')'; break;
            case '{': close = '}'; break;
            default: return line;
        }
        int closePos = findMatchingClose(line, openPos, open, close);
        if (closePos < 0) return line;

        // Split inner content by top-level commas.
        java.util.List<Integer> commaPositions = findTopLevelCommas(line, openPos + 1, closePos);
        if (commaPositions.isEmpty()) return line;

        String innerIndent = leadStr;
        for (int k = 0; k < WRAP_INDENT; k++) innerIndent += ' ';

        // The bit before the open bracket (inclusive of the bracket).
        String prefix = line.substring(0, openPos + 1);
        // The bit after the close bracket (inclusive of it).
        String suffix = line.substring(closePos);

        // Build the broken-out form. Each item on its own line, each with
        // a trailing comma (per the Rust Style Guide's rule for items
        // formatted across multiple lines).
        StringBuilder b = new StringBuilder();
        b.append(prefix).append('\n');
        int itemStart = openPos + 1;
        for (int commaIdx = 0; commaIdx <= commaPositions.size(); commaIdx++) {
            int itemEnd = commaIdx < commaPositions.size() ? commaPositions.get(commaIdx) : closePos;
            String item = line.substring(itemStart, itemEnd).trim();
            if (!item.isEmpty()) {
                b.append(innerIndent).append(item).append(',').append('\n');
            }
            itemStart = itemEnd + 1;
        }
        b.append(leadStr).append(suffix);

        // Recurse over the result to break any remaining long lines.
        String wrapped = b.toString();
        StringBuilder out = new StringBuilder();
        int p = 0;
        while (p <= wrapped.length()) {
            int e = wrapped.indexOf('\n', p);
            if (e < 0) e = wrapped.length();
            out.append(wrapLine(wrapped.substring(p, e), depth - 1));
            if (e < wrapped.length()) out.append('\n');
            p = e + 1;
            if (e == wrapped.length()) break;
        }
        return out.toString();
    }

    /**
     * Break {@code line} at every top-level comma. Each segment after the
     * first is re-indented to {@code leadStr} (the original line's indent).
     * Used for continuation lines that already sit inside an open
     * bracket whose opener is on a previous line.
     */
    private String wrapAtTopLevelCommas(String line, String leadStr, int depth) {
        java.util.List<Integer> commas = findTopLevelCommas(line, leadStr.length(), line.length());
        if (commas.isEmpty()) return line;

        StringBuilder b = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= commas.size(); i++) {
            int end = i < commas.size() ? commas.get(i) + 1 : line.length();
            String segment = line.substring(start, end);
            if (i == 0) {
                b.append(segment);
            } else {
                // Trim leading whitespace from continuation segments and
                // re-indent to the original line's indent. Skip segments
                // that are pure whitespace - these typically arise from a
                // trailing comma whose post-comma "item" is empty.
                int trim = 0;
                while (trim < segment.length() && segment.charAt(trim) == ' ') trim++;
                String trimmed = segment.substring(trim);
                if (trimmed.isEmpty()) continue;
                b.append('\n').append(leadStr).append(trimmed);
            }
            start = end;
        }
        // If the original line did not have a trailing comma, the final
        // segment (e.g., "last_item") was appended without one. The Rust
        // Style Guide asks for a trailing comma on multi-line lists, so
        // add one when the result's final non-blank line does not yet
        // end in a comma. This is a no-op when there's already a
        // trailing comma in the source.
        appendTrailingCommaIfMissing(b);
        String wrapped = b.toString();
        // Recurse over the result.
        String[] lines = wrapped.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(wrapLine(lines[i], depth - 1));
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /**
     * Helper for the comma-fallback wrap. Inspects the StringBuilder's
     * final non-empty line and, if it doesn't already end in a comma,
     * appends one. Used to add a trailing comma to the last item of a
     * multi-line list that didn't have one in the source.
     */
    private void appendTrailingCommaIfMissing(StringBuilder b) {
        int n = b.length();
        // Walk back over trailing whitespace.
        int i = n - 1;
        while (i >= 0 && (b.charAt(i) == ' ' || b.charAt(i) == '\t')) i--;
        if (i < 0) return;
        // If the final non-whitespace character is already a comma, or
        // is a bracket that doesn't take a trailing comma, leave the
        // builder alone.
        char c = b.charAt(i);
        if (c == ',' || c == '{' || c == '[' || c == '(' || c == '\n') return;
        // Insert a ',' right after the last non-whitespace character.
        b.insert(i + 1, ',');
    }

    /**
     * Find the position of the outermost (top-level) opening bracket on the
     * line that has a matching close on the same line AND contains at
     * least one top-level comma. Returns -1 if none.
     *
     * <p>"Outermost" here means the first such bracket at depth 0 (counting
     * from the start of the line, ignoring contents of strings/comments).
     */
    private int findOuterOpenBracket(String line, int from) {
        int n = line.length();
        for (int i = from; i < n; i++) {
            char c = line.charAt(i);
            // Skip strings and char literals.
            if (c == '"' || c == '\'') {
                int end = skipStringLiteral(line, i);
                if (end < 0) return -1; // Unterminated string; can't safely wrap.
                i = end;
                continue;
            }
            // Skip line comments and block comments.
            if (c == '/' && i + 1 < n) {
                char d = line.charAt(i + 1);
                if (d == '/') return -1; // No structure after a line comment.
                if (d == '*') {
                    int end = line.indexOf("*/", i + 2);
                    if (end < 0) return -1;
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[' || c == '(' || c == '{') {
                char close;
                switch (c) {
                    case '[': close = ']'; break;
                    case '(': close = ')'; break;
                    default: close = '}'; break;
                }
                int matchEnd = findMatchingClose(line, i, c, close);
                if (matchEnd > 0 && !findTopLevelCommas(line, i + 1, matchEnd).isEmpty()) {
                    return i;
                }
                // Otherwise: this bracket doesn't help. Continue searching
                // *after* its close (if found) so we don't return a
                // nested one.
                if (matchEnd > 0) {
                    i = matchEnd;
                }
            }
        }
        return -1;
    }

    /**
     * Find the position of the bracket that closes the bracket at
     * {@code openPos} on the same line. Returns -1 if not found on this
     * line. Skips strings, char literals, and comments.
     */
    private int findMatchingClose(String line, int openPos, char open, char close) {
        int depth = 0;
        int n = line.length();
        for (int i = openPos; i < n; i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipStringLiteral(line, i);
                if (end < 0) return -1;
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char d = line.charAt(i + 1);
                if (d == '/') return -1;
                if (d == '*') {
                    int end = line.indexOf("*/", i + 2);
                    if (end < 0) return -1;
                    i = end + 1;
                    continue;
                }
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            } else if (c == '(' || c == '[' || c == '{') {
                // Nested different bracket: track only the matching kind by
                // recursing. To keep this simple, just walk through the
                // nested group.
                char inOpen = c;
                char inClose;
                switch (inOpen) {
                    case '[': inClose = ']'; break;
                    case '(': inClose = ')'; break;
                    default: inClose = '}'; break;
                }
                int nestedEnd = findMatchingClose(line, i, inOpen, inClose);
                if (nestedEnd < 0) return -1;
                i = nestedEnd;
            }
        }
        return -1;
    }

    /**
     * Within {@code line[from..to)}, return the byte positions of commas at
     * depth 0 (i.e., not inside any nested bracket / string / comment).
     */
    private java.util.List<Integer> findTopLevelCommas(String line, int from, int to) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int depth = 0;
        for (int i = from; i < to; i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipStringLiteral(line, i);
                if (end < 0 || end >= to) return out;
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < to) {
                char d = line.charAt(i + 1);
                if (d == '/') return out;
                if (d == '*') {
                    int end = line.indexOf("*/", i + 2);
                    if (end < 0 || end >= to) return out;
                    i = end + 1;
                    continue;
                }
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) out.add(i);
        }
        return out;
    }

    /**
     * Given that {@code line.charAt(start)} is a {@code "} or {@code '},
     * return the position of the matching closing quote, accounting for
     * backslash escapes. Returns -1 if the literal is unterminated on this
     * line.
     */
    private int skipStringLiteral(String line, int start) {
        char quote = line.charAt(start);
        int n = line.length();
        // Rust raw strings (r"...", r#"..."#) are not handled here; if we
        // see an r"..." we still treat it as a normal string for the
        // purpose of skipping, which is correct as long as there are no
        // escaped quotes inside. This is a fallback; precise handling can
        // be added if needed.
        for (int i = start + 1; i < n; i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i++;
                continue;
            }
            if (c == quote) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    // Public entry points (preserved interface)

    private static FileSystem fileSystem = FileSystems.getDefault();

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java org.congocc.codegen.rust.RustFormatter <filename>");
            return;
        }
        formatFile(args[0]);
    }

    static void formatFile(String filename) throws IOException {
        Path path = fileSystem.getPath(filename);
        Crate root = CongoCCParser.parseRustFile(path);
        RustFormatter formatter = new RustFormatter();
        formatter.visit(root);

        // Post-process: wrap any line that exceeds the Style Guide's
        // recommended maximum width.
        String wrapped = formatter.wrapLongLines(formatter.buffer.toString());

        // Ensure a trailing newline so the output is a well-formed text file.
        if (wrapped.isEmpty() || wrapped.charAt(wrapped.length() - 1) != '\n') {
            wrapped = wrapped + formatter.eol;
        }
        System.out.print(wrapped);
    }
}
