package org.congocc.codegen.rust;

import java.math.BigInteger;
import java.util.*;

import org.congocc.core.BNFProduction;
import org.congocc.parser.Node;
import org.congocc.parser.Token;
import org.congocc.parser.tree.*;

/**
 * Typed translator from the Java code embedded in a CongoCC grammar to Rust.
 *
 * <h2>Why a dedicated translator</h2>
 * The shared {@code Translator} infrastructure converts Java into a
 * language-neutral AST and prints it for Python and C#.  Those targets are
 * dynamically typed or close enough to Java that untyped printing works.  Rust
 * is not: integers do not mix with characters, {@code null} does not exist,
 * mutable borrows of {@code self} must not overlap, and the parser's state is
 * owned by a struct.  Printing an untyped AST produced code that either failed
 * to compile or, worse, compiled with different behaviour.  This class works
 * directly on the CongoCC Java AST ({@code org.congocc.parser.tree}), keeps a
 * static {@link RustType} for every expression, and refuses (by throwing
 * {@link RustTranslationException}) anything it cannot translate faithfully.
 * It never emits a guess.
 *
 * <h2>What is supported</h2>
 * Local variables and assignments (including chained and compound
 * assignments), {@code ++}/{@code --} in statement and expression position,
 * {@code if}/{@code else}, {@code while}, {@code do}/{@code while} (without
 * {@code continue}), classic {@code switch} statements (translated to
 * {@code match}, with labelled-block breaks where needed), {@code break},
 * {@code continue}, {@code return} (in injected methods), {@code assert}
 * (as {@code debug_assert!}), the ternary operator, {@code instanceof} on
 * token classes, casts to int and long, Java numeric promotion, the parser API used
 * by grammars ({@code getToken}, {@code getTokenType},
 * {@code checkNextTokenType}, {@code isInProduction}, the call stack
 * iterators), the lexer API used by lexical actions ({@code charAt},
 * {@code switchTo}, {@code matchedToken} and the token offset/type
 * manipulation methods), {@code Character.isXxx} predicates, and calls to
 * methods injected with {@code INJECT PARSER_CLASS}/{@code INJECT LEXER_CLASS}.
 *
 * <h2>Evaluation order and borrowing</h2>
 * Java evaluates operands left to right; so does Rust, so expressions are
 * translated in place.  Side effects inside expressions ({@code i++},
 * assignments used as values) become block expressions such as
 * {@code { let __t0 = i; i += 1; __t0 }}, which keep Java's order.  The only
 * construct Rust's borrow checker rejects is a mutation inside the argument
 * list of a call on {@code self} (the receiver is borrowed first), so such
 * arguments are evaluated into temporaries before the call, which is again
 * Java's order.
 */
final class RustJavaTranslator {

    // =====================================================================
    // Symbols and member descriptions
    // =====================================================================

    /** Which generated struct a piece of code belongs to. */
    enum Side { PARSER, LEXER }

    /**
     * The names that translated code can refer to.  Implemented by
     * {@link RustCodeEmitter}, which collects them from the grammar.
     */
    interface Symbols {
        /** @return true if {@code name} is a constant of the grammar's {@code TokenType} */
        boolean isTokenType(String name);

        /** @return true if {@code name} is one of the grammar's lexical states */
        boolean isLexicalState(String name);

        /**
         * @return the token types whose generated Java token class is, or
         *         directly extends, the class {@code simpleClassName}; null if
         *         no such token class exists
         */
        List<String> tokenTypesOfClass(String simpleClassName);

        /** @return true if the name denotes the base token class (every token is an instance) */
        boolean isBaseTokenClass(String simpleClassName);

        /** @return the injected field {@code javaName} of the given side, or null */
        FieldInfo field(Side side, String javaName);

        /** @return the injected methods named {@code javaName} of the given side (possibly empty) */
        List<MethodInfo> methods(Side side, String javaName);

        /**
         * @return the locals declared at the top level of the code blocks of
         *         {@code production} that are visible from a site; when
         *         {@code inline} is false only locals declared in code blocks
         *         that also run during lookahead are visible
         */
        Map<String, Local> productionLocals(BNFProduction production, boolean inline);

        /** @return the parameters of {@code production} (empty map if none) */
        Map<String, Local> productionParameters(BNFProduction production);

        /**
         * @return the name of the generated Rust function implementing
         *         {@code Character.<javaMethod>(int)}, or null if the predicate
         *         is not supported
         */
        String characterPredicate(String javaMethod);

        /** @return the node package of the grammar (NODE_PACKAGE) */
        String nodePackage();

        /** @return the parser package of the grammar (PARSER_PACKAGE) */
        String parserPackage();
    }

    /** How a variable is held in the generated Rust code. */
    enum Place {
        /** A temporary value (for example the result of a call). */
        RVALUE,
        /** A local variable owned by the current Rust function. */
        LOCAL,
        /** A method parameter of Java type Token, passed as {@code &mut Token}. */
        REF_PARAM,
        /** The lexer's {@code matched_token}, an {@code Option<Token>}. */
        OPTION_LOCAL,
        /** A field of the parser or lexer struct ({@code self.x}). */
        FIELD
    }

    /** A local variable or parameter visible to translated code. */
    static final class Local {
        final String javaName;
        final String rustName;
        final RustType type;
        final Place place;
        /** Non-null if the variable exists in Java but cannot be used from Rust. */
        final String unavailableReason;

        Local(String javaName, String rustName, RustType type, Place place, String unavailableReason) {
            this.javaName = javaName;
            this.rustName = rustName;
            this.type = type;
            this.place = place;
            this.unavailableReason = unavailableReason;
        }
    }

    /** A field injected with INJECT PARSER_CLASS or INJECT LEXER_CLASS. */
    static final class FieldInfo {
        final String javaName;
        final String rustName;
        /** The field's type, or null if the type is not supported. */
        final RustType type;
        final Node declaration;
        /** The Java initializer expression, or null. */
        final Node initializer;
        /** The translated initializer (filled in by the emitter's pre-pass). */
        String rustInitializer;
        /** Non-null if the field cannot be used from Rust. */
        String unsupportedReason;

        FieldInfo(String javaName, String rustName, RustType type, Node declaration, Node initializer) {
            this.javaName = javaName;
            this.rustName = rustName;
            this.type = type;
            this.declaration = declaration;
            this.initializer = initializer;
        }
    }

    /** A parameter of an injected method. */
    static final class ParamInfo {
        final String javaName;
        final String rustName;
        final RustType type;
        /** True for Token parameters, which are passed as {@code &mut Token}. */
        final boolean tokenRef;

        ParamInfo(String javaName, String rustName, RustType type, boolean tokenRef) {
            this.javaName = javaName;
            this.rustName = rustName;
            this.type = type;
            this.tokenRef = tokenRef;
        }
    }

    /** A method injected with INJECT PARSER_CLASS or INJECT LEXER_CLASS. */
    static final class MethodInfo {
        final Side side;
        final String javaName;
        final String rustName;
        /** Null if the signature uses an unsupported type. */
        final RustType returnType;
        final List<ParamInfo> params;
        final MethodDeclaration declaration;
        /** Non-null once the method is known to be untranslatable. */
        String unsupportedReason;
        /** The innermost node responsible for {@link #unsupportedReason}. */
        Node unsupportedNode;
        /** The translated Rust method (filled in by the emitter's pre-pass). */
        String rustCode;

        MethodInfo(Side side, String javaName, String rustName, RustType returnType,
                   List<ParamInfo> params, MethodDeclaration declaration) {
            this.side = side;
            this.javaName = javaName;
            this.rustName = rustName;
            this.returnType = returnType;
            this.params = params;
            this.declaration = declaration;
        }
    }

    // =====================================================================
    // Translation results and context
    // =====================================================================

    /**
     * A translated expression.
     *
     * @param code    the Rust source of the expression
     * @param type    its static type
     * @param atomic  true if the code can be used as an operand without
     *                parentheses (names, literals, calls, field accesses,
     *                parenthesized and block expressions)
     * @param effects true if evaluating it may change state (assignments,
     *                increments, calls that take {@code &mut self})
     * @param place   how the value is held, which matters for tokens that are
     *                read or modified in place
     */
    record RExpr(String code, RustType type, boolean atomic, boolean effects, Place place) {
        /**
         * @return the code, parenthesized unless it is atomic.  Block
         *         expressions ({@code { let t = ...; t }}) are always
         *         parenthesized: a block at the start of a statement would
         *         otherwise be parsed as a block statement.
         */
        String operand() {
            return atomic && !code.startsWith("{") ? code : "(" + code + ")";
        }

        static RExpr value(String code, RustType type, boolean atomic, boolean effects) {
            return new RExpr(code, type, atomic, effects, Place.RVALUE);
        }
    }

    /**
     * A target of a Java {@code break} or {@code continue} statement.
     *
     * <p>Every translated loop is labelled and every {@code break} and
     * {@code continue} names its target.  Rust rejects an unlabelled
     * {@code break}/{@code continue} inside a labelled block (error E0695),
     * and a switch statement may need to become a labelled block, so
     * labelling everything is the only scheme that always compiles.</p>
     */
    private static final class BreakTarget {
        final boolean isLoop;
        /** The Rust label of the loop or of the switch block. */
        final String label;
        boolean labelUsed;
        /** The break statements that end a case group and are dropped. */
        final Set<Node> droppedBreaks = new HashSet<>();

        BreakTarget(boolean isLoop, String label) {
            this.isLoop = isLoop;
            this.label = label;
        }
    }

    /** The context in which one code site is translated. */
    static final class Ctx {
        final Side side;
        /** The production whose locals are visible, or null. */
        final BNFProduction production;
        /** True if the code is emitted inside the production's own function. */
        final boolean inline;
        /** The injected method being translated, or null. */
        final MethodInfo method;
        /** True for field initializers, which cannot refer to {@code self}. */
        final boolean constantOnly;
        /** The grammar location of the site, for runtime panic messages. */
        final String siteLocation;
        private final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
        private final Deque<BreakTarget> breakTargets = new ArrayDeque<>();
        private int tempCounter;
        private int labelCounter;
        /** The injected methods called by the translated code. */
        final Set<MethodInfo> calledMethods = new LinkedHashSet<>();

        Ctx(Side side, BNFProduction production, boolean inline, MethodInfo method,
            boolean constantOnly, String siteLocation) {
            this.side = side;
            this.production = production;
            this.inline = inline;
            this.method = method;
            this.constantOnly = constantOnly;
            this.siteLocation = siteLocation;
            scopes.push(new LinkedHashMap<>());
        }

        void pushScope() {
            scopes.push(new LinkedHashMap<>());
        }

        void popScope() {
            scopes.pop();
        }

        /** Declares a variable in the innermost scope. */
        void declare(Local local) {
            scopes.peek().put(local.javaName, local);
        }

        Local lookup(String javaName) {
            for (Map<String, Local> scope : scopes) {
                Local local = scope.get(javaName);
                if (local != null) {
                    return local;
                }
            }
            return null;
        }

        String newTemp() {
            return "__t" + (tempCounter++);
        }

        String newSwitchLabel() {
            return "'__switch" + (labelCounter++);
        }

        String newLoopLabel() {
            return "'__loop" + (labelCounter++);
        }

        /** @return the innermost enclosing loop, or null */
        BreakTarget innermostLoop() {
            for (BreakTarget target : breakTargets) {
                if (target.isLoop) {
                    return target;
                }
            }
            return null;
        }
    }

    // =====================================================================
    // Construction
    // =====================================================================

    private final Symbols symbols;

    RustJavaTranslator(Symbols symbols) {
        this.symbols = symbols;
    }

    // =====================================================================
    // Naming
    // =====================================================================

    /** Rust keywords that cannot be used as identifiers without escaping. */
    private static final Set<String> RUST_KEYWORDS = Set.of(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
        "trait", "true", "type", "unsafe", "use", "where", "while", "abstract", "become",
        "box", "do", "final", "macro", "override", "priv", "typeof", "unsized", "virtual",
        "yield", "try", "gen");

    /** Keywords that cannot be raw identifiers either. */
    private static final Set<String> NON_RAW_KEYWORDS = Set.of("self", "Self", "super", "crate");

    /**
     * Converts a Java identifier to the Rust naming convention for variables,
     * fields, methods and production parameters.  {@code camelCase} becomes
     * {@code camel_case}, an all-caps constant is simply lower-cased, and
     * Rust keywords are escaped.  The characters that Java allows in
     * identifiers but Rust does not, {@code $} above all (the hooks that
     * CongoCC recognizes are named {@code tokenHook$xxx}), become {@code _}.
     *
     * <p>Different Java names can give the same Rust name ({@code fooBar} and
     * {@code foo_bar}, {@code a$b} and {@code a_b}); RustCodeEmitter reports
     * injected members whose Rust names collide.</p>
     *
     * @param javaName a Java identifier
     * @return the corresponding Rust identifier
     */
    static String snakeCase(String javaName) {
        String result;
        if (javaName.chars().noneMatch(Character::isLowerCase)) {
            result = javaName.toLowerCase(Locale.ROOT);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < javaName.length(); i++) {
                char c = javaName.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) {
                        sb.append('_');
                    }
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
            result = sb.toString();
        }
        result = rustIdentifierCharacters(result);
        if (NON_RAW_KEYWORDS.contains(result)) {
            return result + "_";
        }
        if (RUST_KEYWORDS.contains(result)) {
            return "r#" + result;
        }
        return result;
    }

    /**
     * @return {@code name} with every character that cannot be in a Rust
     *         identifier (Rust follows Unicode's XID_Start and XID_Continue)
     *         replaced by {@code _}; a lone {@code _}, which is not an
     *         identifier in Rust, becomes {@code __}
     */
    static String rustIdentifierCharacters(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            boolean valid = cp == '_' || (sb.length() == 0 ? Character.isUnicodeIdentifierStart(cp)
                                                           : Character.isUnicodeIdentifierPart(cp)
                                                             && !Character.isIdentifierIgnorable(cp));
            sb.appendCodePoint(valid ? cp : '_');
        });
        String result = sb.toString();
        return result.equals("_") ? "__" : result;
    }

    // =====================================================================
    // Literals (also used by the emitter for templates)
    // =====================================================================

    /**
     * Encodes a Java string (UTF-16) as a Rust string literal.
     *
     * @param s       the string
     * @param at      the grammar node the string comes from (for diagnostics)
     * @return the Rust literal, ASCII only, with every other character escaped
     * @throws RustTranslationException if the string contains an unpaired
     *         surrogate, which a Rust {@code str} cannot hold
     */
    static String rustStringLiteral(String s, Node at) throws RustTranslationException {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isSurrogate(s.charAt(i)) && Character.charCount(cp) == 1) {
                throw new RustTranslationException(at,
                    "the string contains an unpaired UTF-16 surrogate (\\u"
                    + String.format("%04X", (int) s.charAt(i)) + "), which a Rust string cannot represent");
            }
            appendRustChar(sb, cp, '"');
            i += Character.charCount(cp);
        }
        return sb.append('"').toString();
    }

    /** @return the Rust char literal for a code point */
    static String rustCharLiteral(int cp) {
        StringBuilder sb = new StringBuilder("'");
        appendRustChar(sb, cp, '\'');
        return sb.append('\'').toString();
    }

    private static void appendRustChar(StringBuilder sb, int cp, char quote) {
        switch (cp) {
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case 0 -> sb.append("\\0");
            default -> {
                if (cp == quote) {
                    sb.append('\\').append(quote);
                } else if (cp >= 0x20 && cp < 0x7F) {
                    sb.append((char) cp);
                } else {
                    sb.append("\\u{").append(Integer.toHexString(cp)).append('}');
                }
            }
        }
    }

    /**
     * Decodes the body of a Java string or character literal (without the
     * quotes), processing every Java escape sequence.
     */
    private static String unescapeJava(String body, Node at) throws RustTranslationException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= body.length()) {
                throw new RustTranslationException(at, "the literal ends with a lone backslash");
            }
            char e = body.charAt(i);
            switch (e) {
                case 'b' -> sb.append('\b');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'f' -> sb.append('\f');
                case 'r' -> sb.append('\r');
                case 's' -> sb.append(' ');
                case '"' -> sb.append('"');
                case '\'' -> sb.append('\'');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    // Java allows any number of 'u's in a unicode escape (\\uuu0041).
                    while (i + 1 < body.length() && body.charAt(i + 1) == 'u') {
                        i++;
                    }
                    String hex = body.substring(i + 1, Math.min(i + 5, body.length()));
                    if (hex.length() != 4 || !hex.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
                        throw new RustTranslationException(at, "malformed \\u escape in the literal");
                    }
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                default -> {
                    if (e >= '0' && e <= '7') {
                        // Octal escape: up to three digits, at most \377.
                        int value = e - '0';
                        int maxDigits = e <= '3' ? 3 : 2;
                        int digits = 1;
                        while (digits < maxDigits && i + 1 < body.length()
                               && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') {
                            value = value * 8 + (body.charAt(++i) - '0');
                            digits++;
                        }
                        sb.append((char) value);
                    } else {
                        throw new RustTranslationException(at, "unknown escape sequence \\" + e + " in the literal");
                    }
                }
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // AST helpers
    // =====================================================================

    /** @return the children of a node that are not tokens */
    static List<Node> nodeChildren(Node n) {
        List<Node> result = new ArrayList<>();
        for (Node child : n) {
            if (!(child instanceof Token)) {
                result.add(child);
            }
        }
        return result;
    }

    /** @return the non-token children of a code block, i.e. its statements */
    static List<Node> blockStatements(Node block) throws RustTranslationException {
        List<Node> result = new ArrayList<>();
        for (Node child : block) {
            if (child instanceof Token t) {
                String image = t.toString();
                if (image.equals("{") || image.equals("}") || image.equals(";")) {
                    continue;
                }
                throw new RustTranslationException(child, "unexpected token '" + image + "' in a code block");
            }
            result.add(child);
        }
        return result;
    }

    /**
     * @return the identifiers of a (possibly qualified) name.  Identifiers that
     *         contain dots (the preprocessor substitutes NODE_PACKAGE and
     *         similar settings as single tokens) are split.
     */
    static List<String> nameParts(Node name) {
        List<String> parts = new ArrayList<>();
        for (Node child : name) {
            if (child instanceof Identifier || child instanceof KeyWord) {
                parts.addAll(Arrays.asList(child.toString().split("\\.")));
            }
        }
        return parts;
    }

    /** @return the Java source text of a node on one line, for messages */
    static String oneLine(Node n) {
        String source = n.getSource();
        if (source == null) {
            source = n.toString();
        }
        source = source.replaceAll("\\s+", " ").trim();
        return source.length() > 120 ? source.substring(0, 117) + "..." : source;
    }

    private static String simpleClassName(Node n) {
        return n.getClass().getSimpleName();
    }

    private static RustTranslationException unsupported(Node at, String reason) {
        return new RustTranslationException(at, reason);
    }

    private static String pad(int indent) {
        return " ".repeat(Math.max(0, indent));
    }

    // =====================================================================
    // Types
    // =====================================================================

    /**
     * Maps a Java type node to a {@link RustType}.
     *
     * @param typeNode a PrimitiveType, ObjectType or ReferenceType node
     * @return the type, or null for the Java {@code var} keyword
     * @throws RustTranslationException if the type has no Rust counterpart
     */
    RustType typeOf(Node typeNode) throws RustTranslationException {
        if (typeNode instanceof PrimitiveType) {
            String name = typeNode.toString().trim();
            return switch (name) {
                case "boolean" -> RustType.BOOL;
                case "int", "short", "byte" -> RustType.INT;
                case "long" -> RustType.LONG;
                case "char" -> RustType.CHAR;
                default -> throw unsupported(typeNode,
                    "the Java type '" + name + "' is not supported (floating point arithmetic is not part of the supported subset)");
            };
        }
        if (typeNode instanceof ObjectType) {
            String full = dottedName(typeNode);
            String simple = full.substring(full.lastIndexOf('.') + 1);
            TypeArguments typeArgs = typeNode.firstChildOfType(TypeArguments.class);
            if (simple.equals("var") && typeArgs == null) {
                return null;
            }
            if (typeArgs == null) {
                switch (simple) {
                    case "String":
                        return RustType.STRING;
                    case "TokenType":
                        return RustType.TOKEN_TYPE;
                    case "LexicalState":
                        return RustType.LEXICAL_STATE;
                    case "NonTerminalCall":
                        return RustType.NT_CALL;
                    default:
                        if (simple.equals("Token") || symbols.isBaseTokenClass(simple)) {
                            return RustType.TOKEN;
                        }
                }
            } else if ((simple.equals("ListIterator") || simple.equals("Iterator"))) {
                List<ObjectType> args = typeArgs.childrenOfType(ObjectType.class);
                if (args.size() == 1 && dottedName(args.get(0)).endsWith("NonTerminalCall")) {
                    return RustType.STACK_ITER;
                }
            }
            throw unsupported(typeNode, "the Java type '" + oneLine(typeNode)
                + "' has no counterpart in the Rust target (supported: boolean, int, long, char, String, "
                + "TokenType, LexicalState, Token, NonTerminalCall and ListIterator<NonTerminalCall>)");
        }
        throw unsupported(typeNode, "the Java type '" + oneLine(typeNode) + "' is not supported (arrays and generic types are not part of the supported subset)");
    }

    /** @return the dotted name of an ObjectType, without type arguments */
    static String dottedName(Node objectType) {
        StringBuilder sb = new StringBuilder();
        for (Node child : objectType) {
            if (child instanceof Identifier) {
                sb.append(child.toString());
            } else if (child instanceof Delimiter && child.toString().equals(".")) {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * Converts a translated value to a target type the way Java's assignment
     * conversion does, for the conversions the supported subset needs.
     */
    RExpr convert(RExpr value, RustType target, Node at, Ctx ctx) throws RustTranslationException {
        if (value.type() == RustType.TOKEN && value.place() == Place.REF_PARAM
            && (target == null || target == RustType.TOKEN)) {
            // A Token parameter is a '&mut Token'; its value is a copy.
            return RExpr.value("(*" + value.code() + ")", RustType.TOKEN, true, value.effects());
        }
        if (target == null || value.type() == target) {
            return value;
        }
        RustType source = value.type();
        switch (target) {
            case INT:
                if (source == RustType.CHAR) {
                    return RExpr.value(value.operand() + " as i32", RustType.INT, false, value.effects());
                }
                break;
            case LONG:
                if (source == RustType.CHAR || source == RustType.INT) {
                    return RExpr.value(value.operand() + " as i64", RustType.LONG, false, value.effects());
                }
                break;
            case OPT_TOKEN:
                if (source == RustType.TOKEN) {
                    return RExpr.value("Some(" + tokenValue(value, at, ctx) + ")", RustType.OPT_TOKEN, true, value.effects());
                }
                if (source == RustType.NULL) {
                    return RExpr.value("None", RustType.OPT_TOKEN, true, false);
                }
                break;
            case TOKEN:
                if (source == RustType.OPT_TOKEN) {
                    return RExpr.value(tokenValue(value, at, ctx), RustType.TOKEN, true, value.effects());
                }
                break;
            case TOKEN_TYPE:
                if (source == RustType.NULL) {
                    return RExpr.value("TokenType::DUMMY", RustType.TOKEN_TYPE, true, false);
                }
                break;
            default:
                break;
        }
        throw unsupported(at, "a value of type " + source.describe() + " cannot be used where "
            + target.describe() + " is expected" + (source == RustType.NULL
                ? " (null is only supported for TokenType values, which map to TokenType::DUMMY, and for matchedToken)"
                : ""));
    }

    // =====================================================================
    // Tokens held in different places
    // =====================================================================

    private static String nullTokenMessage(Node at, Ctx ctx) {
        String location = at.getLocation();
        return "\"the code at " + escapeForRustString(location == null ? ctx.siteLocation : location)
            + " uses matchedToken while it is null (Java would throw NullPointerException here)\"";
    }

    static String escapeForRustString(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> appendRustChar(sb, cp, '"'));
        return sb.toString();
    }

    /** @return code yielding the token as a {@code Token} value */
    private String tokenValue(RExpr e, Node at, Ctx ctx) {
        return switch (e.place()) {
            case REF_PARAM -> "(*" + e.code() + ")";
            case OPTION_LOCAL -> e.code() + ".expect(" + nullTokenMessage(at, ctx) + ")";
            default -> e.operand();
        };
    }

    /** @return code usable as the receiver of a {@code &self} method of Token */
    private String tokenReceiver(RExpr e, Node at, Ctx ctx) {
        return switch (e.place()) {
            case REF_PARAM -> e.code();
            case OPTION_LOCAL -> e.code() + ".expect(" + nullTokenMessage(at, ctx) + ")";
            default -> e.operand();
        };
    }

    /** @return code yielding a {@code &Token} */
    private String tokenRef(RExpr e, Node at, Ctx ctx) {
        return switch (e.place()) {
            case LOCAL, FIELD -> "&" + e.code();
            case REF_PARAM -> e.code();
            case OPTION_LOCAL -> e.code() + ".as_ref().expect(" + nullTokenMessage(at, ctx) + ")";
            case RVALUE -> "&" + e.operand();
        };
    }

    /** @return code yielding a {@code &mut Token} */
    private String tokenMutRef(RExpr e, Node at, Ctx ctx) throws RustTranslationException {
        return switch (e.place()) {
            case LOCAL -> "&mut " + e.code();
            case REF_PARAM -> e.code();
            case OPTION_LOCAL -> e.code() + ".as_mut().expect(" + nullTokenMessage(at, ctx) + ")";
            default -> throw unsupported(at, "this token cannot be modified in place from Rust: only local "
                + "variables, Token parameters and matchedToken can be modified");
        };
    }

    // =====================================================================
    // Statements
    // =====================================================================

    /**
     * Translates a list of Java statements, appending Rust lines to
     * {@code out}.
     */
    void statements(List<Node> statements, Ctx ctx, StringBuilder out, int indent) throws RustTranslationException {
        for (Node statement : statements) {
            statement(statement, ctx, out, indent);
        }
    }

    /** Translates one Java statement. */
    void statement(Node s, Ctx ctx, StringBuilder out, int indent) throws RustTranslationException {
        if (s instanceof LocalVariableDeclaration d) {
            localDeclaration(d, ctx, out, indent);
        } else if (s instanceof ExpressionStatement) {
            List<Node> kids = nodeChildren(s);
            if (kids.size() != 1) {
                throw unsupported(s, "unexpected expression statement shape");
            }
            expressionStatement(kids.get(0), ctx, out, indent);
        } else if (s instanceof CodeBlock) {
            out.append(pad(indent)).append("{\n");
            ctx.pushScope();
            statements(blockStatements(s), ctx, out, indent + 4);
            ctx.popScope();
            out.append(pad(indent)).append("}\n");
        } else if (s instanceof IfStatement i) {
            ifStatement(i, ctx, out, indent, false);
        } else if (s instanceof WhileStatement w) {
            whileStatement(w, ctx, out, indent);
        } else if (s instanceof DoStatement d) {
            doStatement(d, ctx, out, indent);
        } else if (s instanceof SwitchStatement sw) {
            switchStatement(sw, ctx, out, indent);
        } else if (s instanceof BreakStatement b) {
            breakStatement(b, ctx, out, indent);
        } else if (s instanceof ContinueStatement c) {
            if (c.firstChildOfType(Identifier.class) != null) {
                throw unsupported(c, "labelled continue statements are not supported");
            }
            BreakTarget loop = ctx.innermostLoop();
            if (loop == null) {
                throw unsupported(c, "a 'continue' statement outside of a loop of the same code block "
                    + "would jump inside the generated parser and cannot be translated");
            }
            out.append(pad(indent)).append("continue ").append(loop.label).append(";\n");
        } else if (s instanceof ReturnStatement r) {
            returnStatement(r, ctx, out, indent);
        } else if (s instanceof AssertStatement a) {
            assertStatement(a, ctx, out, indent);
        } else if (s instanceof EmptyStatement) {
            // Nothing to do.
        } else {
            throw unsupported(s, "the Java statement kind " + simpleClassName(s) + " is not supported");
        }
    }

    private void localDeclaration(LocalVariableDeclaration d, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        Node typeNode = null;
        for (Node child : d) {
            if (child instanceof PrimitiveType || child instanceof ObjectType || child instanceof ReferenceType) {
                typeNode = child;
                break;
            }
        }
        if (typeNode == null) {
            throw unsupported(d, "unexpected local variable declaration shape");
        }
        RustType declared = typeOf(typeNode);
        for (VariableDeclarator vd : d.childrenOfType(VariableDeclarator.class)) {
            Node first = vd.get(0);
            if (!(first instanceof Identifier)) {
                throw unsupported(vd, "array declarators are not supported");
            }
            String javaName = first.toString();
            Node init = initializerOf(vd);
            RExpr value = null;
            RustType type = declared;
            if (init != null) {
                if (init instanceof ArrayInitializer) {
                    throw unsupported(init, "array initializers are not supported");
                }
                value = expression(init, ctx);
                if (type == null) {
                    type = value.type();
                }
                value = convert(value, type, init, ctx);
            } else if (type == null) {
                throw unsupported(vd, "'var' requires an initializer");
            }
            if (!type.isDeclarable()) {
                throw unsupported(vd, "a variable of type " + type.describe() + " cannot be declared");
            }
            String rustName = snakeCase(javaName);
            Place place = type == RustType.OPT_TOKEN ? Place.OPTION_LOCAL : Place.LOCAL;
            ctx.declare(new Local(javaName, rustName, type, place, null));
            out.append(pad(indent)).append("let mut ").append(rustName).append(": ").append(type.rust);
            if (value != null) {
                out.append(" = ").append(value.code());
            }
            out.append(";\n");
        }
    }

    /** @return the initializer expression of a declarator, or null */
    static Node initializerOf(VariableDeclarator vd) {
        boolean seenAssign = false;
        for (Node child : vd) {
            if (child instanceof Operator && child.toString().equals("=")) {
                seenAssign = true;
            } else if (seenAssign) {
                return child;
            }
        }
        return null;
    }

    private void expressionStatement(Node e, Ctx ctx, StringBuilder out, int indent) throws RustTranslationException {
        while (e instanceof StatementExpression && nodeChildren(e).size() == 1) {
            e = nodeChildren(e).get(0);
        }
        if (e instanceof AssignmentExpression ae) {
            assignmentStatement(ae, ctx, out, indent);
        } else if (e instanceof PreIncrementExpression || e instanceof PreDecrementExpression
                   || e instanceof PostfixExpression) {
            boolean increment = isIncrement(e);
            RExpr target = assignable(incrementOperand(e), ctx);
            requireIntegral(target, e);
            out.append(pad(indent)).append(target.code()).append(increment ? " += 1;\n" : " -= 1;\n");
        } else if (e instanceof MethodCall) {
            RExpr call = expression(e, ctx);
            out.append(pad(indent)).append(call.code()).append(";\n");
        } else {
            throw unsupported(e, "the expression '" + oneLine(e) + "' cannot be used as a statement");
        }
    }

    private static boolean isIncrement(Node e) throws RustTranslationException {
        for (Node child : e) {
            if (child instanceof Operator op) {
                String image = op.toString();
                if (image.equals("++")) return true;
                if (image.equals("--")) return false;
            }
        }
        throw unsupported(e, "unexpected increment expression shape");
    }

    private static Node incrementOperand(Node e) throws RustTranslationException {
        List<Node> kids = nodeChildren(e);
        if (kids.size() != 1) {
            throw unsupported(e, "unexpected increment expression shape");
        }
        return kids.get(0);
    }

    private static void requireIntegral(RExpr target, Node at) throws RustTranslationException {
        if (target.type() != RustType.INT && target.type() != RustType.LONG) {
            throw unsupported(at, "++ and -- are only supported on int and long variables, not on "
                + target.type().describe());
        }
    }

    /** Translates a (possibly chained) assignment used as a statement. */
    private void assignmentStatement(AssignmentExpression ae, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        List<Node> targets = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        Node current = ae;
        while (current instanceof AssignmentExpression assignment) {
            List<Node> kids = new ArrayList<>();
            for (Node child : assignment) kids.add(child);
            if (kids.size() != 3 || !(kids.get(1) instanceof Operator)) {
                throw unsupported(assignment, "unexpected assignment shape");
            }
            targets.add(kids.get(0));
            ops.add(kids.get(1).toString());
            current = kids.get(2);
        }
        RExpr value = expression(current, ctx);
        if (targets.size() == 1) {
            out.append(pad(indent)).append(assignmentCode(targets.get(0), ops.get(0), value, ae, ctx)).append(";\n");
            return;
        }
        for (String op : ops) {
            if (!op.equals("=")) {
                throw unsupported(ae, "chained compound assignments are not supported");
            }
        }
        if (isLiteralCode(value.code())) {
            // a = b = 0: a constant can simply be assigned to every target
            // (right to left, as in Java), each with its own conversion.
            for (int i = targets.size() - 1; i >= 0; i--) {
                out.append(pad(indent)).append(assignmentCode(targets.get(i), "=", value, ae, ctx)).append(";\n");
            }
            return;
        }
        // a = b = v: v is evaluated once and assigned from right to left,
        // each target receiving the (converted) value of the one to its right.
        String valueCode = value.code();
        if (value.effects() || !value.atomic()) {
            String temp = ctx.newTemp();
            out.append(pad(indent)).append("let ").append(temp).append(" = ").append(value.code()).append(";\n");
            valueCode = temp;
        }
        RExpr carried = new RExpr(valueCode, value.type(), true, false, Place.RVALUE);
        for (int i = targets.size() - 1; i >= 0; i--) {
            out.append(pad(indent)).append(assignmentCode(targets.get(i), "=", carried, ae, ctx)).append(";\n");
            RExpr target = assignable(targets.get(i), ctx);
            carried = new RExpr(target.code(), target.type(), true, false, Place.RVALUE);
        }
    }

    /** @return the Rust assignment (without semicolon) */
    private String assignmentCode(Node targetNode, String op, RExpr value, Node at, Ctx ctx)
            throws RustTranslationException {
        RExpr target = assignable(targetNode, ctx);
        if (op.equals("=")) {
            return target.code() + " = " + convert(value, target.type(), at, ctx).code();
        }
        String base = op.substring(0, op.length() - 1);
        RustType t = target.type();
        switch (base) {
            case "+", "-", "*", "/", "%":
                if (t == RustType.INT || t == RustType.LONG) {
                    if (!value.type().isNumeric() || (t == RustType.INT && value.type() == RustType.LONG)) {
                        throw unsupported(at, "compound assignment of " + value.type().withArticle()
                            + " to " + t.withArticle() + " is not supported");
                    }
                    return compoundAssignment(target, base, convert(value, t, at, ctx), ctx);
                }
                break;
            case "&", "|", "^":
                if (t == RustType.BOOL && value.type() == RustType.BOOL) {
                    return compoundAssignment(target, base, value, ctx);
                }
                if ((t == RustType.INT || t == RustType.LONG) && value.type().isNumeric()
                    && !(t == RustType.INT && value.type() == RustType.LONG)) {
                    return compoundAssignment(target, base, convert(value, t, at, ctx), ctx);
                }
                break;
            case "<<", ">>", ">>>":
                if ((t == RustType.INT || t == RustType.LONG) && value.type().isNumeric()) {
                    return target.code() + " = " + shiftCode(base, target, value);
                }
                break;
            default:
                break;
        }
        throw unsupported(at, "the compound assignment operator " + op + " is not supported on " + t.describe());
    }

    /**
     * The Rust code of the compound assignment {@code target op= value} for
     * bool and integer operands (the value is already converted to the type
     * of the target).
     *
     * <p>Java evaluates a compound assignment from left to right: it reads
     * the target, evaluates the value, and stores the result.  Rust's
     * built-in compound assignment evaluates the value first, so a value that
     * calls a method which changes the target would give a different result
     * ({@code counter += reset()} with a {@code reset()} that sets
     * {@code counter}).  Only a field can be changed by a call, so a field
     * with a value that has effects is assigned through a block that reads
     * the field first; everything else keeps the plain operator.  Shifts are
     * not compound-assigned here: their translation ({@link #shiftCode})
     * calls a method on the target, which Rust evaluates first.</p>
     */
    private static String compoundAssignment(RExpr target, String base, RExpr value, Ctx ctx) {
        if (target.place() == Place.FIELD && value.effects()) {
            String temp = ctx.newTemp();
            return target.code() + " = { let " + temp + " = " + target.code() + "; " + temp + " " + base + " "
                + value.operand() + " }";
        }
        return target.code() + " " + base + "= " + value.operand();
    }

    /**
     * Resolves the left-hand side of an assignment or increment.
     */
    private RExpr assignable(Node targetNode, Ctx ctx) throws RustTranslationException {
        while (targetNode instanceof Parentheses p && nodeChildren(p).size() == 1) {
            targetNode = nodeChildren(p).get(0);
        }
        RExpr target;
        if (targetNode instanceof Name n) {
            List<String> parts = nameParts(n);
            if (parts.size() == 1) {
                target = simpleName(parts.get(0), n, ctx);
            } else if (parts.size() == 2 && parts.get(0).equals("this")) {
                target = fieldReference(parts.get(1), n, ctx);
            } else {
                throw unsupported(targetNode, "only local variables and fields can be assigned");
            }
        } else if (targetNode instanceof DotName dn && dn.get(0) instanceof ThisLiteral) {
            target = fieldReference(dn.get(dn.size() - 1).toString(), dn, ctx);
        } else {
            throw unsupported(targetNode, "only local variables and fields can be assigned");
        }
        if (target.place() != Place.LOCAL && target.place() != Place.FIELD && target.place() != Place.OPTION_LOCAL) {
            throw unsupported(targetNode, "'" + oneLine(targetNode) + "' cannot be assigned"
                + (target.place() == Place.REF_PARAM ? " (Token parameters are references in Rust)" : ""));
        }
        return target;
    }

    private void ifStatement(IfStatement i, Ctx ctx, StringBuilder out, int indent, boolean isElseIf)
            throws RustTranslationException {
        List<Node> kids = nodeChildren(i);
        if (kids.size() < 2 || kids.size() > 3) {
            throw unsupported(i, "unexpected if statement shape");
        }
        RExpr condition = convert(expression(kids.get(0), ctx), RustType.BOOL, kids.get(0), ctx);
        if (!isElseIf) {
            out.append(pad(indent));
        }
        out.append("if ").append(condition.code()).append(" {\n");
        nestedBlock(kids.get(1), ctx, out, indent + 4);
        if (kids.size() == 3) {
            Node elseBranch = kids.get(2);
            if (elseBranch instanceof IfStatement elseIf) {
                out.append(pad(indent)).append("} else ");
                ifStatement(elseIf, ctx, out, indent, true);
                return;
            }
            out.append(pad(indent)).append("} else {\n");
            nestedBlock(elseBranch, ctx, out, indent + 4);
        }
        out.append(pad(indent)).append("}\n");
    }

    /** Translates a statement used as the body of a compound statement, in its own scope. */
    private void nestedBlock(Node body, Ctx ctx, StringBuilder out, int indent) throws RustTranslationException {
        ctx.pushScope();
        if (body instanceof CodeBlock) {
            statements(blockStatements(body), ctx, out, indent);
        } else {
            statement(body, ctx, out, indent);
        }
        ctx.popScope();
    }

    private void whileStatement(WhileStatement w, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        List<Node> kids = nodeChildren(w);
        if (kids.size() != 2) {
            throw unsupported(w, "unexpected while statement shape");
        }
        RExpr condition = convert(expression(kids.get(0), ctx), RustType.BOOL, kids.get(0), ctx);
        String label = ctx.newLoopLabel();
        if (condition.code().equals("true")) {
            out.append(pad(indent)).append(label).append(": loop {\n");
        } else {
            out.append(pad(indent)).append(label).append(": while ").append(condition.code()).append(" {\n");
        }
        ctx.breakTargets.push(new BreakTarget(true, label));
        nestedBlock(kids.get(1), ctx, out, indent + 4);
        ctx.breakTargets.pop();
        out.append(pad(indent)).append("}\n");
    }

    private void doStatement(DoStatement d, Ctx ctx, StringBuilder out, int indent) throws RustTranslationException {
        List<Node> kids = nodeChildren(d);
        if (kids.size() != 2) {
            throw unsupported(d, "unexpected do statement shape");
        }
        Node body = kids.get(0);
        // A 'continue' of this loop must evaluate the condition, which the
        // 'loop { body; if !cond { break } }' translation cannot express.
        // A 'continue' of a loop nested in the body is fine.
        for (ContinueStatement c : body.descendants(ContinueStatement.class)) {
            Node owner = c.getParent();
            while (owner != null && !(owner instanceof WhileStatement) && !(owner instanceof DoStatement)
                   && !(owner instanceof BasicForStatement) && !(owner instanceof EnhancedForStatement)) {
                owner = owner.getParent();
            }
            if (owner == d) {
                throw unsupported(c, "'continue' inside a do/while loop is not supported");
            }
        }
        String label = ctx.newLoopLabel();
        out.append(pad(indent)).append(label).append(": loop {\n");
        ctx.breakTargets.push(new BreakTarget(true, label));
        nestedBlock(body, ctx, out, indent + 4);
        ctx.breakTargets.pop();
        RExpr condition = convert(expression(kids.get(1), ctx), RustType.BOOL, kids.get(1), ctx);
        out.append(pad(indent + 4)).append("if !").append(condition.operand()).append(" {\n");
        out.append(pad(indent + 8)).append("break ").append(label).append(";\n");
        out.append(pad(indent + 4)).append("}\n");
        out.append(pad(indent)).append("}\n");
    }

    private void breakStatement(BreakStatement b, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        if (b.firstChildOfType(Identifier.class) != null) {
            throw unsupported(b, "labelled break statements are not supported");
        }
        BreakTarget target = ctx.breakTargets.peek();
        if (target == null) {
            throw unsupported(b, "a 'break' statement outside of a loop or switch of the same code block "
                + "would jump inside the generated parser and cannot be translated");
        }
        if (target.isLoop) {
            out.append(pad(indent)).append("break ").append(target.label).append(";\n");
        } else if (!target.droppedBreaks.contains(b)) {
            target.labelUsed = true;
            out.append(pad(indent)).append("break ").append(target.label).append(";\n");
        }
    }

    /**
     * Translates a classic switch statement into a {@code match}.  Case
     * labels without statements fall through to the next group and are
     * merged into one arm; a group must end with {@code break} (which is
     * dropped), {@code return}, {@code continue}, or be the last group.
     * Breaks elsewhere inside the switch become breaks out of a labelled
     * block around the match.
     */
    private void switchStatement(SwitchStatement sw, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        if (sw.isNewStyle()) {
            throw unsupported(sw, "arrow-style switch statements are not supported");
        }
        Node selectorNode = sw.getSelectorExpression();
        RExpr selector = expression(selectorNode, ctx);
        RustType st = selector.type();
        if (st != RustType.TOKEN_TYPE && st != RustType.INT && st != RustType.CHAR && st != RustType.STRING
            && st != RustType.LEXICAL_STATE) {
            throw unsupported(selectorNode, "switch on " + st.withArticle() + " is not supported");
        }
        BreakTarget target = new BreakTarget(false, ctx.newSwitchLabel());
        ctx.breakTargets.push(target);
        StringBuilder arms = new StringBuilder();
        List<String> pendingLabels = new ArrayList<>();
        boolean sawDefault = false;
        List<ClassicCaseStatement> cases = sw.childrenOfType(ClassicCaseStatement.class);
        for (int ci = 0; ci < cases.size(); ci++) {
            ClassicCaseStatement cs = cases.get(ci);
            ClassicSwitchLabel label = cs.firstChildOfType(ClassicSwitchLabel.class);
            if (label == null) {
                throw unsupported(cs, "unexpected switch case shape");
            }
            List<Node> labelExprs = nodeChildren(label);
            if (labelExprs.isEmpty()) {
                pendingLabels.add("_");
                sawDefault = true;
            } else {
                for (Node labelExpr : labelExprs) {
                    pendingLabels.add(caseLabel(labelExpr, st, ctx));
                }
            }
            List<Node> body = new ArrayList<>();
            for (Node child : cs) {
                if (child != label && !(child instanceof Token)) {
                    body.add(child);
                }
            }
            boolean last = ci == cases.size() - 1;
            if (body.isEmpty() && !last) {
                continue; // falls through to the next group: merge the labels
            }
            Node tail = body.isEmpty() ? null : body.get(body.size() - 1);
            if (tail instanceof BreakStatement && tail.firstChildOfType(Identifier.class) == null) {
                target.droppedBreaks.add(tail);
            } else if (!last && !(tail instanceof ReturnStatement) && !(tail instanceof ContinueStatement)) {
                throw unsupported(cs, "a switch case that falls through into the next case is not supported");
            }
            String pattern = pendingLabels.contains("_") ? "_" : String.join(" | ", pendingLabels);
            pendingLabels.clear();
            arms.append(pad(indent + 4)).append(pattern).append(" => {\n");
            ctx.pushScope();
            statements(body, ctx, arms, indent + 8);
            ctx.popScope();
            arms.append(pad(indent + 4)).append("}\n");
        }
        if (!pendingLabels.isEmpty()) {
            throw unsupported(sw, "unexpected switch shape");
        }
        if (!sawDefault) {
            arms.append(pad(indent + 4)).append("_ => {}\n");
        }
        ctx.breakTargets.pop();
        int matchIndent = target.labelUsed ? indent + 4 : indent;
        if (target.labelUsed) {
            out.append(pad(indent)).append(target.label).append(": {\n");
        }
        out.append(pad(matchIndent)).append("match ").append(selector.code()).append(" {\n");
        // Re-indent the arms if they are nested in a labelled block.
        String armText = arms.toString();
        if (target.labelUsed) {
            armText = armText.replaceAll("(?m)^", "    ");
        }
        out.append(armText);
        out.append(pad(matchIndent)).append("}\n");
        if (target.labelUsed) {
            out.append(pad(indent)).append("}\n");
        }
    }

    private String caseLabel(Node labelExpr, RustType selectorType, Ctx ctx) throws RustTranslationException {
        if (selectorType == RustType.TOKEN_TYPE) {
            if (labelExpr instanceof Name n) {
                List<String> parts = nameParts(n);
                String name = parts.get(parts.size() - 1);
                if ((parts.size() == 1 || (parts.size() == 2 && parts.get(0).equals("TokenType")))
                    && symbols.isTokenType(name)) {
                    return "TokenType::" + name;
                }
            }
            throw unsupported(labelExpr, "'" + oneLine(labelExpr) + "' is not a token type of this grammar");
        }
        if (selectorType == RustType.LEXICAL_STATE) {
            if (labelExpr instanceof Name n) {
                List<String> parts = nameParts(n);
                String name = parts.get(parts.size() - 1);
                if (symbols.isLexicalState(name)) {
                    return "LexicalState::" + name;
                }
            }
            throw unsupported(labelExpr, "'" + oneLine(labelExpr) + "' is not a lexical state of this grammar");
        }
        if (!(labelExpr instanceof LiteralExpression)) {
            throw unsupported(labelExpr, "only literal case labels are supported");
        }
        RExpr literal = expression(labelExpr, ctx);
        if (literal.type() != selectorType) {
            throw unsupported(labelExpr, "the case label type " + literal.type().describe()
                + " does not match the switch type " + selectorType.describe());
        }
        return literal.code();
    }

    private void returnStatement(ReturnStatement r, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        if (ctx.method == null) {
            throw unsupported(r, "a 'return' statement in a grammar code block leaves the generated method; "
                + "only a 'return' at the very end of a production can be translated (it is dropped, "
                + "because Rust productions do not return values)");
        }
        List<Node> kids = nodeChildren(r);
        if (kids.isEmpty()) {
            if (ctx.method.returnType != RustType.VOID) {
                throw unsupported(r, "a 'return' without a value in a non-void method");
            }
            out.append(pad(indent)).append("return;\n");
            return;
        }
        RExpr value = convert(expression(kids.get(0), ctx), ctx.method.returnType, kids.get(0), ctx);
        out.append(pad(indent)).append("return ").append(value.code()).append(";\n");
    }

    private void assertStatement(AssertStatement a, Ctx ctx, StringBuilder out, int indent)
            throws RustTranslationException {
        List<Node> kids = nodeChildren(a);
        RExpr condition = convert(expression(kids.get(0), ctx), RustType.BOOL, kids.get(0), ctx);
        out.append(pad(indent)).append("debug_assert!(").append(condition.code());
        if (kids.size() > 1) {
            RExpr message = expression(kids.get(1), ctx);
            switch (message.type()) {
                case STRING, INT, LONG, CHAR, BOOL, TOKEN_TYPE ->
                    out.append(", \"{}\", ").append(message.code());
                default -> throw unsupported(kids.get(1), "an assertion message of type "
                    + message.type().describe() + " is not supported");
            }
        }
        out.append(");\n");
    }

    // =====================================================================
    // Expressions
    // =====================================================================

    /** Translates a Java expression. */
    RExpr expression(Node e, Ctx ctx) throws RustTranslationException {
        if (e instanceof LiteralExpression) {
            return literal(e);
        }
        if (e instanceof Name n) {
            return nameExpression(n, ctx);
        }
        if (e instanceof Parentheses) {
            List<Node> kids = nodeChildren(e);
            if (kids.size() != 1) {
                throw unsupported(e, "unexpected parenthesized expression shape");
            }
            RExpr inner = expression(kids.get(0), ctx);
            return inner.atomic() ? inner
                : new RExpr("(" + inner.code() + ")", inner.type(), true, inner.effects(), inner.place());
        }
        if (e instanceof MethodCall mc) {
            return methodCall(mc, ctx);
        }
        if (e instanceof DotName dn) {
            return dotName(dn, ctx);
        }
        if (e instanceof ConditionalOrExpression || e instanceof ConditionalAndExpression
            || e instanceof InclusiveOrExpression || e instanceof ExclusiveOrExpression
            || e instanceof AndExpression || e instanceof EqualityExpression
            || e instanceof RelationalExpression || e instanceof ShiftExpression
            || e instanceof AdditiveExpression || e instanceof MultiplicativeExpression) {
            return binary(e, ctx);
        }
        if (e instanceof InstanceOfExpression) {
            return instanceOf(e, ctx);
        }
        if (e instanceof UnaryExpression || e instanceof UnaryExpressionNotPlusMinus) {
            return unary(e, ctx);
        }
        if (e instanceof PreIncrementExpression || e instanceof PreDecrementExpression) {
            return incrementValue(e, true, ctx);
        }
        if (e instanceof PostfixExpression) {
            return incrementValue(e, false, ctx);
        }
        if (e instanceof TernaryExpression) {
            return ternary(e, ctx);
        }
        if (e instanceof AssignmentExpression ae) {
            StringBuilder sb = new StringBuilder();
            assignmentStatement(ae, ctx, sb, 0);
            RExpr target = assignable(ae.get(0), ctx);
            String statements = sb.toString().replace("\n", " ").trim();
            return new RExpr("{ " + statements + " " + target.code() + " }", target.type(), true, true, Place.RVALUE);
        }
        if (e instanceof PrimitiveCastExpression) {
            return cast(e, ctx);
        }
        if (e instanceof StatementExpression && nodeChildren(e).size() == 1) {
            return expression(nodeChildren(e).get(0), ctx);
        }
        if (e instanceof ThisLiteral) {
            throw unsupported(e, "'this' can only be used to access fields and methods");
        }
        throw unsupported(e, "the Java expression kind " + simpleClassName(e) + " ('" + oneLine(e) + "') is not supported");
    }

    private RExpr literal(Node e) throws RustTranslationException {
        Node token = e.get(0);
        String image = token.toString();
        if (token instanceof CharacterLiteral) {
            String value = unescapeJava(image.substring(1, image.length() - 1), e);
            if (value.length() != 1) {
                throw unsupported(e, "malformed character literal " + image);
            }
            char c = value.charAt(0);
            if (Character.isSurrogate(c)) {
                throw unsupported(e, "the character literal " + image
                    + " is a UTF-16 surrogate, which is not a Rust char");
            }
            return RExpr.value(rustCharLiteral(c), RustType.CHAR, true, false);
        }
        if (token instanceof StringLiteral) {
            if (image.startsWith("\"\"\"")) {
                throw unsupported(e, "text blocks are not supported");
            }
            String value = unescapeJava(image.substring(1, image.length() - 1), e);
            return RExpr.value(rustStringLiteral(value, e), RustType.STRING, true, false);
        }
        if (image.equals("true") || image.equals("false")) {
            return RExpr.value(image, RustType.BOOL, true, false);
        }
        if (image.equals("null")) {
            return RExpr.value("null", RustType.NULL, true, false);
        }
        if (token instanceof NumericalLiteral) {
            return integerLiteral(image, e);
        }
        throw unsupported(e, "the literal " + image + " is not supported (floating point is not part of the supported subset)");
    }

    /**
     * Translates a Java integer literal.  Hexadecimal, octal and binary
     * literals denote two's complement bit patterns in Java ({@code 0xFFFFFFFF}
     * is -1), so they are emitted as the decimal value they denote.
     */
    private static RExpr integerLiteral(String image, Node at) throws RustTranslationException {
        String s = image.replace("_", "");
        boolean isLong = s.endsWith("L") || s.endsWith("l");
        if (isLong) {
            s = s.substring(0, s.length() - 1);
        }
        int radix = 10;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            radix = 16;
            s = s.substring(2);
        } else if (s.startsWith("0b") || s.startsWith("0B")) {
            radix = 2;
            s = s.substring(2);
        } else if (s.length() > 1 && s.startsWith("0")) {
            radix = 8;
            s = s.substring(1);
        }
        BigInteger value;
        try {
            value = new BigInteger(s, radix);
        } catch (NumberFormatException nfe) {
            throw unsupported(at, "the integer literal " + image + " is not supported (floating point is not part of the supported subset)");
        }
        int bits = isLong ? 64 : 32;
        RustType type = isLong ? RustType.LONG : RustType.INT;
        if (radix == 10) {
            BigInteger max = BigInteger.ONE.shiftLeft(bits - 1); // MIN_VALUE magnitude, valid only after unary minus
            if (value.compareTo(max) > 0) {
                throw unsupported(at, "the integer literal " + image + " is out of range");
            }
            return RExpr.value(value.toString(), type, true, false);
        }
        if (value.bitLength() > bits) {
            throw unsupported(at, "the integer literal " + image + " is out of range");
        }
        long signed = isLong ? value.longValue() : value.intValue();
        return signed < 0 ? RExpr.value(Long.toString(signed), type, false, false)
                          : RExpr.value(Long.toString(signed), type, true, false);
    }

    private RExpr nameExpression(Name n, Ctx ctx) throws RustTranslationException {
        List<String> parts = nameParts(n);
        if (parts.size() == 1) {
            return simpleName(parts.get(0), n, ctx);
        }
        if (parts.size() == 2) {
            String qualifier = parts.get(0), name = parts.get(1);
            if (qualifier.equals("LexicalState")) {
                if (!symbols.isLexicalState(name)) {
                    throw unsupported(n, "'" + name + "' is not a lexical state of this grammar");
                }
                return RExpr.value("LexicalState::" + name, RustType.LEXICAL_STATE, true, false);
            }
            if (qualifier.equals("TokenType")) {
                if (!symbols.isTokenType(name)) {
                    throw unsupported(n, "'" + name + "' is not a token type of this grammar");
                }
                return RExpr.value("TokenType::" + name, RustType.TOKEN_TYPE, true, false);
            }
            if (qualifier.equals("this")) {
                return fieldReference(name, n, ctx);
            }
        }
        RExpr base = simpleName(parts.get(0), n, ctx);
        for (int i = 1; i < parts.size(); i++) {
            base = fieldAccess(base, parts.get(i), n);
        }
        return base;
    }

    /** Resolves a simple identifier: locals, production locals and parameters, fields, token types. */
    private RExpr simpleName(String id, Node at, Ctx ctx) throws RustTranslationException {
        Local local = ctx.lookup(id);
        if (local == null && ctx.production != null) {
            local = symbols.productionLocals(ctx.production, ctx.inline).get(id);
            if (local == null && ctx.inline) {
                local = symbols.productionParameters(ctx.production).get(id);
            }
        }
        if (local != null) {
            if (local.unavailableReason != null) {
                throw unsupported(at, "the variable '" + id + "' is not available in Rust: " + local.unavailableReason);
            }
            return new RExpr(local.rustName, local.type, true, false, local.place);
        }
        FieldInfo field = ctx.constantOnly ? null : symbols.field(ctx.side, id);
        if (field != null) {
            return fieldReference(id, at, ctx);
        }
        if (symbols.isTokenType(id)) {
            return RExpr.value("TokenType::" + id, RustType.TOKEN_TYPE, true, false);
        }
        if (id.equals("THIS") || id.equals("thisProduction") || id.equals("THAT") || id.equals("CURRENT_NODE")) {
            throw unsupported(at, "tree nodes ('" + id + "') cannot be used by code translated to Rust: "
                + "the Rust AST is an arena whose nodes have no Java-style object API during parsing");
        }
        if (ctx.constantOnly) {
            throw unsupported(at, "a field initializer can only use constants, but it refers to '" + id + "'");
        }
        throw unsupported(at, "unknown identifier '" + id + "'"
            + (ctx.production != null && !ctx.inline
               ? " (locals of the production are not visible in lookahead routines)" : ""));
    }

    private RExpr fieldReference(String id, Node at, Ctx ctx) throws RustTranslationException {
        FieldInfo field = ctx.constantOnly ? null : symbols.field(ctx.side, id);
        if (field == null) {
            throw unsupported(at, "unknown field '" + id + "'");
        }
        if (field.unsupportedReason != null) {
            throw unsupported(at, "the field '" + id + "' is not available in Rust: " + field.unsupportedReason);
        }
        return new RExpr("self." + field.rustName, field.type, true, false, Place.FIELD);
    }

    private RExpr fieldAccess(RExpr base, String field, Node at) throws RustTranslationException {
        if (base.type() == RustType.NT_CALL && field.equals("productionName")) {
            return RExpr.value(base.operand() + ".production_name", RustType.STRING, true, base.effects());
        }
        throw unsupported(at, "the field '" + field + "' of " + base.type().withArticle() + " is not supported");
    }

    private RExpr dotName(DotName dn, Ctx ctx) throws RustTranslationException {
        List<Node> kids = new ArrayList<>();
        for (Node child : dn) kids.add(child);
        String field = kids.get(kids.size() - 1).toString();
        if (kids.get(0) instanceof ThisLiteral) {
            return fieldReference(field, dn, ctx);
        }
        RExpr base = expression(kids.get(0), ctx);
        return fieldAccess(base, field, dn);
    }

    private RExpr binary(Node e, Ctx ctx) throws RustTranslationException {
        List<Node> kids = new ArrayList<>();
        for (Node child : e) kids.add(child);
        RExpr acc = expression(kids.get(0), ctx);
        for (int i = 1; i + 1 < kids.size(); i += 2) {
            String op = kids.get(i).toString();
            RExpr rhs = expression(kids.get(i + 1), ctx);
            acc = binaryOp(op, acc, rhs, kids.get(i), ctx);
        }
        return acc;
    }

    private RExpr binaryOp(String op, RExpr a, RExpr b, Node at, Ctx ctx) throws RustTranslationException {
        boolean effects = a.effects() || b.effects();
        switch (op) {
            case "||":
            case "&&":
                requireType(a, RustType.BOOL, at, op);
                requireType(b, RustType.BOOL, at, op);
                return RExpr.value(a.operand() + " " + op + " " + b.operand(), RustType.BOOL, false, effects);
            case "==":
            case "!=":
                return equality(op, a, b, at, ctx);
            case "<":
            case "<=":
            case ">":
            case ">=": {
                if (a.type() == RustType.CHAR && b.type() == RustType.CHAR) {
                    return RExpr.value(a.operand() + " " + op + " " + b.operand(), RustType.BOOL, false, effects);
                }
                RustType t = promoted(a, b, at, op);
                return RExpr.value(promote(a, t) + " " + op + " " + promote(b, t), RustType.BOOL, false, effects);
            }
            case "+":
                if (a.type() == RustType.STRING || b.type() == RustType.STRING) {
                    throw unsupported(at, "string concatenation is not supported");
                }
                // fall through
            case "-":
            case "*":
            case "/":
            case "%": {
                RustType t = promoted(a, b, at, op);
                return RExpr.value(promote(a, t) + " " + op + " " + promote(b, t), t, false, effects);
            }
            case "&":
            case "|":
            case "^": {
                if (a.type() == RustType.BOOL && b.type() == RustType.BOOL) {
                    return RExpr.value(a.operand() + " " + op + " " + b.operand(), RustType.BOOL, false, effects);
                }
                RustType t = promoted(a, b, at, op);
                return RExpr.value(promote(a, t) + " " + op + " " + promote(b, t), t, false, effects);
            }
            case "<<":
            case ">>":
            case ">>>": {
                if (!a.type().isNumeric() || !b.type().isNumeric()) {
                    throw unsupported(at, "the operator " + op + " needs integer operands");
                }
                RustType t = a.type() == RustType.LONG ? RustType.LONG : RustType.INT;
                RExpr left = new RExpr(promote(a, t), t, true, a.effects(), Place.RVALUE);
                return RExpr.value(shiftCode(op, left, b), t, true, effects);
            }
            default:
                throw unsupported(at, "the operator " + op + " is not supported");
        }
    }

    /**
     * Java masks the shift distance (to 5 bits for int, 6 for long) instead
     * of overflowing; Rust's {@code wrapping_shl}/{@code wrapping_shr} mask
     * the same way.  {@code >>>} shifts the unsigned reinterpretation.
     */
    private static String shiftCode(String op, RExpr left, RExpr distance) {
        String d = "(" + distance.operand() + " as u32)";
        String rustType = left.type() == RustType.LONG ? "i64" : "i32";
        String unsignedType = left.type() == RustType.LONG ? "u64" : "u32";
        String receiver = typedOperand(left, rustType);
        return switch (op) {
            case "<<" -> receiver + ".wrapping_shl" + d;
            case ">>" -> receiver + ".wrapping_shr" + d;
            default -> "((" + left.operand() + " as " + unsignedType + ").wrapping_shr" + d + " as " + rustType + ")";
        };
    }

    /**
     * @return the operand, with an integer literal given an explicit type
     *         suffix: Rust cannot call a method on an integer literal whose
     *         type is not yet inferred (error E0689)
     */
    private static String typedOperand(RExpr e, String rustType) {
        if (e.code().matches("-?[0-9]+")) {
            return "(" + e.code() + rustType + ")";
        }
        return e.operand();
    }

    private static void requireType(RExpr e, RustType type, Node at, String op) throws RustTranslationException {
        if (e.type() != type) {
            throw unsupported(at, "the operator " + op + " needs " + type.describe() + " operands, not "
                + e.type().describe());
        }
    }

    /** Java binary numeric promotion: long if either operand is long, else int. */
    private static RustType promoted(RExpr a, RExpr b, Node at, String op) throws RustTranslationException {
        if (!a.type().isNumeric() || !b.type().isNumeric()) {
            throw unsupported(at, "the operator " + op + " needs numeric operands, not "
                + a.type().describe() + " and " + b.type().describe());
        }
        return a.type() == RustType.LONG || b.type() == RustType.LONG ? RustType.LONG : RustType.INT;
    }

    private static String promote(RExpr e, RustType target) {
        if (e.type() == target) {
            return e.operand();
        }
        return "(" + e.operand() + " as " + target.rust + ")";
    }

    private RExpr equality(String op, RExpr a, RExpr b, Node at, Ctx ctx) throws RustTranslationException {
        boolean effects = a.effects() || b.effects();
        RustType ta = a.type(), tb = b.type();
        if (ta == RustType.NULL || tb == RustType.NULL) {
            RExpr other = ta == RustType.NULL ? b : a;
            boolean eq = op.equals("==");
            switch (other.type()) {
                case NULL:
                    return RExpr.value(eq ? "true" : "false", RustType.BOOL, true, false);
                case OPT_TOKEN:
                    return RExpr.value(other.operand() + (eq ? ".is_none()" : ".is_some()"), RustType.BOOL, true, effects);
                case TOKEN_TYPE:
                    return RExpr.value(other.operand() + " " + op + " TokenType::DUMMY", RustType.BOOL, false, effects);
                default:
                    throw unsupported(at, "comparing " + other.type().withArticle() + " with null is not supported");
            }
        }
        if (ta.isNumeric() && tb.isNumeric()) {
            if (ta == RustType.CHAR && tb == RustType.CHAR) {
                return RExpr.value(a.operand() + " " + op + " " + b.operand(), RustType.BOOL, false, effects);
            }
            RustType t = promoted(a, b, at, op);
            return RExpr.value(promote(a, t) + " " + op + " " + promote(b, t), RustType.BOOL, false, effects);
        }
        if (ta == RustType.OPT_TOKEN || tb == RustType.OPT_TOKEN) {
            throw unsupported(at, "comparing matchedToken with another token is not supported");
        }
        if (ta == tb && (ta == RustType.BOOL || ta == RustType.TOKEN_TYPE || ta == RustType.LEXICAL_STATE
                         || ta == RustType.STRING || ta == RustType.TOKEN)) {
            String left = ta == RustType.TOKEN && a.place() == Place.REF_PARAM ? "(*" + a.code() + ")" : a.operand();
            String right = tb == RustType.TOKEN && b.place() == Place.REF_PARAM ? "(*" + b.code() + ")" : b.operand();
            if (ta != RustType.BOOL && a.place() == Place.FIELD && b.effects()) {
                // For these types Rust's == is PartialEq::eq(&a, &b), which
                // borrows the field while the right operand is evaluated; a
                // right operand that calls a '&mut self' method then fails
                // to compile (E0502).  Copying the field first (all these
                // types are Copy) avoids the borrow and keeps Java's order:
                // the field is read before the right operand runs, which may
                // change it.  (bool, int, long and char are compared with
                // Rust's built-in operators, which copy the left operand
                // first, so they need no temporary.)
                String temp = ctx.newTemp();
                return RExpr.value("{ let " + temp + " = " + a.code() + "; " + temp + " " + op + " " + right + " }",
                                   RustType.BOOL, true, effects);
            }
            return RExpr.value(left + " " + op + " " + right, RustType.BOOL, false, effects);
        }
        throw unsupported(at, "cannot compare " + ta.withArticle() + " with " + tb.withArticle());
    }

    private RExpr unary(Node e, Ctx ctx) throws RustTranslationException {
        List<Node> kids = new ArrayList<>();
        for (Node child : e) kids.add(child);
        if (kids.size() != 2 || !(kids.get(0) instanceof Operator)) {
            throw unsupported(e, "unexpected unary expression shape");
        }
        String op = kids.get(0).toString();
        RExpr operand = expression(kids.get(1), ctx);
        switch (op) {
            case "!":
                requireType(operand, RustType.BOOL, e, op);
                return RExpr.value("!" + operand.operand(), RustType.BOOL, false, operand.effects());
            case "-":
            case "+":
            case "~": {
                if (!operand.type().isNumeric()) {
                    throw unsupported(e, "the operator " + op + " needs a numeric operand");
                }
                RustType t = operand.type() == RustType.LONG ? RustType.LONG : RustType.INT;
                String value = promote(operand, t);
                if (op.equals("+")) {
                    return RExpr.value(value, t, operand.type() == t && operand.atomic(), operand.effects());
                }
                return RExpr.value((op.equals("-") ? "-" : "!") + value, t, false, operand.effects());
            }
            default:
                throw unsupported(e, "the unary operator " + op + " is not supported");
        }
    }

    private RExpr incrementValue(Node e, boolean prefix, Ctx ctx) throws RustTranslationException {
        boolean increment = isIncrement(e);
        RExpr target = assignable(incrementOperand(e), ctx);
        requireIntegral(target, e);
        String op = increment ? " += 1; " : " -= 1; ";
        if (prefix) {
            return RExpr.value("{ " + target.code() + op + target.code() + " }", target.type(), true, true);
        }
        String temp = ctx.newTemp();
        return RExpr.value("{ let " + temp + " = " + target.code() + "; " + target.code() + op + temp + " }",
                           target.type(), true, true);
    }

    private RExpr ternary(Node e, Ctx ctx) throws RustTranslationException {
        List<Node> kids = nodeChildren(e);
        if (kids.size() != 3) {
            throw unsupported(e, "unexpected conditional expression shape");
        }
        RExpr condition = convert(expression(kids.get(0), ctx), RustType.BOOL, kids.get(0), ctx);
        RExpr a = expression(kids.get(1), ctx);
        RExpr b = expression(kids.get(2), ctx);
        RustType t;
        if (a.type() == b.type()) {
            t = a.type();
        } else if (a.type().isNumeric() && b.type().isNumeric()) {
            t = promoted(a, b, e, "?:");
        } else if (a.type() == RustType.NULL) {
            t = b.type();
        } else if (b.type() == RustType.NULL) {
            t = a.type();
        } else {
            throw unsupported(e, "the branches of the conditional expression have different types ("
                + a.type().describe() + " and " + b.type().describe() + ")");
        }
        String ac = a.type() == t ? a.code() : convertOrPromote(a, t, kids.get(1), ctx);
        String bc = b.type() == t ? b.code() : convertOrPromote(b, t, kids.get(2), ctx);
        return RExpr.value("if " + condition.code() + " { " + ac + " } else { " + bc + " }", t, false,
                           condition.effects() || a.effects() || b.effects());
    }

    private String convertOrPromote(RExpr e, RustType t, Node at, Ctx ctx) throws RustTranslationException {
        if (e.type().isNumeric() && t.isNumeric()) {
            return promote(e, t);
        }
        return convert(e, t, at, ctx).code();
    }

    private RExpr cast(Node e, Ctx ctx) throws RustTranslationException {
        PrimitiveType typeNode = e.firstChildOfType(PrimitiveType.class);
        List<Node> kids = nodeChildren(e);
        Node operandNode = kids.get(kids.size() - 1);
        if (typeNode == null || operandNode == typeNode) {
            throw unsupported(e, "unexpected cast expression shape");
        }
        RustType target = typeOf(typeNode);
        RExpr operand = expression(operandNode, ctx);
        RustType source = operand.type();
        if (source == target) {
            return operand;
        }
        if ((target == RustType.INT || target == RustType.LONG) && source.isNumeric()) {
            return RExpr.value(operand.operand() + " as " + target.rust, target, false, operand.effects());
        }
        throw unsupported(e, "the cast from " + source.describe() + " to " + target.describe() + " is not supported");
    }

    private RExpr instanceOf(Node e, Ctx ctx) throws RustTranslationException {
        List<Node> kids = nodeChildren(e);
        if (kids.size() != 2 || !(kids.get(1) instanceof ObjectType)) {
            throw unsupported(e, "only 'expression instanceof ClassName' is supported (no patterns)");
        }
        RExpr value = expression(kids.get(0), ctx);
        if (value.type() != RustType.TOKEN && value.type() != RustType.OPT_TOKEN) {
            throw unsupported(e, "instanceof is only supported on tokens, not on " + value.type().describe());
        }
        String full = dottedName(kids.get(1));
        int dot = full.lastIndexOf('.');
        String simple = full.substring(dot + 1);
        String qualifier = dot < 0 ? "" : full.substring(0, dot);
        if (!qualifier.isEmpty() && !qualifier.equals(symbols.nodePackage()) && !qualifier.equals(symbols.parserPackage())) {
            throw unsupported(kids.get(1), "'" + full + "' is not a token class of this grammar");
        }
        String test;
        if (symbols.isBaseTokenClass(simple) || simple.equals("Token")) {
            test = null;
        } else {
            List<String> types = symbols.tokenTypesOfClass(simple);
            if (types == null) {
                throw unsupported(kids.get(1), "'" + simple + "' is not a token class of this grammar "
                    + "(instanceof is only supported for token classes)");
            }
            test = types.isEmpty() ? "false"
                 : "matches!(%s.kind, " + String.join(" | ", types.stream().map(t -> "TokenType::" + t).toList()) + ")";
        }
        if (value.type() == RustType.OPT_TOKEN) {
            String code = test == null ? value.operand() + ".is_some()"
                : value.operand() + ".map_or(false, |__tok| " + String.format(test, "__tok") + ")";
            return RExpr.value(code, RustType.BOOL, true, value.effects());
        }
        String code = test == null ? "true" : String.format(test, tokenReceiver(value, e, ctx));
        if (test == null && value.effects()) {
            code = "{ let _ = " + value.code() + "; true }";
        }
        return RExpr.value(code, RustType.BOOL, true, value.effects());
    }

    // =====================================================================
    // Method calls
    // =====================================================================

    private RExpr methodCall(MethodCall mc, Ctx ctx) throws RustTranslationException {
        Node callee = mc.get(0);
        InvocationArguments argsNode = mc.firstChildOfType(InvocationArguments.class);
        if (argsNode == null) {
            throw unsupported(mc, "unexpected method call shape");
        }
        List<Node> args = new ArrayList<>(argsNode.getArgs());
        if (ctx.constantOnly) {
            throw unsupported(mc, "a field initializer can only use constants, but it calls '" + oneLine(callee) + "'");
        }
        if (callee instanceof Name n) {
            List<String> parts = nameParts(n);
            String method = parts.get(parts.size() - 1);
            List<String> qualifier = parts.subList(0, parts.size() - 1);
            if (qualifier.isEmpty() || (qualifier.size() == 1 && qualifier.get(0).equals("this"))) {
                return unqualifiedCall(method, args, mc, ctx);
            }
            if (qualifier.size() == 1 && qualifier.get(0).equals("Character")) {
                return characterCall(method, args, mc, ctx);
            }
            RExpr receiver = simpleName(qualifier.get(0), n, ctx);
            for (int i = 1; i < qualifier.size(); i++) {
                receiver = fieldAccess(receiver, qualifier.get(i), n);
            }
            return instanceCall(receiver, method, args, mc, ctx);
        }
        if (callee instanceof DotName dn) {
            List<Node> kids = new ArrayList<>();
            for (Node child : dn) kids.add(child);
            String method = kids.get(kids.size() - 1).toString();
            if (kids.get(0) instanceof ThisLiteral) {
                return unqualifiedCall(method, args, mc, ctx);
            }
            RExpr receiver = expression(kids.get(0), ctx);
            return instanceCall(receiver, method, args, mc, ctx);
        }
        throw unsupported(mc, "unsupported method call '" + oneLine(mc) + "'");
    }

    /** Translates the arguments of a call, converting each to the parameter type. */
    private List<RExpr> arguments(List<Node> args, List<RustType> types, Ctx ctx) throws RustTranslationException {
        List<RExpr> result = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            RExpr value = expression(args.get(i), ctx);
            result.add(types == null ? value : convert(value, types.get(i), args.get(i), ctx));
        }
        return result;
    }

    private static void requireArity(List<Node> args, int count, String method, Node at) throws RustTranslationException {
        if (args.size() != count) {
            throw unsupported(at, method + "() takes " + count + " argument" + (count == 1 ? "" : "s")
                + " in the supported API, not " + args.size());
        }
    }

    /**
     * Emits a call on {@code self}.  Arguments with side effects are
     * evaluated into temporaries first (in Java's left-to-right order),
     * because Rust borrows the receiver before evaluating the arguments.
     * Arguments given as {@code &mut Token} references are created last, at
     * the call, so that other arguments may still read the same token.
     *
     * @param prefix  {@code "self."} or {@code "self.lexer."}
     * @param name    the Rust method name
     * @param args    the converted argument codes, in order
     * @param mutRefs which arguments are {@code &mut Token} references
     */
    private static String selfCall(String prefix, String name, List<String> args, List<Boolean> mutRefs,
                                   List<RExpr> argExprs, Ctx ctx) {
        boolean hasMutRef = mutRefs.contains(Boolean.TRUE);
        boolean hoist = false;
        for (int i = 0; i < args.size(); i++) {
            RExpr arg = argExprs.get(i);
            if (mutRefs.get(i)) continue;
            if (arg.effects() || (hasMutRef && !isLiteralCode(args.get(i)))) {
                hoist = true;
            }
        }
        if (!hoist) {
            return prefix + name + "(" + String.join(", ", args) + ")";
        }
        StringBuilder sb = new StringBuilder("{ ");
        List<String> finalArgs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (mutRefs.get(i)) {
                finalArgs.add(args.get(i));
            } else {
                String temp = ctx.newTemp();
                sb.append("let ").append(temp).append(" = ").append(args.get(i)).append("; ");
                finalArgs.add(temp);
            }
        }
        sb.append(prefix).append(name).append("(").append(String.join(", ", finalArgs)).append(") }");
        return sb.toString();
    }

    private static boolean isLiteralCode(String code) {
        return code.matches("-?[0-9]+|true|false|'.*'|TokenType::\\w+|LexicalState::\\w+");
    }

    private RExpr unqualifiedCall(String method, List<Node> args, MethodCall mc, Ctx ctx)
            throws RustTranslationException {
        // User methods shadow nothing in Java (the API methods are inherited
        // members), but an injected method with an API name would be an
        // overload; look up injected methods first so that such a grammar is
        // diagnosed below rather than silently calling the API.
        List<MethodInfo> injected = symbols.methods(ctx.side, method);
        if (!injected.isEmpty()) {
            return injectedCall(injected, method, args, mc, ctx);
        }
        if (ctx.side == Side.PARSER) {
            switch (method) {
                case "getToken": {
                    requireArity(args, 1, method, mc);
                    RExpr n = arguments(args, List.of(RustType.INT), ctx).get(0);
                    return new RExpr(selfCall("self.", "get_token", List.of(n.code()), List.of(false), List.of(n), ctx),
                                     RustType.TOKEN, true, true, Place.RVALUE);
                }
                case "getTokenType": {
                    requireArity(args, 1, method, mc);
                    RExpr n = arguments(args, List.of(RustType.INT), ctx).get(0);
                    return RExpr.value(selfCall("self.", "get_token_type", List.of(n.code()), List.of(false), List.of(n), ctx),
                                       RustType.TOKEN_TYPE, true, true);
                }
                case "checkNextTokenType": {
                    requireArity(args, 1, method, mc);
                    RExpr t = arguments(args, List.of(RustType.TOKEN_TYPE), ctx).get(0);
                    return RExpr.value(selfCall("self.", "check_next_token_type", List.of(t.code()), List.of(false), List.of(t), ctx),
                                       RustType.BOOL, true, true);
                }
                case "stackIteratorBackward":
                case "stackIteratorForward":
                    requireArity(args, 0, method, mc);
                    return RExpr.value("self." + (method.equals("stackIteratorBackward")
                        ? "stack_iterator_backward()" : "stack_iterator_forward()"), RustType.STACK_ITER, true, false);
                case "isInProduction": {
                    if (args.isEmpty()) {
                        throw unsupported(mc, "isInProduction() needs at least one production name");
                    }
                    List<String> names = new ArrayList<>();
                    for (Node arg : args) {
                        RExpr name = expression(arg, ctx);
                        if (name.type() != RustType.STRING) {
                            throw unsupported(arg, "isInProduction() takes production names");
                        }
                        names.add(name.code());
                    }
                    return RExpr.value("self.is_in_production(&[" + String.join(", ", names) + "])",
                                       RustType.BOOL, true, false);
                }
                default:
                    break;
            }
        } else {
            switch (method) {
                case "charAt": {
                    requireArity(args, 1, method, mc);
                    RExpr i = arguments(args, List.of(RustType.INT), ctx).get(0);
                    return RExpr.value(selfCall("self.", "char_at", List.of(i.code()), List.of(false), List.of(i), ctx),
                                       RustType.CHAR, true, i.effects());
                }
                case "switchTo": {
                    requireArity(args, 1, method, mc);
                    RExpr s = expression(args.get(0), ctx);
                    if (s.type() != RustType.LEXICAL_STATE) {
                        throw unsupported(args.get(0), "switchTo() takes a LexicalState");
                    }
                    return RExpr.value(selfCall("self.", "switch_to", List.of(s.code()), List.of(false), List.of(s), ctx),
                                       RustType.BOOL, true, true);
                }
                default:
                    break;
            }
        }
        throw unsupported(mc, "the method '" + method + "' is not part of the API supported by the Rust target"
            + " and is not injected with INJECT " + (ctx.side == Side.PARSER ? "PARSER_CLASS" : "LEXER_CLASS"));
    }

    private RExpr injectedCall(List<MethodInfo> candidates, String method, List<Node> args, MethodCall mc, Ctx ctx)
            throws RustTranslationException {
        if (candidates.size() > 1) {
            throw unsupported(mc, "the injected method '" + method + "' is overloaded; Rust has no overloading");
        }
        MethodInfo m = candidates.get(0);
        if (m.params.size() != args.size()) {
            throw unsupported(mc, "the injected method '" + method + "' takes " + m.params.size()
                + " arguments, not " + args.size());
        }
        if (m.unsupportedReason != null) {
            throw unsupported(mc, "the injected method '" + method + "' could not be translated ("
                + m.unsupportedReason + ")");
        }
        ctx.calledMethods.add(m);
        List<String> codes = new ArrayList<>();
        List<Boolean> mutRefs = new ArrayList<>();
        List<RExpr> exprs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            ParamInfo p = m.params.get(i);
            RExpr value = expression(args.get(i), ctx);
            if (p.tokenRef) {
                if (value.type() != RustType.TOKEN && value.type() != RustType.OPT_TOKEN) {
                    throw unsupported(args.get(i), "the argument must be a token");
                }
                if (value.place() != Place.LOCAL && value.place() != Place.REF_PARAM
                    && value.place() != Place.OPTION_LOCAL) {
                    // A Token parameter is a '&mut Token' (the method may
                    // change the token in place, as lexical actions do), and
                    // Rust cannot lend a token that is not held in a variable.
                    throw unsupported(args.get(i), "the injected method '" + method + "' takes a Token, which the "
                        + "Rust target passes as a '&mut Token', so the argument must be a local variable, a Token "
                        + "parameter or matchedToken; assign the token to a local variable first (for example "
                        + "'Token next = getToken(1);' in a code block) and pass the variable");
                }
                codes.add(tokenMutRef(value, args.get(i), ctx));
                mutRefs.add(true);
            } else {
                value = convert(value, p.type, args.get(i), ctx);
                codes.add(value.code());
                mutRefs.add(false);
            }
            exprs.add(value);
        }
        return RExpr.value(selfCall("self.", m.rustName, codes, mutRefs, exprs, ctx), m.returnType, true, true);
    }

    private RExpr characterCall(String method, List<Node> args, MethodCall mc, Ctx ctx)
            throws RustTranslationException {
        String helper = symbols.characterPredicate(method);
        if (helper == null) {
            throw unsupported(mc, "Character." + method + "() is not supported by the Rust target");
        }
        requireArity(args, 1, "Character." + method, mc);
        RExpr c = expression(args.get(0), ctx);
        if (!c.type().isNumeric() || c.type() == RustType.LONG) {
            throw unsupported(args.get(0), "Character." + method + "() takes a char or an int code point");
        }
        String path = ctx.side == Side.LEXER ? "" : "crate::lexer::";
        return RExpr.value(path + helper + "(" + c.operand() + " as u32)", RustType.BOOL, true, c.effects());
    }

    private RExpr instanceCall(RExpr receiver, String method, List<Node> args, MethodCall mc, Ctx ctx)
            throws RustTranslationException {
        switch (receiver.type()) {
            case TOKEN:
            case OPT_TOKEN:
                return tokenCall(receiver, method, args, mc, ctx);
            case STACK_ITER: {
                if (receiver.place() != Place.LOCAL) {
                    throw unsupported(mc, "stack iterators must be held in local variables");
                }
                requireArity(args, 0, method, mc);
                return switch (method) {
                    case "hasNext" -> RExpr.value(receiver.code() + ".has_next(self)", RustType.BOOL, true, false);
                    case "hasPrevious" -> RExpr.value(receiver.code() + ".has_previous(self)", RustType.BOOL, true, false);
                    case "next" -> RExpr.value(receiver.code() + ".next(self)", RustType.NT_CALL, true, true);
                    case "previous" -> RExpr.value(receiver.code() + ".previous(self)", RustType.NT_CALL, true, true);
                    default -> throw unsupported(mc, "the iterator method '" + method + "' is not supported");
                };
            }
            case STRING: {
                if (method.equals("equals") || method.equals("contentEquals")) {
                    requireArity(args, 1, method, mc);
                    RExpr other = expression(args.get(0), ctx);
                    if (other.type() != RustType.STRING) {
                        throw unsupported(args.get(0), "only Strings can be compared with String." + method + "()");
                    }
                    return RExpr.value(receiver.operand() + " == " + other.operand(), RustType.BOOL, false,
                                       receiver.effects() || other.effects());
                }
                if (method.equals("isEmpty")) {
                    requireArity(args, 0, method, mc);
                    return RExpr.value(receiver.operand() + ".is_empty()", RustType.BOOL, true, receiver.effects());
                }
                break;
            }
            case TOKEN_TYPE: {
                if (method.equals("ordinal")) {
                    requireArity(args, 0, method, mc);
                    return RExpr.value(receiver.operand() + ".ordinal() as i32", RustType.INT, false, receiver.effects());
                }
                if (method.equals("equals")) {
                    requireArity(args, 1, method, mc);
                    return equality("==", receiver, expression(args.get(0), ctx), mc, ctx);
                }
                break;
            }
            default:
                break;
        }
        throw unsupported(mc, "the method '" + method + "' of " + receiver.type().describe()
            + " is not supported by the Rust target");
    }

    private RExpr tokenCall(RExpr receiver, String method, List<Node> args, MethodCall mc, Ctx ctx)
            throws RustTranslationException {
        boolean lexer = ctx.side == Side.LEXER;
        switch (method) {
            case "getType":
                requireArity(args, 0, method, mc);
                return RExpr.value(tokenReceiver(receiver, mc, ctx) + ".kind", RustType.TOKEN_TYPE, true, receiver.effects());
            case "getBeginOffset":
            case "getEndOffset":
            case "length":
                requireArity(args, 0, method, mc);
                String rustMethod = switch (method) {
                    case "getBeginOffset" -> "get_begin_offset";
                    case "getEndOffset" -> "get_end_offset";
                    default -> "length";
                };
                return RExpr.value(tokenReceiver(receiver, mc, ctx) + "." + rustMethod + "()", RustType.INT, true,
                                   receiver.effects());
            case "isUnparsed":
                requireArity(args, 0, method, mc);
                return RExpr.value(tokenReceiver(receiver, mc, ctx) + ".is_unparsed", RustType.BOOL, true, receiver.effects());
            case "charAt": {
                requireArity(args, 1, method, mc);
                RExpr index = arguments(args, List.of(RustType.INT), ctx).get(0);
                if (receiver.place() == Place.FIELD && index.effects()) {
                    // The helper takes the token by reference, and the call
                    // below evaluates an index with effects first (selfCall
                    // moves it to a temporary).  Java reads the receiver
                    // before the index, which may assign the field, so the
                    // field is copied (Token is Copy) before the index runs.
                    String temp = ctx.newTemp();
                    RExpr copy = new RExpr(temp, RustType.TOKEN, true, false, Place.LOCAL);
                    return RExpr.value("{ let " + temp + " = " + receiver.code() + "; " + selfCall(
                        lexer ? "self." : "self.lexer.", "token_char_at", List.of("&" + temp, index.code()),
                        List.of(true, false), List.of(copy, index), ctx) + " }", RustType.CHAR, true, true);
                }
                String ref = tokenRef(receiver, mc, ctx);
                return RExpr.value(selfCall(lexer ? "self." : "self.lexer.", "token_char_at",
                    List.of(ref, index.code()), List.of(true, false), List.of(receiver, index), ctx),
                    RustType.CHAR, true, receiver.effects() || index.effects());
            }
            default:
                break;
        }
        if (lexer) {
            switch (method) {
                case "truncate":
                case "setBeginOffset":
                case "setEndOffset": {
                    requireArity(args, 1, method, mc);
                    RExpr amount = arguments(args, List.of(RustType.INT), ctx).get(0);
                    String helper = switch (method) {
                        case "truncate" -> "truncate_token";
                        case "setBeginOffset" -> "set_token_begin_offset";
                        default -> "set_token_end_offset";
                    };
                    String ref = tokenMutRef(receiver, mc, ctx);
                    return RExpr.value(selfCall("self.", helper, List.of(ref, amount.code()), List.of(true, false),
                                                List.of(receiver, amount), ctx), RustType.VOID, true, true);
                }
                case "replaceType": {
                    requireArity(args, 1, method, mc);
                    RExpr type = arguments(args, List.of(RustType.TOKEN_TYPE), ctx).get(0);
                    String value = tokenValue(receiver, mc, ctx);
                    return RExpr.value(selfCall("self.", "replace_token_type", List.of(value, type.code()),
                        List.of(false, false), List.of(receiver, type), ctx), RustType.TOKEN, true,
                        receiver.effects() || type.effects());
                }
                default:
                    break;
            }
        }
        throw unsupported(mc, "the Token method '" + method + "' is not supported by the Rust target"
            + (lexer ? "" : " in parser code"));
    }
}
