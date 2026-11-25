package org.congocc.codegen.rust;

import org.congocc.parser.Node;

/**
 * Signals that a piece of Java code embedded in a grammar (a code action, a
 * semantic lookahead, an assertion, a tree-building condition, a lexical
 * action, or an {@code INJECT PARSER_CLASS}/{@code INJECT LEXER_CLASS}
 * member) cannot be translated to Rust by {@link RustCodeEmitter}.
 *
 * <p>The exception carries the innermost grammar node that could not be
 * translated, so that the diagnostic reported to the grammar author points at
 * the exact construct (for example the unsupported method call inside a
 * larger expression) rather than at the whole code block.</p>
 *
 * <p>This is a checked exception on purpose: every caller inside the emitter
 * must decide how a failure is classified (error, or warning with a stub) and
 * cannot let it escape by accident.</p>
 */
public final class RustTranslationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The grammar node that could not be translated; may be null. */
    private final transient Node node;

    /**
     * @param node   the grammar node that could not be translated (may be
     *               null when no better location is known)
     * @param reason a human-readable explanation of why the construct cannot
     *               be translated, phrased so that it completes the sentence
     *               "cannot translate ... to Rust: &lt;reason&gt;"
     */
    public RustTranslationException(Node node, String reason) {
        super(reason);
        this.node = node;
    }

    /**
     * @return the grammar node that could not be translated, or null
     */
    public Node getNode() {
        return node;
    }

    /**
     * @return the grammar location ({@code file:line:column}) of the node that
     *         could not be translated, or {@code "unknown location"}
     */
    public String getLocation() {
        if (node == null) {
            return "unknown location";
        }
        String location = node.getLocation();
        return location == null || location.isEmpty() ? "unknown location" : location;
    }
}
