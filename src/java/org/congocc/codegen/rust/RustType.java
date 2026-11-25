package org.congocc.codegen.rust;

/**
 * The static types that {@link RustJavaTranslator} can represent in generated
 * Rust code.
 *
 * <p>Java code embedded in a grammar is translated with full type checking:
 * every translated expression has one of these types, and an expression whose
 * Java type has no entry here is rejected instead of being emitted as untyped
 * text.  The set is deliberately small; it covers what grammar actions,
 * predicates and injected parser/lexer members use in practice (see the
 * "Rust target" section of the developer documentation for the supported
 * subset).</p>
 *
 * <p>Java {@code null} has no Rust counterpart, so the two Java reference
 * types that grammars routinely set to or compare with {@code null} are
 * mapped as follows:</p>
 * <ul>
 *   <li>a {@code TokenType} that is {@code null} is represented by
 *       {@code TokenType::DUMMY}, the sentinel that {@code getTokenType(n)}
 *       also returns where Java returns {@code null};</li>
 *   <li>the lexer's {@code matchedToken} is an {@code Option<Token>}
 *       ({@link #OPT_TOKEN}).</li>
 * </ul>
 */
enum RustType {
    /** Java {@code boolean}. */
    BOOL("bool", "false"),
    /** Java {@code int} (also {@code short} and {@code byte}). */
    INT("i32", "0"),
    /** Java {@code long}. */
    LONG("i64", "0"),
    /** Java {@code char}: a Unicode code point in Rust (see the docs on offsets). */
    CHAR("char", "'\\0'"),
    /**
     * Java {@code String}, restricted to values known to be static: string
     * literals and production names taken from the parser call stacks.
     */
    STRING("&'static str", "\"\""),
    /** The grammar's {@code TokenType}; {@code null} maps to {@code TokenType::DUMMY}. */
    TOKEN_TYPE("TokenType", "TokenType::DUMMY"),
    /** A token value (the Rust {@code Token} is {@code Copy}). */
    TOKEN("Token", "Token::dummy()"),
    /** The lexer's {@code matchedToken}, which Java code may set to {@code null}. */
    OPT_TOKEN("Option<Token>", "None"),
    /** The grammar's {@code LexicalState}. */
    LEXICAL_STATE("LexicalState", null),
    /** An entry of the parser's call stacks ({@code NonTerminalCall}). */
    NT_CALL("NonTerminalCall", null),
    /** A cursor over the parser's call stacks ({@code ListIterator<NonTerminalCall>}). */
    STACK_ITER("StackIter", null),
    /** The type of the Java literal {@code null}, only valid where a conversion exists. */
    NULL(null, null),
    /** The result of a call to a {@code void} method. */
    VOID("()", null);

    /** The Rust spelling of the type, or null where it cannot be declared. */
    final String rust;

    /**
     * The Rust value that Java's default field initialization corresponds to,
     * or null if a field of this type must be explicitly initialized.
     */
    final String defaultValue;

    RustType(String rust, String defaultValue) {
        this.rust = rust;
        this.defaultValue = defaultValue;
    }

    /** @return true for the types that take part in Java numeric promotion */
    boolean isNumeric() {
        return this == INT || this == LONG || this == CHAR;
    }

    /** @return true if a variable or field of this type can be declared */
    boolean isDeclarable() {
        return rust != null && this != VOID;
    }

    /**
     * @return the name used in diagnostics with its indefinite article
     *         ("an int", "a boolean"), for messages such as "cannot compare
     *         an int with a boolean"
     */
    String withArticle() {
        String name = describe();
        return ("aeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    /** @return the Java-ish name used in diagnostics */
    String describe() {
        return switch (this) {
            case BOOL -> "boolean";
            case INT -> "int";
            case LONG -> "long";
            case CHAR -> "char";
            case STRING -> "String";
            case TOKEN_TYPE -> "TokenType";
            case TOKEN, OPT_TOKEN -> "Token";
            case LEXICAL_STATE -> "LexicalState";
            case NT_CALL -> "NonTerminalCall";
            case STACK_ITER -> "ListIterator<NonTerminalCall>";
            case NULL -> "null";
            case VOID -> "void";
        };
    }
}
