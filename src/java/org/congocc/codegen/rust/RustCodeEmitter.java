package org.congocc.codegen.rust;

import java.util.*;

import org.congocc.app.AppSettings;
import org.congocc.app.Errors;
import org.congocc.codegen.java.JavaCodeInjector;
import org.congocc.core.*;
import org.congocc.core.nfa.LexicalStateData;
import org.congocc.parser.Node;
import org.congocc.parser.Token;
import org.congocc.parser.tree.*;

import static org.congocc.codegen.rust.RustJavaTranslator.*;

/**
 * Translates all the Java code embedded in a grammar for the Rust target and
 * reports, at generation time, everything that cannot be translated.
 *
 * <h2>Role</h2>
 * The Rust templates call this class (through the {@code rust*} methods of
 * {@code TemplateGlobals}) for every site where the grammar contains Java
 * code: code actions, semantic lookaheads, assertions, tree-building
 * conditions, production arguments, lexical actions and the members
 * injected with {@code INJECT PARSER_CLASS} and {@code INJECT LEXER_CLASS}.
 * The typed translation itself is done by {@link RustJavaTranslator}; this
 * class knows the grammar (token types, token classes, lexical states,
 * injected members, production locals), decides how a failure is handled,
 * and keeps the list of problems for the generated {@code FIXME.md}.
 *
 * <h2>Failure policy ("fail loud")</h2>
 * A site that cannot be translated is never silently replaced by a guess.
 * <ul>
 *   <li>If the untranslated code can change how the input is parsed or
 *       tokenized (a semantic lookahead, an assertion, a tree-building
 *       condition, a lexical action, a code action that runs during
 *       lookahead, or a code action that assigns a variable read by any of
 *       those, calls a parsing API that changes state, or transfers control),
 *       it is an <em>error</em>: it is reported with
 *       {@link Errors#addError(Node, String)} (so CongoCC exits with a
 *       non-zero status) and the generated Rust contains a
 *       {@code compile_error!} with the same message, so the crate cannot be
 *       built by accident.</li>
 *   <li>Other untranslatable code actions (for example code that only
 *       decorates AST nodes) are <em>warnings</em>: the generated Rust
 *       contains the Java code as a {@code FIXME(congocc)} comment.</li>
 *   <li>With the command line flag {@code -rust-allow-stubs}, errors are
 *       downgraded to warnings and replaced by documented stubs (a
 *       predicate becomes {@code true}, an assertion or lexical action is
 *       skipped, a tree-building condition becomes {@code true}).  This is
 *       meant for bringing up a grammar, not for production use.</li>
 * </ul>
 * Every diagnostic names the grammar location, the construct that could not
 * be translated, why, and the possible remedies: rewriting the code in the
 * supported subset, providing Rust code for the site in a raw code block
 * ({@code {R% ... %}}), or generating with {@code -rust-allow-stubs}.
 *
 * <h2>Usage</h2>
 * One instance exists per grammar (created lazily by
 * {@code TemplateGlobals}).  Templates call, for example:
 * <pre>
 *   ${globals.rustPredicate(expansion, true)}      // inline semantic lookahead
 *   ${globals.rustAction(codeBlock, 8)}            // code action, indented 8
 *   ${globals.rustParserFields()}                  // INJECT PARSER_CLASS fields
 * </pre>
 * All methods are deterministic: they depend only on the grammar, and the
 * order of the generated members is the order of their declarations.
 */
public final class RustCodeEmitter implements RustJavaTranslator.Symbols {

    // =====================================================================
    // Generated names that injected members must not use
    // =====================================================================

    /**
     * The fields and inherent methods of the generated {@code Parser} struct
     * (parser.rs.ctl), not counting the functions generated for the
     * productions ({@code parse_<Production>}, checked separately) and for
     * the lookahead routines (whose names cannot be Java identifiers).  An
     * injected member whose Rust name is one of these would not compile, so
     * it is reported as an error instead.  {@code RustCodegenTest} checks
     * that this set is exactly the set of names that the template defines.
     */
    static final Set<String> RESERVED_PARSER_NAMES = Set.of(
        // fields
        "lexer", "tokens", "lexer_pos", "pos", "scan_pos", "in_lookahead", "builder", "build_tree",
        "tokens_are_nodes", "call_stack", "lookahead_stack", "currently_parsed_production",
        "current_lookahead_production", "remaining_lookahead", "hit_failure", "passed_predicate",
        "passed_predicate_threshold", "lookahead_routine_nesting",
        // methods
        "parse", "new", "lex_next_regular", "token_at", "lex_until", "lexed_token", "reset_to", "current_base",
        "get_token", "get_token_type", "check_next_token_type", "last_consumed_end", "type_matches",
        "has_match", "next_token_matches", "next_token_in", "scan_token", "scan_token_set", "consume_token",
        "uncache_tokens", "activate_token_types", "deactivate_token_types", "push_onto_call_stack",
        "pop_call_stack", "restore_call_stack", "push_onto_lookahead_stack", "pop_lookahead_stack",
        "stack_iterator_forward", "stack_iterator_backward", "is_in_production", "make_parse_error",
        "unexpected_token_error", "fail_at", "open_node");

    /**
     * The fields and inherent methods of the generated {@code Lexer} struct
     * (lexer.rs.ctl).  The {@code fmt} method of its {@code Debug}
     * implementation is not included: an inherent method may have the name
     * of a trait method.  {@code RustCodegenTest} checks that this set is
     * exactly the set of names that the template defines.
     */
    static final Set<String> RESERVED_LEXER_NAMES = Set.of(
        // fields
        "content", "chars", "byte_offsets", "source_name", "tokens", "lexical_state", "active_token_types",
        "current_states", "next_states",
        // methods
        "new", "tokenize", "source", "token_text", "activate_token_type", "deactivate_token_type",
        "save_active_tokens", "restore_active_tokens", "switch_to", "line_column", "do_lexical_state_switch",
        "is_active", "active_snapshot", "restore_active_snapshot", "activate_types", "deactivate_types",
        "byte_offset", "make_token", "tokenize_at", "get_match_info", "offset_panic", "char_at", "token_char_at",
        "checked_offset", "set_token_begin_offset", "set_token_end_offset", "truncate_token",
        "replace_token_type", "token_lexical_actions", "balanced_delimiters");

    /**
     * Parser API methods that change the state of the parse.  A code action
     * that calls one of them and cannot be translated is an error, because
     * dropping it would change what is parsed.
     */
    private static final Set<String> STATE_CHANGING_API = Set.of(
        "pushNode", "popNode", "pokeNode", "openNodeScope", "closeNodeScope", "clearNodeScope",
        "consumeToken", "activateTokenTypes", "deactivateTokenTypes", "uncacheTokens", "fail", "switchTo",
        "reset", "setLexicalState", "skipTokens", "setBuildTree", "setTokensAreNodes", "setUnparsedTokensAreNodes");

    /** Receivers whose methods always change the state of the parse. */
    private static final Set<String> STATE_CHANGING_RECEIVERS = Set.of("token_source", "lexer", "LEXER_CLASS");

    /** The {@code java.lang.Character} predicates supported in translated code. */
    private static final List<String> CHARACTER_PREDICATES = List.of(
        "isAlphabetic", "isDigit", "isIdentifierIgnorable", "isISOControl", "isJavaIdentifierPart",
        "isJavaIdentifierStart", "isLetter", "isLetterOrDigit", "isLowerCase", "isSpaceChar", "isTitleCase",
        "isUnicodeIdentifierPart", "isUnicodeIdentifierStart", "isUpperCase", "isWhitespace");

    // =====================================================================
    // Diagnostics
    // =====================================================================

    /** The kinds of grammar sites that contain Java code. */
    enum SiteKind {
        PARSER_ACTION("code action"),
        LOOKAHEAD_ACTION("code action that runs during lookahead"),
        PREDICATE("semantic lookahead"),
        ASSERTION("assertion"),
        ASSERTION_MESSAGE("assertion message"),
        TREE_CONDITION("tree-building condition"),
        LEXER_ACTION("lexical action"),
        PRODUCTION_ARGUMENTS("argument list of a production call"),
        PRODUCTION_PARAMETERS("parameter list of a production"),
        ASSIGNMENT("assignment of a parsed element"),
        PARSER_MEMBER("INJECT PARSER_CLASS member"),
        LEXER_MEMBER("INJECT LEXER_CLASS member"),
        FEATURE("grammar feature");

        final String description;

        SiteKind(String description) {
            this.description = description;
        }
    }

    /**
     * A problem found while translating the grammar's Java code.
     *
     * @param error       true if reported as an error, false for a warning
     * @param kind        the kind of site
     * @param location    the grammar location of the untranslatable construct
     * @param javaSource  the Java code of the site (one line)
     * @param reason      why the code cannot be translated
     * @param consequence what the generated Rust code contains instead
     */
    record Diagnostic(boolean error, SiteKind kind, String location, String javaSource,
                      String reason, String consequence) {
    }

    // =====================================================================
    // State
    // =====================================================================

    private final Grammar grammar;
    private final AppSettings settings;
    private final Errors errors;
    private final boolean allowStubs;
    private final RustJavaTranslator translator;

    private boolean initialized;
    private final Set<String> tokenTypes = new HashSet<>();
    private final Set<String> lexicalStates = new HashSet<>();
    /** Token class name to the token types that are instances of it. */
    private final Map<String, List<String>> tokenTypesByClass = new HashMap<>();
    private final Map<Side, Map<String, FieldInfo>> fields = new EnumMap<>(Side.class);
    private final Map<Side, Map<String, List<MethodInfo>>> methods = new EnumMap<>(Side.class);
    /** Injected declarations that are neither fields nor methods (constructors, nested types, ...). */
    private final Map<Side, List<Node>> otherMembers = new EnumMap<>(Side.class);
    private final Map<BNFProduction, Map<String, Local>> inlineLocals = new HashMap<>();
    private final Map<BNFProduction, Map<String, Local>> lookaheadLocals = new HashMap<>();
    private final Map<BNFProduction, Map<String, Local>> parameters = new HashMap<>();
    /** Names read by code that influences parsing (see {@link #collectParseRelevantNames()}). */
    private final Set<String> parseRelevantNames = new HashSet<>();
    /**
     * Names used by the lexical actions and the injected lexer methods they
     * call: an injected lexer field with one of these names changes what the
     * lexer produces (see {@link #collectParseRelevantNames()}).
     */
    private final Set<String> lexerRelevantNames = new HashSet<>();
    /**
     * The injected methods that the generated Rust code can call: the parser
     * methods reachable from code that influences parsing, and the lexer
     * methods reachable from lexical actions (see
     * {@link #collectParseRelevantNames()}).  A method that cannot be
     * translated is an error when it is in this set; otherwise nothing in the
     * Rust parser calls it, and it is only reported as a warning.  (Lexer
     * token hooks, for example, are called by the Java lexer only.)
     */
    private final Set<MethodInfo> reachableMethods = new HashSet<>();
    /** The {@code Character} predicates referenced anywhere in the grammar's Java code. */
    private final SortedSet<String> usedCharacterPredicates = new TreeSet<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<String> reportedKeys = new HashSet<>();

    /**
     * @param grammar the grammar being generated; its settings decide whether
     *                untranslatable code is an error or a stub
     *                ({@link AppSettings#getRustAllowStubs()})
     */
    public RustCodeEmitter(Grammar grammar) {
        this.grammar = grammar;
        this.settings = grammar.getAppSettings();
        this.errors = grammar.getErrors();
        this.allowStubs = settings.getRustAllowStubs();
        this.translator = new RustJavaTranslator(this);
        for (Side side : Side.values()) {
            fields.put(side, new LinkedHashMap<>());
            methods.put(side, new LinkedHashMap<>());
            otherMembers.put(side, new ArrayList<>());
        }
    }

    /** @return the problems found so far, in the order they were found */
    List<Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    // =====================================================================
    // Initialization: symbols and injected members
    // =====================================================================

    /**
     * Collects the grammar's symbols and translates the injected members.
     * Done once, before the first site is translated, because every site
     * may refer to the members and the lexer (generated first) needs the
     * parser-side helpers too.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        LexerData lexerData = grammar.getLexerData();
        for (RegularExpression re : lexerData.getRegularExpressions()) {
            tokenTypes.add(re.getLabel());
        }
        tokenTypes.addAll(settings.getExtraTokenNames());
        tokenTypes.add("DUMMY");
        tokenTypes.add("INVALID");
        for (LexicalStateData state : lexerData.getLexicalStates()) {
            lexicalStates.add(state.getName());
        }
        collectTokenClasses();
        collectMembers(Side.PARSER);
        collectMembers(Side.LEXER);
        collectProductionSymbols();
        collectParseRelevantNames();
        translateFieldInitializers(Side.LEXER);
        translateFieldInitializers(Side.PARSER);
        translateMethods(Side.LEXER);
        translateMethods(Side.PARSER);
        collectCharacterPredicates();
        reportUnsupportedFeatures();
    }

    /**
     * Records, for every token class, the token types whose Java token
     * objects are instances of it.  This mirrors {@code Token.newToken} in
     * the Java template: a non-private named token whose generated class is
     * not the base token class gets its own class (its label, or the name
     * given with {@code #Name}); a class given with {@code TOKEN #X} is the
     * superclass of the classes of its members; {@code INJECT} can add
     * further {@code extends} clauses.
     */
    private void collectTokenClasses() {
        String base = settings.getBaseTokenClassName();
        Map<String, String> superClass = new HashMap<>();
        Map<String, String> classOfType = new LinkedHashMap<>();
        for (RegularExpression re : grammar.getLexerData().getOrderedNamedTokens()) {
            if (re.isPrivate()) {
                continue;
            }
            String cls = re.getGeneratedClassName();
            if (cls == null || cls.equals(base)) {
                continue;
            }
            classOfType.put(re.getLabel(), simpleName(cls));
            String sup = re.getGeneratedSuperClassName();
            if (sup != null) {
                superClass.put(simpleName(cls), simpleName(sup));
            }
        }
        for (String extra : settings.getExtraTokenNames()) {
            String cls = settings.getExtraTokens().get(extra);
            if (cls != null) {
                classOfType.put(extra, simpleName(cls));
            }
        }
        classOfType.put("INVALID", "InvalidToken");
        JavaCodeInjector injector = grammar.getInjector();
        for (Map.Entry<String, String> entry : classOfType.entrySet()) {
            Set<String> seen = new HashSet<>();
            String cls = entry.getValue();
            while (cls != null && !cls.equals(base) && seen.add(cls)) {
                tokenTypesByClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(entry.getKey());
                String next = superClass.get(cls);
                if (next == null && injector != null) {
                    Set<ObjectType> extendsList = injector.getExtendsList(settings.getNodePackage() + "." + cls);
                    if (extendsList != null && !extendsList.isEmpty()) {
                        next = simpleName(extendsList.iterator().next().toString());
                    }
                }
                cls = next;
            }
        }
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1).trim();
    }

    private String injectedClassName(Side side) {
        return settings.getParserPackage() + "."
            + (side == Side.PARSER ? settings.getParserClassName() : settings.getLexerClassName());
    }

    private Set<String> reservedNames(Side side) {
        return side == Side.PARSER ? RESERVED_PARSER_NAMES : RESERVED_LEXER_NAMES;
    }

    private SiteKind memberKind(Side side) {
        return side == Side.PARSER ? SiteKind.PARSER_MEMBER : SiteKind.LEXER_MEMBER;
    }

    /** Collects the fields and methods injected into the parser or lexer class, in declaration order. */
    private void collectMembers(Side side) {
        JavaCodeInjector injector = grammar.getInjector();
        if (injector == null) {
            return;
        }
        List<ClassOrInterfaceBodyDeclaration> declarations = injector.getBodyDeclarations(injectedClassName(side));
        if (declarations == null) {
            return;
        }
        for (ClassOrInterfaceBodyDeclaration declaration : declarations) {
            if (declaration instanceof FieldDeclaration fd) {
                collectField(side, fd);
            } else if (declaration instanceof MethodDeclaration md) {
                collectMethod(side, md);
            } else if (!(declaration instanceof EmptyDeclaration)) {
                otherMembers.get(side).add(declaration);
            }
        }
        rejectRustNameCollisions(side);
    }

    /**
     * Members whose Java names differ but whose Rust names are the same
     * ({@code fooBar} and {@code foo_bar}, {@code a$b} and {@code a_b})
     * cannot all be generated: none of them is, and each is reported like any
     * other member that cannot be generated (an error if parse-relevant code
     * uses it).  The overloads of one Java name are handled separately.
     */
    private void rejectRustNameCollisions(Side side) {
        Map<String, Set<String>> javaNames = new TreeMap<>();
        for (FieldInfo f : fields.get(side).values()) {
            javaNames.computeIfAbsent(f.rustName, k -> new TreeSet<>()).add(f.javaName);
        }
        for (List<MethodInfo> overloads : methods.get(side).values()) {
            for (MethodInfo m : overloads) {
                javaNames.computeIfAbsent(m.rustName, k -> new TreeSet<>()).add(m.javaName);
            }
        }
        for (Map.Entry<String, Set<String>> entry : javaNames.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String reason = "the injected members " + String.join(", ", entry.getValue().stream()
                .map(n -> "'" + n + "'").toList()) + " would all be called '" + entry.getKey() + "' in Rust; rename them";
            for (FieldInfo f : fields.get(side).values()) {
                if (f.rustName.equals(entry.getKey()) && f.unsupportedReason == null) {
                    f.unsupportedReason = reason;
                }
            }
            for (List<MethodInfo> overloads : methods.get(side).values()) {
                for (MethodInfo m : overloads) {
                    if (m.rustName.equals(entry.getKey()) && m.unsupportedReason == null) {
                        m.unsupportedReason = reason;
                        m.unsupportedNode = m.declaration;
                    }
                }
            }
        }
    }

    private static boolean hasModifier(Node declaration, String modifier) {
        for (Node child : declaration) {
            if (child instanceof KeyWord && child.toString().equals(modifier)) {
                return true;
            }
            if (child instanceof Modifiers) {
                for (Node m : child) {
                    if (m.toString().equals(modifier)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Node typeNodeOf(Node declaration) {
        for (Node child : declaration) {
            if (child instanceof PrimitiveType || child instanceof ObjectType || child instanceof ReferenceType) {
                return child;
            }
        }
        return null;
    }

    private void collectField(Side side, FieldDeclaration fd) {
        RustType type = null;
        String unsupported = null;
        Node typeNode = typeNodeOf(fd);
        try {
            if (typeNode == null) {
                throw new RustTranslationException(fd, "unexpected field declaration shape");
            }
            type = translator.typeOf(typeNode);
            // A field needs a Rust value for Java's default initialization
            // (RustType.defaultValue), so the types whose Java default is a
            // null without Rust counterpart (LexicalState, the call stack
            // types) cannot be field types.  String fields are rejected
            // below with their own reason.
            if (type == null || !type.isDeclarable() || type.defaultValue == null) {
                throw new RustTranslationException(typeNode, "fields of type '" + oneLine(typeNode)
                    + "' are not supported (supported: boolean, int, long, char, TokenType and Token)");
            }
            if (type == RustType.STRING) {
                throw new RustTranslationException(typeNode, "String fields are not supported: a Rust parser "
                    + "cannot hold strings computed at run time in a '&'static str'");
            }
            if (hasModifier(fd, "static") && !hasModifier(fd, "final")) {
                throw new RustTranslationException(fd, "mutable static fields (shared by all parser instances "
                    + "in Java) are not supported");
            }
        } catch (RustTranslationException e) {
            unsupported = e.getMessage();
            type = null;
        }
        for (VariableDeclarator vd : fd.childrenOfType(VariableDeclarator.class)) {
            Node id = vd.get(0);
            String javaName = id.toString();
            String rustName = snakeCase(javaName);
            FieldInfo info = new FieldInfo(javaName, rustName, type, vd, initializerOf(vd));
            info.unsupportedReason = unsupported;
            // A field whose Rust name is taken by the generated struct is not
            // generated.  Like any other field that cannot be generated, it
            // is reported once the parse-relevant names are known (see
            // translateMethods): an error if parse-relevant code uses it, a
            // warning if dropping it changes nothing.
            if (unsupported == null && reservedNames(side).contains(rustName)) {
                info.unsupportedReason = "its Rust name '" + rustName + "' is already used by the generated "
                    + (side == Side.PARSER ? "Parser" : "Lexer") + "; rename the field";
            }
            if (fields.get(side).containsKey(javaName)) {
                info.unsupportedReason = "the field is declared more than once";
            }
            fields.get(side).put(javaName, info);
        }
    }

    private void collectMethod(Side side, MethodDeclaration md) {
        String javaName = md.getName();
        String rustName = snakeCase(javaName);
        RustType returnType = null;
        List<ParamInfo> params = new ArrayList<>();
        String unsupported = null;
        Node unsupportedNode = md;
        try {
            if (md.firstChildOfType(TypeParameters.class) != null) {
                throw new RustTranslationException(md, "generic methods are not supported");
            }
            ReturnType rt = md.firstChildOfType(ReturnType.class);
            if (rt == null) {
                throw new RustTranslationException(md, "unexpected method declaration shape");
            }
            Node rtType = typeNodeOf(rt);
            if (rtType == null) {
                returnType = RustType.VOID;
            } else {
                returnType = translator.typeOf(rtType);
                if (returnType == null || !returnType.isDeclarable()) {
                    throw new RustTranslationException(rtType, "the return type '" + oneLine(rtType) + "' is not supported");
                }
                if (returnType == RustType.STACK_ITER || returnType == RustType.OPT_TOKEN) {
                    throw new RustTranslationException(rtType, "methods returning '" + oneLine(rtType) + "' are not supported");
                }
            }
            for (FormalParameter fp : md.getFormalParameters().getParams()) {
                Node pType = typeNodeOf(fp);
                if (pType == null || fp.toString().contains("...")) {
                    throw new RustTranslationException(fp, "variable-arity and array parameters are not supported");
                }
                RustType t = translator.typeOf(pType);
                if (t == null || !t.isDeclarable() || t == RustType.STACK_ITER) {
                    throw new RustTranslationException(pType, "parameters of type '" + oneLine(pType) + "' are not supported");
                }
                params.add(new ParamInfo(fp.getName(), snakeCase(fp.getName()), t, t == RustType.TOKEN));
            }
            if (hasModifier(md, "abstract") || hasModifier(md, "native")
                || md.firstChildOfType(CodeBlock.class) == null) {
                throw new RustTranslationException(md, "methods without a body are not supported");
            }
            if (reservedNames(side).contains(rustName)) {
                throw new RustTranslationException(md, "its Rust name '" + rustName + "' is already used by the "
                    + "generated " + (side == Side.PARSER ? "Parser" : "Lexer") + "; rename the method");
            }
            if (side == Side.PARSER && rustName.startsWith("parse_")
                && grammar.getProductionByName(rustName.substring("parse_".length())) != null) {
                throw new RustTranslationException(md, "its Rust name '" + rustName + "' is already used by the "
                    + "function generated for the production " + rustName.substring("parse_".length())
                    + "; rename the method");
            }
        } catch (RustTranslationException e) {
            unsupported = e.getMessage();
            unsupportedNode = e.getNode() == null ? md : e.getNode();
        }
        MethodInfo info = new MethodInfo(side, javaName, rustName, returnType, params, md);
        info.unsupportedReason = unsupported;
        info.unsupportedNode = unsupportedNode;
        List<MethodInfo> overloads = methods.get(side).computeIfAbsent(javaName, k -> new ArrayList<>());
        overloads.add(info);
        if (overloads.size() > 1) {
            for (MethodInfo m : overloads) {
                if (m.unsupportedReason == null) {
                    m.unsupportedReason = "the method '" + javaName + "' is overloaded, and Rust has no overloading";
                    m.unsupportedNode = m.declaration;
                }
            }
        }
    }

    /**
     * Collects, for every production, its parameters and the locals declared
     * at the top level of its code blocks (they are visible to later code of
     * the production, as in the generated Java method).
     */
    private void collectProductionSymbols() {
        for (BNFProduction production : grammar.getParserProductions()) {
            Map<String, Local> inline = new LinkedHashMap<>();
            Map<String, Local> lookahead = new LinkedHashMap<>();
            for (CodeBlock block : production.descendants(CodeBlock.class)) {
                if (block.getContainingProduction() != production) {
                    continue;
                }
                for (Node statement : block) {
                    if (statement instanceof LocalVariableDeclaration lvd) {
                        addLocals(lvd, inline);
                        if (block.isAppliesInLookahead()) {
                            addLocals(lvd, lookahead);
                        }
                    }
                }
            }
            inlineLocals.put(production, inline);
            lookaheadLocals.put(production, lookahead);
            Map<String, Local> params = new LinkedHashMap<>();
            FormalParameters formals = production.getParameterList();
            if (formals != null) {
                for (FormalParameter fp : formals.getParams()) {
                    Node typeNode = typeNodeOf(fp);
                    String reason = null;
                    RustType type = null;
                    try {
                        if (typeNode == null) {
                            throw new RustTranslationException(fp, "unexpected parameter shape");
                        }
                        type = translator.typeOf(typeNode);
                        if (type == null || !type.isDeclarable() || type == RustType.STACK_ITER) {
                            throw new RustTranslationException(typeNode, "parameters of type '" + oneLine(typeNode)
                                + "' are not supported");
                        }
                    } catch (RustTranslationException e) {
                        reason = e.getMessage();
                    }
                    params.put(fp.getName(), new Local(fp.getName(), snakeCase(fp.getName()),
                        type == null ? RustType.BOOL : type, Place.LOCAL, reason));
                }
            }
            parameters.put(production, params);
        }
    }

    private void addLocals(LocalVariableDeclaration lvd, Map<String, Local> into) {
        Node typeNode = typeNodeOf(lvd);
        RustType type = null;
        String reason = null;
        try {
            if (typeNode == null) {
                throw new RustTranslationException(lvd, "unexpected declaration shape");
            }
            type = translator.typeOf(typeNode);
        } catch (RustTranslationException e) {
            reason = e.getMessage();
        }
        for (VariableDeclarator vd : lvd.childrenOfType(VariableDeclarator.class)) {
            String javaName = vd.get(0).toString();
            String localReason = reason;
            RustType localType = type;
            if (localReason == null && localType == null) {
                // 'var': the type is that of the initializer, which is only
                // known once the declaration is translated.
                localReason = "it is declared with 'var' in another code block of the production; declare it with an explicit type";
            }
            Local previous = into.get(javaName);
            if (previous != null && previous.type != localType) {
                localReason = "it is declared more than once in the production with different types";
            }
            into.put(javaName, new Local(javaName, snakeCase(javaName),
                localType == null ? RustType.BOOL : localType, localType == RustType.OPT_TOKEN ? Place.OPTION_LOCAL : Place.LOCAL,
                localReason));
        }
    }

    /**
     * Collects the names that influence parsing: the names read by semantic
     * lookaheads, assertions, tree-building conditions, production
     * arguments and code that runs during lookahead, plus everything read by
     * the injected methods those call (transitively).
     */
    private void collectParseRelevantNames() {
        // The lexer side: the lexical actions, which the Rust lexer runs,
        // and whatever injected lexer methods they call, transitively.
        Deque<Node> lexerWork = new ArrayDeque<>();
        for (RegularExpression re : grammar.getLexerData().getRegularExpressions()) {
            if (re.getCodeSnippet() instanceof CodeBlock block) {
                lexerWork.add(block);
            }
        }
        while (!lexerWork.isEmpty()) {
            Node code = lexerWork.poll();
            for (MethodCall call : descendantsOrSelf(code, MethodCall.class)) {
                for (MethodInfo m : calledMethods(call, Side.LEXER)) {
                    if (reachableMethods.add(m)) {
                        lexerWork.add(m.declaration);
                    }
                }
            }
            collectNames(code, lexerRelevantNames);
        }
        // The parser side.
        Deque<Node> work = new ArrayDeque<>();
        for (BNFProduction production : grammar.getParserProductions()) {
            for (Lookahead la : production.descendants(Lookahead.class)) {
                if (la.getSemanticLookahead() != null) {
                    work.add(la.getSemanticLookahead());
                }
            }
            for (Assertion a : production.descendants(Assertion.class)) {
                if (a.getAssertionExpression() != null) work.add(a.getAssertionExpression());
                if (a.getMessageExpression() != null) work.add(a.getMessageExpression());
            }
            for (TreeBuildingAnnotation tba : production.descendants(TreeBuildingAnnotation.class)) {
                if (tba.getCondition() != null) work.add(tba.getCondition());
            }
            for (NonTerminal nt : production.descendants(NonTerminal.class)) {
                if (nt.getArgs() != null) work.add(nt.getArgs());
            }
            for (CodeBlock block : production.descendants(CodeBlock.class)) {
                if (block.isAppliesInLookahead()) work.add(block);
            }
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            for (Name name : descendantsOrSelf(n, Name.class)) {
                List<String> parts = nameParts(name);
                if (parts.isEmpty()) continue;
                boolean isCall = name.getParent() instanceof MethodCall && name.getParent().get(0) == name;
                int index = parts.get(0).equals("this") && parts.size() > 1 ? 1 : 0;
                if (isCall && parts.size() == index + 1) {
                    for (MethodInfo m : methods.get(Side.PARSER).getOrDefault(parts.get(index), List.of())) {
                        if (reachableMethods.add(m)) work.add(m.declaration);
                    }
                } else {
                    parseRelevantNames.add(parts.get(index));
                }
            }
            for (DotName dn : descendantsOrSelf(n, DotName.class)) {
                if (dn.get(0) instanceof ThisLiteral) {
                    parseRelevantNames.add(dn.get(dn.size() - 1).toString());
                }
            }
        }
    }

    /**
     * Adds to {@code into} the names that {@code code} reads or writes: the
     * first part of every name ({@code x} of {@code x.y}), and the member of
     * {@code this.x}.
     */
    private static void collectNames(Node code, Set<String> into) {
        for (Name name : descendantsOrSelf(code, Name.class)) {
            List<String> parts = nameParts(name);
            if (!parts.isEmpty()) {
                into.add(parts.get(0).equals("this") && parts.size() > 1 ? parts.get(1) : parts.get(0));
            }
        }
        for (DotName dn : descendantsOrSelf(code, DotName.class)) {
            if (dn.get(0) instanceof ThisLiteral) {
                into.add(dn.get(dn.size() - 1).toString());
            }
        }
    }

    private static <T extends Node> List<T> descendantsOrSelf(Node n, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(n)) {
            result.add(type.cast(n));
        }
        result.addAll(n.descendants(type));
        return result;
    }

    /** Translates the initializers of the injected fields (constants only). */
    private void translateFieldInitializers(Side side) {
        for (FieldInfo field : fields.get(side).values()) {
            if (field.unsupportedReason != null) {
                continue;
            }
            if (field.initializer == null) {
                field.rustInitializer = field.type.defaultValue;
                continue;
            }
            Ctx ctx = new Ctx(side, null, false, null, true, field.declaration.getLocation());
            try {
                RExpr value = translator.expression(field.initializer, ctx);
                field.rustInitializer = translator.convert(value, field.type, field.initializer, ctx).code();
            } catch (RustTranslationException e) {
                field.unsupportedReason = "its initializer cannot be translated (" + e.getMessage() + ")";
            }
        }
    }

    /**
     * Translates the injected methods.  A method that cannot be translated
     * makes its callers untranslatable, so translation is repeated until no
     * more methods fail (a fixed point; the number of rounds is bounded by
     * the number of methods).
     */
    private void translateMethods(Side side) {
        List<MethodInfo> all = new ArrayList<>();
        methods.get(side).values().forEach(all::addAll);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo m : all) {
                if (m.unsupportedReason != null) {
                    continue;
                }
                try {
                    m.rustCode = translateMethod(m);
                } catch (RustTranslationException e) {
                    m.unsupportedReason = e.getMessage() + " (at " + e.getLocation() + ")";
                    m.unsupportedNode = e.getNode() == null ? m.declaration : e.getNode();
                    m.rustCode = null;
                    changed = true;
                }
            }
        }
        for (MethodInfo m : all) {
            if (m.unsupportedReason != null) {
                boolean relevant = reachableMethods.contains(m)
                    || m.side == Side.PARSER && parseRelevantNames.contains(m.javaName);
                report(relevant, memberKind(side), m.unsupportedNode, methodHeader(m.declaration),
                    m.unsupportedReason, relevant
                        ? "every call to it is untranslatable; the method is listed in FIXME.md as Java code"
                        : "the method is not generated; its Java code is kept as a comment");
            }
        }
        for (FieldInfo f : fields.get(side).values()) {
            if (f.unsupportedReason != null) {
                boolean relevant = (side == Side.PARSER ? parseRelevantNames : lexerRelevantNames).contains(f.javaName);
                report(relevant, memberKind(side), f.declaration, oneLine(f.declaration.getParent()),
                    f.unsupportedReason, "the field is not generated, and code using it cannot be translated");
            }
        }
        for (Node other : otherMembers.get(side)) {
            report(false, memberKind(side), other, oneLine(other),
                "only fields and methods can be injected into the Rust " + (side == Side.PARSER ? "Parser" : "Lexer"),
                "the member is not generated; its Java code is kept as a comment");
        }
    }

    /**
     * @return the injected methods of {@code side} that {@code call} may
     *         invoke: an unqualified call ({@code foo(...)}) or a call on
     *         {@code this} ({@code this.foo(...)}) names every overload
     */
    private List<MethodInfo> calledMethods(MethodCall call, Side side) {
        if (call.get(0) instanceof Name name) {
            List<String> parts = nameParts(name);
            if (parts.size() == 1 || parts.size() == 2 && parts.get(0).equals("this")) {
                return methods.get(side).getOrDefault(parts.get(parts.size() - 1), List.of());
            }
        }
        return List.of();
    }

    /**
     * @return the header of a method declaration as written in the grammar
     *         (modifiers, return type, name and parameters, without the
     *         body) on one line, for example {@code private void reset(int depth)};
     *         {@code getFullSignature()} is an internal key ({@code reset#int})
     *         that users would not recognize in a message
     */
    static String methodHeader(MethodDeclaration md) {
        String source = md.getSource();
        Node body = md.firstChildOfType(CodeBlock.class);
        if (source == null || body == null || body.getBeginOffset() < md.getBeginOffset()) {
            return md.getName() + "(...)";
        }
        String header = source.substring(0, Math.min(source.length(), body.getBeginOffset() - md.getBeginOffset()));
        return header.replaceAll("\\s+", " ").trim();
    }

    private String translateMethod(MethodInfo m) throws RustTranslationException {
        Ctx ctx = new Ctx(m.side, null, false, m, false, m.declaration.getLocation());
        StringBuilder signature = new StringBuilder();
        signature.append("    /// Translated from the Java method injected at ").append(m.declaration.getLocation()).append(":\n");
        signature.append("    /// `").append(methodHeader(m.declaration).replace("`", "'")).append("`\n");
        signature.append("    fn ").append(m.rustName).append("(&mut self");
        for (ParamInfo p : m.params) {
            signature.append(", ");
            if (p.tokenRef) {
                signature.append(p.rustName).append(": &mut Token");
                ctx.declare(new Local(p.javaName, p.rustName, RustType.TOKEN, Place.REF_PARAM, null));
            } else {
                signature.append("mut ").append(p.rustName).append(": ").append(p.type.rust);
                ctx.declare(new Local(p.javaName, p.rustName, p.type, Place.LOCAL, null));
            }
        }
        signature.append(")");
        if (m.returnType != RustType.VOID) {
            signature.append(" -> ").append(m.returnType.rust);
        }
        signature.append(" {\n");
        CodeBlock body = m.declaration.firstChildOfType(CodeBlock.class);
        List<Node> statements = blockStatements(body);
        StringBuilder out = new StringBuilder();
        translator.statements(statements, ctx, out, 8);
        if (m.returnType != RustType.VOID
            && (statements.isEmpty() || !(statements.get(statements.size() - 1) instanceof ReturnStatement))) {
            // Java proved that the end of the method is unreachable; Rust's
            // analysis is weaker (for example for do/while loops), so make
            // the impossible fall-through explicit.
            out.append("        #[allow(unreachable_code)]\n");
            out.append("        {\n");
            out.append("            unreachable!(\"the Java method '").append(m.javaName)
               .append("' ended without returning a value\")\n");
            out.append("        }\n");
        }
        return signature + out.toString() + "    }\n";
    }

    /** Finds the {@code Character.isXxx} predicates referenced anywhere. */
    private void collectCharacterPredicates() {
        List<Node> roots = new ArrayList<>(grammar.getParserProductions());
        for (RegularExpression re : grammar.getLexerData().getRegularExpressions()) {
            if (re.getCodeSnippet() != null) {
                roots.add(re.getCodeSnippet());
            }
        }
        for (Side side : Side.values()) {
            methods.get(side).values().forEach(list -> list.forEach(m -> roots.add(m.declaration)));
        }
        for (Node root : roots) {
            for (MethodCall mc : descendantsOrSelf(root, MethodCall.class)) {
                if (mc.get(0) instanceof Name name) {
                    List<String> parts = nameParts(name);
                    if (parts.size() == 2 && parts.get(0).equals("Character") && CHARACTER_PREDICATES.contains(parts.get(1))) {
                        usedCharacterPredicates.add(parts.get(1));
                    }
                }
            }
        }
    }

    /**
     * Reports grammar features that the Rust target does not implement, so
     * that their absence is never silent.
     */
    private void reportUnsupportedFeatures() {
        for (BNFProduction production : grammar.getParserProductions()) {
            for (Assertion a : production.descendants(Assertion.class)) {
                if (a.isCardinalityConstraint()) {
                    report(false, SiteKind.FEATURE, a, oneLine(a),
                        "repetition cardinality constraints are not implemented by the Rust target",
                        "the constraint is not checked (the Rust parser accepts any number of repetitions)",
                        null, CARDINALITY_REMEDY);
                    break;
                }
            }
        }
        if (settings.getFaultTolerant()) {
            reportSetting("FAULT_TOLERANT", "fault-tolerant parsing is not implemented by the Rust target",
                "the Rust parser stops at the first syntax error");
        }
        if (settings.getSyntheticNodesEnabled() || settings.getJtbParseTree()) {
            String key = settings.getJtbParseTree() ? "JTB_PARSE_TREE" : "SYNTHETIC_NODES_ENABLED";
            reportSetting(key, "synthetic (JTB-style) tree nodes are not implemented by the Rust target",
                "the Rust parser builds only the nodes named in the grammar, so its tree differs from the Java parser's");
        }
        if (settings.getUnparsedTokensAreNodes()) {
            reportSetting("UNPARSED_TOKENS_ARE_NODES",
                "adding unparsed tokens (such as comments) to the tree is not implemented by the Rust target",
                "the Rust tree contains no unparsed tokens");
        }
        reportHooks(grammar.getParserTokenHooks(), Side.PARSER, "parser token hook",
            "the Rust parser does not call it, so it may see different tokens than the Java parser");
        reportHooks(grammar.getResetTokenHooks(), Side.LEXER, "reset token hook", "the Rust lexer does not call it");
        reportHooks(grammar.getLexerTokenHooks(), Side.LEXER, "lexer token hook",
            "the Rust lexer does not call it (the generator only emulates the Lua-style long-bracket hook)");
        reportHooks(grammar.getOpenNodeScopeHooks(), Side.PARSER, "open node scope hook", "the Rust parser does not call it");
        reportHooks(grammar.getCloseNodeScopeHooks(), Side.PARSER, "close node scope hook", "the Rust parser does not call it");
        for (List<String> hooks : grammar.getCloseNodeHooksByClass().values()) {
            reportHooks(hooks, Side.PARSER, "close node hook", "the Rust parser does not call it");
        }
    }

    /** Reports a setting that the Rust target ignores, at the place where the grammar sets it. */
    private void reportSetting(String key, String reason, String consequence) {
        report(false, SiteKind.FEATURE, settingNode(key), key, reason, consequence, null, SETTING_REMEDY);
    }

    /**
     * Reports Java hook methods (methods with special names that the Java
     * parser or lexer calls automatically) as warnings: the Rust target never
     * calls them.
     */
    /**
     * Reports hooks (methods that the generated Java code calls because of
     * their names) at their declarations, if they are injected methods.
     */
    private void reportHooks(List<String> hooks, Side side, String kind, String consequence) {
        if (hooks == null) {
            return;
        }
        for (String hook : hooks) {
            List<MethodInfo> declared = methods.get(side).getOrDefault(hook, List.of());
            Node at = declared.isEmpty() ? null : declared.get(0).declaration;
            report(false, SiteKind.FEATURE, at, hook, "the " + kind + " '" + hook + "' is Java code that the "
                + "generated Java " + (side == Side.LEXER ? "lexer" : "parser") + " calls automatically", consequence,
                null, HOOK_REMEDY);
        }
    }

    // =====================================================================
    // Symbols (used by RustJavaTranslator)
    // =====================================================================

    @Override
    public boolean isTokenType(String name) {
        return tokenTypes.contains(name);
    }

    @Override
    public boolean isLexicalState(String name) {
        return lexicalStates.contains(name);
    }

    @Override
    public List<String> tokenTypesOfClass(String simpleClassName) {
        List<String> types = tokenTypesByClass.get(simpleClassName);
        if (types == null) {
            return null;
        }
        // Declaration order of the token types, for stable output.
        List<String> sorted = new ArrayList<>(types);
        Map<String, Integer> order = new HashMap<>();
        int i = 0;
        for (RegularExpression re : grammar.getLexerData().getRegularExpressions()) {
            order.put(re.getLabel(), i++);
        }
        for (String extra : settings.getExtraTokenNames()) {
            order.put(extra, i++);
        }
        order.put("INVALID", i);
        sorted.sort(Comparator.comparingInt(t -> order.getOrDefault(t, Integer.MAX_VALUE)));
        return sorted;
    }

    @Override
    public boolean isBaseTokenClass(String simpleClassName) {
        return simpleClassName.equals(simpleName(settings.getBaseTokenClassName()));
    }

    @Override
    public FieldInfo field(Side side, String javaName) {
        return fields.get(side).get(javaName);
    }

    @Override
    public List<MethodInfo> methods(Side side, String javaName) {
        return methods.get(side).getOrDefault(javaName, List.of());
    }

    @Override
    public Map<String, Local> productionLocals(BNFProduction production, boolean inline) {
        return (inline ? inlineLocals : lookaheadLocals).getOrDefault(production, Map.of());
    }

    @Override
    public Map<String, Local> productionParameters(BNFProduction production) {
        return parameters.getOrDefault(production, Map.of());
    }

    @Override
    public String characterPredicate(String javaMethod) {
        return CHARACTER_PREDICATES.contains(javaMethod) ? characterHelperName(javaMethod) : null;
    }

    private static String characterHelperName(String javaMethod) {
        return "java_" + snakeCase(javaMethod).replace("i_s_o", "iso");
    }

    @Override
    public String nodePackage() {
        return settings.getNodePackage();
    }

    @Override
    public String parserPackage() {
        return settings.getParserPackage();
    }

    // =====================================================================
    // Reporting
    // =====================================================================

    private static final String REMEDY = "Possible remedies: rewrite the code using the subset of Java that the "
        + "Rust target translates (it is described in the FIXME.md file of the generated crate); or provide Rust "
        + "code for this site in a raw code block ({R% ... %}), inside '#if __rust__ ... #endif' if the grammar "
        + "also targets other languages; or generate with -rust-allow-stubs to replace it with a stub.";

    private static final String WARNING_REMEDY = "If the Rust parser needs this code, rewrite it using the subset "
        + "of Java that the Rust target translates (see FIXME.md in the generated crate), or provide Rust code "
        + "for this site in a raw code block ({R% ... %}).";

    /** The remedy for a grammar construct that the Rust target does not implement (an error). */
    private static final String CONSTRUCT_REMEDY = "Possible remedies: write this part of the grammar differently for "
        + "the Rust target (with '#if __rust__ ... #else ... #endif'); or generate with -rust-allow-stubs, which "
        + "replaces it as described.";

    /** The remedy for a stubbed grammar construct that the Rust target does not implement. */
    private static final String STUBBED_CONSTRUCT_REMEDY = "If that matters, write this part of the grammar "
        + "differently for the Rust target (with '#if __rust__ ... #else ... #endif').";

    /** The remedy for a setting that the Rust target ignores. */
    private static final String SETTING_REMEDY = "If the Rust parser must behave like the Java parser, do not use "
        + "this setting; to keep it for the other targets only, put it inside '#if !__rust__ ... #endif'.";

    /** The remedy for a hook that the Rust parser or lexer does not call. */
    private static final String HOOK_REMEDY = "If the Rust parser needs what the hook does, do it in the grammar's "
        + "code actions instead; to keep the hook for the other targets only, put it inside "
        + "'#if !__rust__ ... #endif'.";

    /** The remedy for a repetition cardinality constraint, which the Rust target does not check. */
    private static final String CARDINALITY_REMEDY = "If the Rust parser must reject the same inputs, count the "
        + "repetitions in a code action and check the count with an ASSERT instead.";

    /**
     * Records a diagnostic and reports it to {@link Errors} (once per site),
     * with the remedies for Java code that cannot be translated.
     *
     * @return the message reported
     */
    private String report(boolean error, SiteKind kind, Node at, String javaSource, String reason, String consequence) {
        return report(error, kind, at, javaSource, reason, consequence, null, null);
    }

    /**
     * Records a diagnostic and reports it to {@link Errors} (once per site).
     *
     * @param error         true if the problem changes what is parsed: an
     *                      error, unless the stubs are allowed
     * @param at            the node where the problem is, or null if it has
     *                      no place in the grammar (the main grammar file is
     *                      reported instead)
     * @param remedy        what the user can do about an error, or null for
     *                      the remedies of Java code that cannot be translated
     * @param stubbedRemedy what the user can do about a warning (an error
     *                      stubbed because of -rust-allow-stubs, or a
     *                      warning), or null for the remedies of Java code that
     *                      cannot be translated
     * @return the message reported
     */
    private String report(boolean error, SiteKind kind, Node at, String javaSource, String reason, String consequence,
                          String remedy, String stubbedRemedy) {
        boolean asError = error && !allowStubs;
        String location = at == null || at.getLocation() == null ? grammarLocation() : at.getLocation();
        String advice = asError ? (remedy != null ? remedy : REMEDY)
                                : (stubbedRemedy != null ? stubbedRemedy : WARNING_REMEDY);
        String message = "Cannot translate the " + kind.description + " '" + javaSource + "' to Rust: " + reason
            + ". " + capitalize(consequence) + "."
            + (error && !asError ? " This is stubbed because of -rust-allow-stubs, so the Rust parser may behave "
                                   + "differently from the Java parser." : "")
            + " " + advice;
        String key = location + "|" + kind + "|" + reason;
        if (reportedKeys.add(key)) {
            diagnostics.add(new Diagnostic(asError, kind, location, javaSource, reason, consequence));
            if (at != null) {
                if (asError) {
                    errors.addError(at, message);
                } else {
                    errors.addWarning(at, message);
                }
            } else {
                // Errors would print an empty location for a null node.
                String prefixed = (asError ? "Error: " : "Warning: ") + location + ":" + message;
                if (asError) {
                    errors.addError(prefixed);
                } else {
                    errors.addWarning(prefixed);
                }
            }
        }
        return message;
    }

    /** @return the main grammar file, the location of problems that have no place in it */
    private String grammarLocation() {
        java.nio.file.Path file = settings.getFilename();
        return file == null ? "the grammar" : file.toString();
    }

    /**
     * @return the node where the grammar sets {@code key} (such as
     *         {@code FAULT_TOLERANT}), or null if it is not found (the setting
     *         may come from an included file that is not part of the tree)
     */
    private Node settingNode(String key) {
        for (Setting setting : grammar.descendants(Setting.class)) {
            if (setting.size() > 0 && setting.get(0).toString().equalsIgnoreCase(key)) {
                return setting;
            }
        }
        return null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** @return a Rust {@code compile_error!} invocation carrying the message */
    private static String compileError(String message) {
        return "compile_error!(\"" + escapeForRustString(message) + "\")";
    }

    /** @return the Java source as a block of Rust line comments */
    private static String javaAsComment(Node javaCode, int indent) {
        String source = javaCode.getSource();
        if (source == null) {
            source = javaCode.toString();
        }
        StringBuilder sb = new StringBuilder();
        String pad = " ".repeat(indent);
        for (String line : source.strip().split("\r?\n")) {
            sb.append(pad).append("//     ").append(line.stripTrailing()).append('\n');
        }
        return sb.toString();
    }

    private static String inlineComment(String text) {
        return "/* " + text.replace("*/", "* /").replace("/*", "/ *") + " */";
    }

    // =====================================================================
    // Sites: parser code actions
    // =====================================================================

    /**
     * Translates a code action of a production, emitted inline in the
     * production's function.
     *
     * @param block  the code block
     * @param indent the indentation of the generated statements
     * @return Rust statements, one per line, each ending with a newline
     */
    public String action(CodeBlock block, int indent) {
        ensureInitialized();
        return translateAction(block, indent, false);
    }

    /**
     * Translates a code action that is emitted in a lookahead routine
     * ({@code {...}#} blocks, and code in productions that are only used
     * for lookahead).
     */
    public String lookaheadAction(CodeBlock block, int indent) {
        ensureInitialized();
        return translateAction(block, indent, true);
    }

    private String translateAction(CodeBlock block, int indent, boolean lookahead) {
        BNFProduction production = block.getContainingProduction();
        Ctx ctx = new Ctx(Side.PARSER, production, !lookahead, null, false, block.getLocation());
        String pad = " ".repeat(indent);
        StringBuilder out = new StringBuilder();
        List<Node> statements;
        try {
            statements = blockStatements(block);
        } catch (RustTranslationException e) {
            return failedStatement(block, e, lookahead, true, ctx, indent);
        }
        Node tailReturn = null;
        if (!lookahead && !statements.isEmpty() && statements.get(statements.size() - 1) instanceof ReturnStatement r
            && isTailOfProduction(block)) {
            tailReturn = r;
        }
        for (Node statement : statements) {
            if (statement == tailReturn) {
                out.append(pad).append("// The production returns '").append(oneLine(statement).replace("\n", " "))
                   .append("' in Java; Rust productions return no value.\n");
                continue;
            }
            StringBuilder buf = new StringBuilder();
            try {
                translator.statement(statement, ctx, buf, indent);
                out.append(buf);
            } catch (RustTranslationException e) {
                boolean relevant = lookahead || isParseRelevantFailure(statement);
                out.append(failedStatement(statement, e, lookahead, relevant, ctx, indent));
            }
        }
        return out.toString();
    }

    /**
     * Emits the replacement of an untranslatable statement and reports it.
     * A failed local variable declaration still declares the variable (with
     * Java's default value) when its type is supported, so that later code
     * that only reads it still translates.
     */
    private String failedStatement(Node statement, RustTranslationException e, boolean lookahead,
                                   boolean relevant, Ctx ctx, int indent) {
        String pad = " ".repeat(indent);
        SiteKind kind = lookahead ? SiteKind.LOOKAHEAD_ACTION : SiteKind.PARSER_ACTION;
        StringBuilder out = new StringBuilder();
        String consequence = relevant && !allowStubs ? "the generated Rust code contains a compile_error! at this point"
                           : "the statement is not executed by the Rust parser; its Java code is kept as a comment";
        String message = report(relevant, kind, e.getNode() == null ? statement : e.getNode(), oneLine(statement),
                                e.getMessage(), consequence);
        if (relevant && !allowStubs) {
            out.append(pad).append(compileError(message)).append(";\n");
        } else {
            out.append(pad).append("// FIXME(congocc): this Java statement could not be translated (").append(
                e.getMessage().replace("\n", " ")).append("):\n");
            out.append(javaAsComment(statement, indent));
        }
        if (statement instanceof LocalVariableDeclaration lvd) {
            RustType type = null;
            String reason = null;
            try {
                Node typeNode = typeNodeOf(lvd);
                type = typeNode == null ? null : translator.typeOf(typeNode);
            } catch (RustTranslationException te) {
                reason = te.getMessage();
            }
            for (VariableDeclarator vd : lvd.childrenOfType(VariableDeclarator.class)) {
                String javaName = vd.get(0).toString();
                String rustName = snakeCase(javaName);
                if (type != null && type.defaultValue != null && type.isDeclarable()) {
                    out.append(pad).append("let mut ").append(rustName).append(": ").append(type.rust).append(" = ")
                       .append(type.defaultValue).append("; // Java default value; see the FIXME above\n");
                    ctx.declare(new Local(javaName, rustName, type,
                        type == RustType.OPT_TOKEN ? Place.OPTION_LOCAL : Place.LOCAL, null));
                } else {
                    ctx.declare(new Local(javaName, rustName, RustType.BOOL, Place.LOCAL,
                        "its declaration could not be translated (" + (reason == null ? e.getMessage() : reason) + ")"));
                }
            }
        }
        return out.toString();
    }

    /**
     * @return true if the code block is the last thing a production does:
     *         every ancestor up to the production is a sequence in which it
     *         is the last unit, or a choice or parentheses
     */
    private static boolean isTailOfProduction(Node block) {
        Node current = block;
        while (!(current.getParent() instanceof BNFProduction)) {
            Node parent = current.getParent();
            if (parent == null) {
                return false;
            }
            if (parent instanceof ExpansionSequence seq) {
                List<Expansion> units = seq.getUnits();
                if (units.isEmpty() || units.get(units.size() - 1) != current) {
                    return false;
                }
            } else if (!(parent instanceof ExpansionChoice) && !(parent instanceof ExpansionWithParentheses)) {
                return false;
            }
            current = parent;
        }
        return true;
    }

    /**
     * Decides whether failing to translate a statement of a code action can
     * change how the input is parsed (an error) or not (a warning).
     */
    private boolean isParseRelevantFailure(Node statement) {
        for (Node n : descendantsOrSelf(statement, Node.class)) {
            if (n instanceof ReturnStatement || n instanceof ThrowStatement || n instanceof YieldStatement) {
                return true;
            }
            if ((n instanceof BreakStatement || n instanceof ContinueStatement) && !jumpsWithin(n, statement)) {
                return true;
            }
            if (n instanceof AssignmentExpression || n instanceof PreIncrementExpression
                || n instanceof PreDecrementExpression || n instanceof PostfixExpression) {
                String target = assignedName(n);
                if (target == null || parseRelevantNames.contains(target)) {
                    return true;
                }
            }
            if (n instanceof VariableDeclarator vd && parseRelevantNames.contains(vd.get(0).toString())) {
                return true;
            }
            if (n instanceof MethodCall mc && callChangesParseState(mc, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    /** @return true if the break/continue targets a loop or switch inside {@code root} */
    private static boolean jumpsWithin(Node jump, Node root) {
        boolean isBreak = jump instanceof BreakStatement;
        for (Node n = jump.getParent(); n != null; n = n.getParent()) {
            if (n instanceof WhileStatement || n instanceof DoStatement || n instanceof BasicForStatement
                || n instanceof EnhancedForStatement || (isBreak && n instanceof SwitchStatement)) {
                return true;
            }
            if (n == root) {
                return false;
            }
        }
        return false;
    }

    /** @return the base name assigned by an assignment or increment, or null if unknown */
    private static String assignedName(Node n) {
        Node target = n instanceof AssignmentExpression ? n.get(0) : nodeChildren(n).isEmpty() ? null : nodeChildren(n).get(0);
        if (target instanceof Name name) {
            List<String> parts = nameParts(name);
            return parts.get(0).equals("this") && parts.size() > 1 ? parts.get(1) : parts.get(0);
        }
        if (target instanceof DotName dn && dn.get(0) instanceof ThisLiteral) {
            return dn.get(dn.size() - 1).toString();
        }
        return null;
    }

    private boolean callChangesParseState(MethodCall mc, Set<MethodInfo> visiting) {
        if (!(mc.get(0) instanceof Name name)) {
            return false;
        }
        List<String> parts = nameParts(name);
        String method = parts.get(parts.size() - 1);
        if (parts.size() > 1 && STATE_CHANGING_RECEIVERS.contains(parts.get(0))) {
            return true;
        }
        if (parts.size() == 1 || parts.size() == 2 && parts.get(0).equals("this")) {
            if (STATE_CHANGING_API.contains(method)) {
                return true;
            }
            for (MethodInfo m : methods.get(Side.PARSER).getOrDefault(method, List.of())) {
                if (visiting.add(m)) {
                    for (Node n : descendantsOrSelf(m.declaration, Node.class)) {
                        if ((n instanceof AssignmentExpression || n instanceof PostfixExpression
                             || n instanceof PreIncrementExpression || n instanceof PreDecrementExpression)) {
                            String target = assignedName(n);
                            if (target == null || parseRelevantNames.contains(target)) {
                                return true;
                            }
                        }
                        if (n instanceof MethodCall inner && callChangesParseState(inner, visiting)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // =====================================================================
    // Sites: expressions
    // =====================================================================

    /**
     * Translates the semantic lookahead of an expansion to a Rust boolean
     * expression.
     *
     * @param expansion the expansion whose lookahead has a semantic part
     * @param inline    true when the expression is emitted in the
     *                  production's function (it then sees the
     *                  production's locals and parameters), false when it is
     *                  emitted in a lookahead routine
     */
    public String predicate(Expansion expansion, boolean inline) {
        ensureInitialized();
        EmbeddedCode code = expansion.getSemanticLookahead();
        return booleanSite(code, expansion.getContainingProduction(), inline, SiteKind.PREDICATE,
            "the semantic lookahead is replaced by 'true'");
    }

    /**
     * Translates the condition of an assertion ({@code ASSERT {...}} or
     * {@code ENSURE {...}}) to a Rust boolean expression.
     */
    public String assertionCondition(Assertion assertion, boolean inline) {
        ensureInitialized();
        EmbeddedCode code = assertion.getAssertionExpression() != null ? assertion.getAssertionExpression()
                          : assertion.getRawCode();
        return booleanSite(code, assertion.getContainingProduction(), inline, SiteKind.ASSERTION,
            "the assertion is not checked");
    }

    private String booleanSite(EmbeddedCode code, BNFProduction production, boolean inline, SiteKind kind,
                               String stubConsequence) {
        if (code == null) {
            return "true";
        }
        if (code instanceof RawCode rc) {
            return rawExpression(rc, kind);
        }
        Ctx ctx = new Ctx(Side.PARSER, production, inline, null, false, code.getLocation());
        try {
            RExpr value = translator.expression(code, ctx);
            return translator.convert(value, RustType.BOOL, code, ctx).code();
        } catch (RustTranslationException e) {
            String consequence = allowStubs ? stubConsequence : "the generated Rust code contains a compile_error! here";
            String message = report(true, kind, e.getNode() == null ? code : e.getNode(), oneLine(code),
                                    e.getMessage(), consequence);
            return allowStubs ? inlineComment("FIXME(congocc): " + oneLine(code) + " -- " + e.getMessage()) + " true"
                              : compileError(message);
        }
    }

    private String rawExpression(RawCode rc, SiteKind kind) {
        if (rc.isWrongLanguageIgnore()) {
            String message = report(true, kind, rc, oneLine(rc),
                "the raw code block is written for another target language", "the generated Rust code contains a compile_error! here");
            return allowStubs ? "true" : compileError(message);
        }
        return "(" + rc.toString().trim() + ")";
    }

    /**
     * Translates the message of an assertion into a Rust expression of
     * type {@code &str} (or {@code &String}) for {@code fail}, prefixed by
     * the location text that the Java parser uses.
     */
    public String assertionMessage(Assertion assertion) {
        ensureInitialized();
        String prefix = "Assertion at: " + assertion.getLocation() + " failed. ";
        Node message = assertion.getMessageExpression();
        String prefixLiteral = "\"" + escapeForRustString(prefix) + "\"";
        if (message == null) {
            return prefixLiteral;
        }
        if (message instanceof LiteralExpression && message.get(0) instanceof StringLiteral) {
            try {
                Ctx ctx = new Ctx(Side.PARSER, assertion.getContainingProduction(), true, null, false, message.getLocation());
                RExpr text = translator.expression(message, ctx);
                return "concat!(" + prefixLiteral + ", " + text.code() + ")";
            } catch (RustTranslationException e) {
                // fall through to the general case
            }
        }
        Ctx ctx = new Ctx(Side.PARSER, assertion.getContainingProduction(), true, null, false, message.getLocation());
        try {
            RExpr text = translator.expression(message, ctx);
            if (text.type() == RustType.STRING || text.type().isNumeric() || text.type() == RustType.BOOL
                || text.type() == RustType.TOKEN_TYPE) {
                String display = text.type() == RustType.TOKEN_TYPE ? "{:?}" : "{}";
                return "&format!(\"{}" + display + "\", " + prefixLiteral + ", " + text.code() + ")";
            }
            throw new RustTranslationException(message, "a message of type " + text.type().describe() + " cannot be formatted");
        } catch (RustTranslationException e) {
            report(false, SiteKind.ASSERTION_MESSAGE, e.getNode() == null ? message : e.getNode(), oneLine(message),
                e.getMessage(), "the Java source of the message is used as the message text");
            return "\"" + escapeForRustString(prefix + oneLine(message)) + "\"";
        }
    }

    /**
     * Translates the expression of an assertion's location
     * ({@code ASSERT {cond : message : location}}), a token.
     *
     * @return Rust code yielding a {@code Token}, or null if the assertion has no location expression
     */
    public String assertionLocation(Assertion assertion) {
        ensureInitialized();
        Node location = assertion.getLocationExpression();
        if (location == null) {
            return null;
        }
        Ctx ctx = new Ctx(Side.PARSER, assertion.getContainingProduction(), true, null, false, location.getLocation());
        try {
            return translator.convert(translator.expression(location, ctx), RustType.TOKEN, location, ctx).code();
        } catch (RustTranslationException e) {
            report(false, SiteKind.ASSERTION_MESSAGE, e.getNode() == null ? location : e.getNode(), oneLine(location),
                e.getMessage(), "the failure is reported at the next token instead");
            return null;
        }
    }

    /**
     * Generates the statement that closes a tree node scope.
     *
     * <p>Java decides between the two {@code closeNodeScope} overloads by the
     * type of the condition: an {@code int} condition (e.g. {@code #Foo(3)},
     * {@code #Foo(+1)}) creates a node with exactly that many children,
     * taken from the top of the node stack and possibly from enclosing
     * scopes; a {@code boolean} condition (e.g. {@code #Foo(>1)},
     * {@code #Foo(flag)}) creates a node with all the children of the scope
     * if it is true.</p>
     *
     * @param condition  the condition: an Expression node, a String constant
     *                   (the smart-node default "1"), or null for "always"
     * @param gtNode     true if the condition has a shorthand operator
     * @param shorthand  the shorthand operator (&gt;, &gt;=, &lt;, &lt;=, +, -), or null
     * @param site       the expansion that builds the node (for diagnostics and locals)
     * @param offsetExpr Rust code for the end offset to use if the node has no children
     * @return a Rust block statement
     */
    public String closeNodeScope(Object condition, boolean gtNode, String shorthand, Expansion site, String offsetExpr) {
        ensureInitialized();
        // The template passes "" for an absent condition or shorthand,
        // because a template expression cannot pass a Java null.
        if (condition instanceof String s && s.isBlank()) {
            condition = null;
        }
        if (shorthand != null && shorthand.isBlank()) {
            shorthand = null;
        }
        if (condition == null) {
            return "self.builder.close_node_scope_conditional(true, " + offsetExpr + ");";
        }
        BNFProduction production = site instanceof BNFProduction p ? p : site.getContainingProduction();
        RExpr cond;
        Node conditionNode = condition instanceof Node n ? n : null;
        String javaText = conditionNode != null ? oneLine(conditionNode) : condition.toString();
        String op = shorthand == null ? "" : shorthand.trim();
        try {
            if (conditionNode != null) {
                Ctx ctx = new Ctx(Side.PARSER, production, true, null, false, conditionNode.getLocation());
                cond = translator.expression(conditionNode, ctx);
                if (cond.type() == RustType.CHAR) {
                    cond = translator.convert(cond, RustType.INT, conditionNode, ctx);
                }
            } else {
                String text = condition.toString().trim();
                if (!text.matches("-?[0-9]+")) {
                    throw new RustTranslationException(site, "unexpected tree-building condition '" + text + "'");
                }
                cond = RExpr.value(text, RustType.INT, !text.startsWith("-"), false);
            }
            if (gtNode && !op.isEmpty()) {
                javaText = op + javaText;
                if (cond.type() != RustType.INT) {
                    throw new RustTranslationException(conditionNode == null ? site : conditionNode,
                        "the condition after '" + op + "' must be an int, not " + cond.type().withArticle());
                }
                String arity = "(self.builder.node_arity() as i32)";
                switch (op) {
                    case ">", ">=", "<", "<=" -> {
                        return "{ let __condition: bool = " + arity + " " + op + " " + cond.operand()
                            + "; self.builder.close_node_scope_conditional(__condition, " + offsetExpr + "); }";
                    }
                    case "+", "-" -> {
                        return "{ let __num_children: i32 = " + arity + " " + op + " " + cond.operand()
                            + "; self.builder.close_node_scope_definite(__num_children, " + offsetExpr + "); }";
                    }
                    default -> throw new RustTranslationException(site, "unknown tree-building shorthand '" + op + "'");
                }
            }
            if (cond.type() == RustType.INT) {
                return "{ let __num_children: i32 = " + cond.code()
                    + "; self.builder.close_node_scope_definite(__num_children, " + offsetExpr + "); }";
            }
            if (cond.type() == RustType.BOOL) {
                return "{ let __condition: bool = " + cond.code()
                    + "; self.builder.close_node_scope_conditional(__condition, " + offsetExpr + "); }";
            }
            throw new RustTranslationException(conditionNode == null ? site : conditionNode,
                "a tree-building condition must be an int or a boolean, not " + cond.type().withArticle());
        } catch (RustTranslationException e) {
            String consequence = allowStubs ? "the node is always created with all the children of its scope"
                                            : "the generated Rust code contains a compile_error! here";
            String message = report(true, SiteKind.TREE_CONDITION, e.getNode() == null ? site : e.getNode(),
                                    javaText, e.getMessage(), consequence);
            if (allowStubs) {
                return inlineComment("FIXME(congocc): tree-building condition " + javaText)
                    + " self.builder.close_node_scope_conditional(true, " + offsetExpr + ");";
            }
            return compileError(message) + ";";
        }
    }

    // =====================================================================
    // Sites: productions
    // =====================================================================

    /**
     * @return the Rust parameter list of a production's function, each
     *         parameter preceded by ", " (empty if it has none)
     */
    public String productionParameterList(BNFProduction production) {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        for (Local p : productionParameters(production).values()) {
            if (p.unavailableReason != null) {
                report(true, SiteKind.PRODUCTION_PARAMETERS, production, production.getName() + "(" +
                    oneLine(production.getParameterList()) + ")", "the parameter '" + p.javaName + "' cannot be "
                    + "represented: " + p.unavailableReason, allowStubs ? "the parameter is declared as a bool"
                        : "the generated Rust code contains a compile_error! here");
                if (!allowStubs) {
                    return ", " + p.rustName + ": [(); " + compileError("unsupported production parameter type") + "]";
                }
            }
            sb.append(", mut ").append(p.rustName).append(": ").append(p.type.rust);
        }
        return sb.toString();
    }

    /**
     * Generates the call of the function of the production that a
     * non-terminal refers to, with its arguments translated and type-checked
     * against the production's parameters.
     *
     * <p>Arguments are evaluated left to right, as in Java.  When an argument
     * has side effects (for example it calls {@code getToken(n)}, which may
     * lex), all arguments are first evaluated into temporaries: Rust does not
     * allow the parser to be borrowed mutably by an argument while it is
     * already borrowed as the receiver of the call.</p>
     *
     * @param nt the non-terminal
     * @return a Rust expression of type {@code PResult}, for example
     *         {@code self.parse_Foo(3)} or
     *         {@code { let __argument_0 = self.get_token(1); self.parse_Foo(__argument_0) }}
     */
    public String nonTerminalCall(NonTerminal nt) {
        ensureInitialized();
        String callee = "self.parse_" + nt.getName();
        InvocationArguments args = nt.getArgs();
        List<Local> params = new ArrayList<>(productionParameters(nt.getProduction()).values());
        List<Expression> argList = args == null ? List.of() : args.getArgs();
        Ctx ctx = new Ctx(Side.PARSER, nt.getContainingProduction(), true, null, false, nt.getLocation());
        try {
            if (argList.size() != params.size()) {
                throw new RustTranslationException(nt, "the production " + nt.getName() + " takes " + params.size()
                    + " arguments, not " + argList.size());
            }
            List<String> codes = new ArrayList<>();
            boolean hoist = false;
            for (int i = 0; i < argList.size(); i++) {
                RExpr value = translator.convert(translator.expression(argList.get(i), ctx), params.get(i).type,
                                                 argList.get(i), ctx);
                codes.add(value.code());
                hoist |= value.effects();
            }
            if (!hoist) {
                return callee + "(" + String.join(", ", codes) + ")";
            }
            StringBuilder sb = new StringBuilder("{ ");
            List<String> temps = new ArrayList<>();
            for (int i = 0; i < codes.size(); i++) {
                String temp = "__argument_" + i;
                sb.append("let ").append(temp).append(" = ").append(codes.get(i)).append("; ");
                temps.add(temp);
            }
            return sb.append(callee).append("(").append(String.join(", ", temps)).append(") }").toString();
        } catch (RustTranslationException e) {
            String message = report(true, SiteKind.PRODUCTION_ARGUMENTS, e.getNode() == null ? nt : e.getNode(),
                nt.getName() + "(" + (args == null ? "" : oneLine(args)) + ")", e.getMessage(),
                allowStubs ? "default values are passed" : "the generated Rust code contains a compile_error! here");
            if (!allowStubs) {
                return compileError(message);
            }
            List<String> defaults = new ArrayList<>();
            for (Local p : params) {
                defaults.add(p.type.defaultValue == null ? "false" : p.type.defaultValue);
            }
            return callee + "(" + String.join(", ", defaults) + ")";
        }
    }

    // =====================================================================
    // Sites: FAIL and features without a Rust implementation
    // =====================================================================

    /**
     * Translates the message of a {@code FAIL} statement into a Rust
     * expression of type {@code &str}: {@code "Failure"}, or
     * {@code "Failure: "} followed by the value of the expression given in
     * the grammar, as the Java parser does.
     */
    public String failureMessage(Failure fail) {
        ensureInitialized();
        Expression exp = fail.getExp();
        if (exp == null) {
            return "\"Failure\"";
        }
        Ctx ctx = new Ctx(Side.PARSER, fail.getContainingProduction(), true, null, false, exp.getLocation());
        try {
            RExpr text = translator.expression(exp, ctx);
            if (text.type() == RustType.STRING || text.type().isNumeric() || text.type() == RustType.BOOL) {
                return "&format!(\"Failure: {}\", " + text.code() + ")";
            }
            if (text.type() == RustType.TOKEN_TYPE) {
                return "&format!(\"Failure: {:?}\", " + text.code() + ")";
            }
            throw new RustTranslationException(exp, "a message of type " + text.type().describe() + " cannot be formatted");
        } catch (RustTranslationException e) {
            report(false, SiteKind.ASSERTION_MESSAGE, e.getNode() == null ? exp : e.getNode(), oneLine(exp),
                e.getMessage(), "the Java source of the message is used as the message text");
            return "\"" + escapeForRustString("Failure: " + oneLine(exp)) + "\"";
        }
    }

    /**
     * Translates the code block of a {@code FAIL {...}} statement (a
     * failure with its own Java code instead of a message).
     *
     * @return Rust statements
     */
    public String failureCode(Failure fail, int indent) {
        ensureInitialized();
        EmbeddedCode code = fail.getCode();
        if (code instanceof CodeBlock block) {
            return action(block, indent);
        }
        if (code instanceof RawCode raw) {
            return raw.isWrongLanguageIgnore() ? "" : " ".repeat(indent) + raw.getRawContent().trim() + "\n";
        }
        return unsupported((Node) fail, "FAIL statements whose code is not a code block", "the failure is not raised");
    }

    /**
     * Reports a grammar construct that the Rust target does not implement
     * (for example {@code TRY}/{@code CATCH} blocks) as an error, or as a
     * warning with {@code -rust-allow-stubs}.
     *
     * @param at          the construct
     * @param feature     what is not supported, for the message
     * @param consequence what the generated parser does instead when stubbed
     * @return a Rust statement: a {@code compile_error!} carrying the message,
     *         or a {@code FIXME(congocc)} comment when stubbed
     */
    public String unsupported(Node at, String feature, String consequence) {
        ensureInitialized();
        String message = report(true, SiteKind.FEATURE, at, oneLine(at),
            feature + " are not implemented by the Rust target",
            allowStubs ? consequence : "the generated Rust code contains a compile_error! here (with -rust-allow-stubs, "
                + consequence + ")", CONSTRUCT_REMEDY, STUBBED_CONSTRUCT_REMEDY);
        if (!allowStubs) {
            return compileError(message) + ";\n";
        }
        return "// FIXME(congocc): " + feature + " are not implemented by the Rust target; " + consequence + ".\n";
    }

    /**
     * Handles the assignment of a parsed element ({@code x = NonTerminal},
     * {@code t = <TOKEN>}, {@code @property := ...}).  Assigning a consumed
     * token to a local variable of type Token is supported; everything else
     * needs the Java AST API and is reported.
     *
     * @param expansion the Terminal or NonTerminal with an assignment
     * @param indent    the indentation of the generated statement
     * @param valueVar  for a Terminal, the Rust variable holding the consumed token
     * @return Rust statements (possibly only a comment)
     */
    public String assignment(Expansion expansion, int indent, String valueVar) {
        ensureInitialized();
        Assignment assignment = expansion instanceof NonTerminal nt ? nt.getAssignment()
                              : expansion instanceof Terminal t ? t.getAssignment() : null;
        if (assignment == null) {
            return "";
        }
        String pad = " ".repeat(indent);
        String name = assignment.getName();
        if (expansion instanceof Terminal && valueVar != null && !assignment.isPropertyAssignment()
            && !assignment.isNamedAssignment() && !assignment.isAddTo() && !assignment.isExistenceOf()
            && !assignment.isStringOf() && name != null && !name.contains(".")) {
            Map<String, Local> locals = productionLocals(expansion.getContainingProduction(), true);
            Local local = locals.get(name);
            if (local != null && local.unavailableReason == null && local.type == RustType.TOKEN) {
                return pad + local.rustName + " = " + valueVar + ";\n";
            }
            if (local != null && local.unavailableReason == null && local.type == RustType.OPT_TOKEN) {
                return pad + local.rustName + " = Some(" + valueVar + ");\n";
            }
        }
        boolean relevant = name != null && parseRelevantNames.contains(simpleName(name));
        String what = (name == null ? "?" : name) + " = " + oneLine(expansion);
        String consequence = relevant && !allowStubs ? "the generated Rust code contains a compile_error! here"
                           : "the assignment is not performed by the Rust parser";
        String message = report(relevant, SiteKind.ASSIGNMENT, assignment, what,
            "only a token assigned to a local variable of type Token can be translated; assigning tree nodes "
                + "or properties needs the Java AST API", consequence);
        if (relevant && !allowStubs) {
            return pad + compileError(message) + ";\n";
        }
        return pad + "// FIXME(congocc): assignment '" + what.replace("\n", " ") + "' is not performed.\n";
    }

    // =====================================================================
    // Sites: lexer
    // =====================================================================

    /**
     * Translates the lexical action of a token definition.  The action sees
     * the variables of the Java {@code tokenLexicalActions} method:
     * {@code matchedToken} (an {@code Option<Token>}, which the action can
     * set to null to continue scanning), {@code matchedType},
     * {@code tokenBeginOffset}, {@code matchStart} and {@code matchEnd}.
     */
    public String lexerAction(RegularExpression regexp, int indent) {
        ensureInitialized();
        EmbeddedCode code = regexp.getCodeSnippet();
        String pad = " ".repeat(indent);
        if (code == null) {
            return "";
        }
        if (code instanceof RawCode rc) {
            if (rc.isWrongLanguageIgnore()) {
                return pad + "// The lexical action is written for another target language.\n";
            }
            return pad + rc.toString().trim() + "\n";
        }
        Ctx ctx = new Ctx(Side.LEXER, null, false, null, false, code.getLocation());
        ctx.declare(new Local("matchedToken", "matched_token", RustType.OPT_TOKEN, Place.OPTION_LOCAL, null));
        ctx.declare(new Local("matchedType", "matched_type", RustType.TOKEN_TYPE, Place.LOCAL, null));
        ctx.declare(new Local("tokenBeginOffset", "token_begin_offset", RustType.INT, Place.LOCAL, null));
        ctx.declare(new Local("matchStart", "match_start", RustType.INT, Place.LOCAL, null));
        ctx.declare(new Local("matchEnd", "match_end", RustType.INT, Place.LOCAL, null));
        StringBuilder out = new StringBuilder();
        try {
            translator.statements(blockStatements(code), ctx, out, indent);
            return out.toString();
        } catch (RustTranslationException e) {
            String consequence = allowStubs ? "the lexical action is not executed by the Rust lexer"
                                            : "the generated Rust code contains a compile_error! here";
            String message = report(true, SiteKind.LEXER_ACTION, e.getNode() == null ? code : e.getNode(),
                                    oneLine(code), e.getMessage(), consequence);
            if (!allowStubs) {
                return pad + compileError(message) + ";\n";
            }
            return pad + "// FIXME(congocc): this lexical action could not be translated (" + e.getMessage()
                .replace("\n", " ") + "):\n" + javaAsComment(code, indent);
        }
    }

    // =====================================================================
    // Members
    // =====================================================================

    /** @return the struct fields injected with INJECT PARSER_CLASS, one per line */
    public String parserFieldDeclarations() {
        return fieldDeclarations(Side.PARSER);
    }

    /** @return the initializers of the injected parser fields, one per line */
    public String parserFieldInitializers() {
        return fieldInitializers(Side.PARSER);
    }

    /** @return the methods injected with INJECT PARSER_CLASS, translated */
    public String parserMethods() {
        return memberMethods(Side.PARSER);
    }

    /** @return the struct fields injected with INJECT LEXER_CLASS, one per line */
    public String lexerFieldDeclarations() {
        return fieldDeclarations(Side.LEXER);
    }

    /** @return the initializers of the injected lexer fields, one per line */
    public String lexerFieldInitializers() {
        return fieldInitializers(Side.LEXER);
    }

    /** @return the methods injected with INJECT LEXER_CLASS, translated */
    public String lexerMethods() {
        return memberMethods(Side.LEXER);
    }

    private String fieldDeclarations(Side side) {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        for (FieldInfo f : fields.get(side).values()) {
            if (f.unsupportedReason != null) {
                sb.append("    // FIXME(congocc): the injected field '").append(f.javaName)
                  .append("' is not generated: ").append(f.unsupportedReason.replace("\n", " ")).append('\n');
                continue;
            }
            sb.append("    /// Injected at ").append(f.declaration.getLocation()).append(".\n");
            sb.append("    ").append(f.rustName).append(": ").append(f.type.rust).append(",\n");
        }
        return sb.toString();
    }

    private String fieldInitializers(Side side) {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        for (FieldInfo f : fields.get(side).values()) {
            if (f.unsupportedReason == null) {
                sb.append("            ").append(f.rustName).append(": ").append(f.rustInitializer).append(",\n");
            }
        }
        return sb.toString();
    }

    private String memberMethods(Side side) {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        for (List<MethodInfo> overloads : methods.get(side).values()) {
            for (MethodInfo m : overloads) {
                if (m.rustCode != null) {
                    sb.append('\n').append(m.rustCode);
                } else {
                    sb.append("\n    // FIXME(congocc): the injected method '").append(m.javaName)
                      .append("' is not generated: ").append(m.unsupportedReason.replace("\n", " ")).append('\n');
                    sb.append(javaAsComment(m.declaration, 4));
                }
            }
        }
        for (Node other : otherMembers.get(side)) {
            sb.append("\n    // FIXME(congocc): this injected member is not generated (only fields and methods are supported):\n");
            sb.append(javaAsComment(other, 4));
        }
        return sb.toString();
    }

    // =====================================================================
    // Support code
    // =====================================================================

    /**
     * Generates the {@code java_is_*} functions implementing the
     * {@code Character.isXxx} predicates used by the grammar.  The range
     * tables are computed here, with the JVM's {@link Character} class, so
     * that the Rust predicates agree exactly with the Java parser generated
     * by the same CongoCC run.
     */
    public String supportCode() {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        for (String predicate : usedCharacterPredicates) {
            int[] ranges = characterRanges(predicate);
            String fn = characterHelperName(predicate);
            String table = fn.toUpperCase(Locale.ROOT) + "_RANGES";
            sb.append("/// Code point ranges for which Java's `Character.").append(predicate)
              .append("` is true (computed by CongoCC with Java ").append(System.getProperty("java.version"))
              .append(").\n");
            sb.append("static ").append(table).append(": [u32; ").append(ranges.length).append("] = [");
            for (int i = 0; i < ranges.length; i++) {
                if (i % 8 == 0) sb.append("\n    ");
                sb.append("0x").append(Integer.toHexString(ranges[i])).append(", ");
            }
            sb.append("\n];\n\n");
            sb.append("/// Rust equivalent of Java's `Character.").append(predicate).append("(int codePoint)`.\n");
            sb.append("#[inline]\n");
            sb.append("pub fn ").append(fn).append("(code_point: u32) -> bool {\n");
            sb.append("    check_intervals(&").append(table).append(", code_point)\n");
            sb.append("}\n\n");
        }
        return sb.toString();
    }

    /** @return {@code [lo0, hi0, lo1, hi1, ...]} for the code points where the predicate holds */
    static int[] characterRanges(String predicate) {
        java.util.function.IntPredicate test = switch (predicate) {
            case "isAlphabetic" -> Character::isAlphabetic;
            case "isDigit" -> Character::isDigit;
            case "isIdentifierIgnorable" -> Character::isIdentifierIgnorable;
            case "isISOControl" -> Character::isISOControl;
            case "isJavaIdentifierPart" -> Character::isJavaIdentifierPart;
            case "isJavaIdentifierStart" -> Character::isJavaIdentifierStart;
            case "isLetter" -> Character::isLetter;
            case "isLetterOrDigit" -> Character::isLetterOrDigit;
            case "isLowerCase" -> Character::isLowerCase;
            case "isSpaceChar" -> Character::isSpaceChar;
            case "isTitleCase" -> Character::isTitleCase;
            case "isUnicodeIdentifierPart" -> Character::isUnicodeIdentifierPart;
            case "isUnicodeIdentifierStart" -> Character::isUnicodeIdentifierStart;
            case "isUpperCase" -> Character::isUpperCase;
            case "isWhitespace" -> Character::isWhitespace;
            default -> throw new IllegalArgumentException("unsupported Character predicate " + predicate);
        };
        List<Integer> ranges = new ArrayList<>();
        int start = -1;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT + 1; cp++) {
            boolean in = cp <= Character.MAX_CODE_POINT && (cp < 0xD800 || cp > 0xDFFF) && test.test(cp);
            if (in && start < 0) {
                start = cp;
            } else if (!in && start >= 0) {
                ranges.add(start);
                ranges.add(cp - 1);
                start = -1;
            }
        }
        return ranges.stream().mapToInt(Integer::intValue).toArray();
    }

    /** @return a Rust string literal for any Java string (unpaired surrogates become U+FFFD) */
    public static String stringLiteral(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            clean.appendCodePoint(Character.isSurrogate(s.charAt(i)) && Character.charCount(cp) == 1 ? 0xFFFD : cp);
            i += Character.charCount(cp);
        }
        return "\"" + escapeForRustString(clean.toString()) + "\"";
    }

    // =====================================================================
    // Report for FIXME.md
    // =====================================================================

    /**
     * @return a Markdown section listing every diagnostic, or a sentence
     *         saying that all the grammar's Java code was translated
     */
    public String translationReport() {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        if (diagnostics.isEmpty()) {
            sb.append("All the Java code embedded in the grammar was translated to Rust.\n");
            return sb.toString();
        }
        long errorCount = diagnostics.stream().filter(Diagnostic::error).count();
        sb.append(diagnostics.size()).append(" construct(s) could not be translated (").append(errorCount)
          .append(" error(s), ").append(diagnostics.size() - errorCount).append(" warning(s)")
          .append(allowStubs ? ", generated with -rust-allow-stubs" : "").append(").\n\n");
        sb.append("| Severity | Location | Kind | Java code | Reason | Generated Rust |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Diagnostic d : diagnostics) {
            sb.append("| ").append(d.error() ? "error" : "warning")
              .append(" | ").append(markdownCell(d.location()))
              .append(" | ").append(markdownCell(d.kind().description))
              .append(" | `").append(markdownCell(d.javaSource()).replace("`", "'")).append('`')
              .append(" | ").append(markdownCell(d.reason()))
              .append(" | ").append(markdownCell(d.consequence())).append(" |\n");
        }
        return sb.toString();
    }

    private static String markdownCell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
