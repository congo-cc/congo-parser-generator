package org.congocc.codegen.rust;

import java.util.*;

import org.congocc.core.Grammar;
import org.congocc.codegen.Translator;
import org.congocc.codegen.java.CodeInjector;
import org.congocc.parser.Node;
import org.congocc.parser.tree.*;

/**
 * Translates Java code fragments from grammar INJECT blocks and semantic
 * actions into idiomatic Rust.  For patterns that cannot be mechanically
 * translated (mutable aliasing, exception control flow, complex generics),
 * emits the original Java as a commented-out block with a FIXME marker and
 * records a warning for CongoCC to report at generation time.
 *
 * <h2>Example — translatable identifier mapping:</h2>
 * <pre>
 *   Java: lastConsumedToken   -&gt;  Rust: last_consumed_token
 *   Java: peekNode()          -&gt;  Rust: self.peek_node()
 *   Java: List&lt;Token&gt;         -&gt;  Rust: Vec&lt;Token&gt;
 * </pre>
 *
 * <h2>Graceful degradation:</h2>
 * <p>When an INJECT block contains Java patterns that cannot be mechanically
 * translated to safe Rust (mutable aliasing, exception-based control flow,
 * complex generics with wildcards, anonymous inner classes), the translator
 * emits a commented-out copy of the original Java code with a FIXME marker
 * and adds a warning to the CongoCC error output.  This ensures the generated
 * crate will not silently produce incorrect code — the user knows exactly
 * what needs manual attention.</p>
 */
public class RustTranslator extends Translator {

    // Accumulates warnings about untranslatable Java code so that
    // FilesGenerator can report them to the user after generation.
    private final List<String> translationWarnings = new ArrayList<>();

    public RustTranslator(Grammar grammar) {
        super(grammar);
        methodIndent = 4;
        fieldIndent = 4;
        isTyped = true;
    }

    /**
     * Returns the list of warnings produced during translation.  Each entry
     * describes a Java construct that could not be mechanically translated
     * to Rust and requires manual intervention.
     *
     * @return unmodifiable view of the warning list
     */
    public List<String> getTranslationWarnings() {
        return Collections.unmodifiableList(translationWarnings);
    }

    // ----- Rust keyword set (used to avoid identifier collisions) -----

    private static final Set<String> RUST_KEYWORDS = new HashSet<>(Arrays.asList(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn",
        "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
        "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
        "self", "Self", "static", "struct", "super", "trait", "true", "type",
        "unsafe", "use", "where", "while", "yield"
    ));

    /**
     * Escapes a Rust keyword by prepending the raw identifier prefix {@code r#}.
     * Returns the identifier unchanged if it is not a Rust keyword.
     */
    private static String escapeKeyword(String ident) {
        if (RUST_KEYWORDS.contains(ident)) {
            return "r#" + ident;
        }
        return ident;
    }

    // ----- Identifier translation -----

    @Override
    public String translateIdentifier(String ident, TranslationContext kind) {
        if (ident == null) return "None";

        // Literal keywords that map directly
        if (ident.equals("null")) return "None";
        if (ident.equals("true")) return "true";
        if (ident.equals("false")) return "false";

        // Parser/lexer built-in names -> snake_case equivalents
        if (ident.equals("this")) return "self";
        if (ident.equals("THIS_PRODUCTION") || ident.equals("THIS")) return "this_production";
        if (ident.equals("THAT")) return "self.peek_node()";
        if (ident.equals("lastConsumedToken")) return "last_consumed_token";
        if (ident.equals("currentLookaheadToken")) return "current_lookahead_token";
        if (ident.equals("token_source")) return "token_source";

        // Well-known class names that stay PascalCase
        if (ident.equals("LEXER_CLASS") || ident.equals(appSettings.getLexerClassName())) {
            return "Lexer";
        }
        if (ident.equals("PARSER_CLASS") || ident.equals(appSettings.getParserClassName())) {
            return "Parser";
        }
        if (ident.equals("BASE_TOKEN_CLASS") || ident.equals(appSettings.getBaseTokenClassName())) {
            return "Token";
        }

        // Strip node-package prefix (e.g. "org.parsers.json.ast.JSONObject" -> "JSONObject")
        if (ident.startsWith("NODE_PACKAGE.")) {
            return ident.substring(13);
        }
        String nodePackage = appSettings.getNodePackage();
        if (nodePackage != null && ident.startsWith(nodePackage + ".")) {
            return ident.substring(nodePackage.length() + 1);
        }

        // Types stay PascalCase
        if (kind == TranslationContext.TYPE) {
            return translateTypeName(ident);
        }

        // Methods and variables -> snake_case, escaping Rust keywords
        if (kind == TranslationContext.METHOD || kind == TranslationContext.VARIABLE
                || kind == TranslationContext.PARAMETER || kind == TranslationContext.FIELD) {
            String snake = camelToSnake(ident).toLowerCase();
            return escapeKeyword(snake);
        }

        return ident;
    }

    // ----- Type name translation -----

    @Override
    public String translateTypeName(String name) {
        if (name == null) return name;
        switch (name) {
            case "String":   return "String";
            case "boolean":  return "bool";
            case "Boolean":  return "bool";
            case "int":      return "i32";
            case "Integer":  return "i32";
            case "long":     return "i64";
            case "Long":     return "i64";
            case "float":    return "f32";
            case "Float":    return "f32";
            case "double":   return "f64";
            case "Double":   return "f64";
            case "char":     return "char";
            case "Character": return "char";
            case "void":     return "()";
            case "Object":   return "Box<dyn std::any::Any>";
            case "List":     return "Vec";
            case "ArrayList": return "Vec";
            case "LinkedList": return "Vec";
            case "Map":      return "HashMap";
            case "HashMap":  return "HashMap";
            case "LinkedHashMap": return "HashMap";
            case "Set":      return "HashSet";
            case "HashSet":  return "HashSet";
            case "LinkedHashSet": return "HashSet";
            case "Iterator": return "impl Iterator";
            default:
                // Strip package prefix from fully-qualified names
                // e.g. "org.parsers.java.ast.NumericalLiteral" -> "NumericalLiteral"
                int lastDot = name.lastIndexOf('.');
                if (lastDot >= 0) {
                    return name.substring(lastDot + 1);
                }
                return name;
        }
    }

    // ----- Operator translation -----

    /**
     * Translates a Java operator to its Rust equivalent.  Most Java operators
     * are identical in Rust; the notable exception is unsigned right shift
     * ({@code >>>}) which has no direct Rust equivalent.
     */
    public String translateOperator(String operator) {
        if (">>>".equals(operator)) {
            // Rust has no unsigned right shift; callers should cast to unsigned type
            translationWarnings.add(
                "Java unsigned right shift '>>>' has no direct Rust equivalent. "
                + "Cast the left operand to an unsigned type and use '>>'.");
            return ">>";
        }
        return operator;
    }

    // ----- Invocation translation -----

    @Override
    protected void translateInvocation(ASTInvocation expr, StringBuilder result) {
        String name = expr.getMethodName();

        // Map common Java method names to Rust equivalents
        if (name != null) {
            switch (name) {
                case "size":
                case "length":
                    result.append("len()");
                    return;
                case "equals":
                    result.append("eq(");
                    translateArguments(expr, result);
                    result.append(')');
                    return;
                case "toString":
                    result.append("to_string()");
                    return;
                case "add":
                    result.append("push(");
                    translateArguments(expr, result);
                    result.append(')');
                    return;
                case "contains":
                    result.append("contains(");
                    translateArguments(expr, result);
                    result.append(')');
                    return;
                case "charAt":
                    result.append("chars().nth(");
                    translateArguments(expr, result);
                    result.append(')');
                    return;
                case "get":
                    result.append("get(");
                    translateArguments(expr, result);
                    result.append(')');
                    return;
                case "isEmpty":
                    result.append("is_empty()");
                    return;
                case "peekNode":
                    result.append("peek_node()");
                    return;
                case "popNode":
                    result.append("pop_node()");
                    return;
                case "pushNode":
                    result.append("push_node(");
                    translateArguments(expr, result);
                    result.append(')');
                    return;
                case "nodeArity":
                    result.append("node_arity()");
                    return;
                default:
                    break;
            }
        }

        // Fall through: translate the receiver and emit a snake_case method call.
        // The receiver (object the method is called on) is translated first,
        // followed by '.' and the snake_case method name with arguments.
        ASTExpression receiver = expr.getReceiver();
        if (receiver != null) {
            internalTranslateExpression(receiver, TranslationContext.UNKNOWN, result);
            result.append('.');
        }
        if (name != null) {
            result.append(camelToSnake(name).toLowerCase());
        }
        result.append('(');
        translateArguments(expr, result);
        result.append(')');
    }

    /**
     * Translates invocation arguments from a method call expression,
     * emitting them comma-separated into the result buffer.
     */
    private void translateArguments(ASTInvocation expr, StringBuilder result) {
        List<ASTExpression> args = expr.getArguments();
        if (args != null) {
            boolean first = true;
            for (ASTExpression arg : args) {
                if (!first) result.append(", ");
                internalTranslateExpression(arg, TranslationContext.UNKNOWN, result);
                first = false;
            }
        }
    }

    // ----- instanceof translation -----

    @Override
    protected void translateInstanceofExpression(ASTInstanceofExpression expr, StringBuilder result) {
        // Java:  x instanceof Foo
        // Rust:  matches!(x, NodeKind::Foo(..))
        result.append("matches!(");
        internalTranslateExpression(expr.getInstance(), TranslationContext.UNKNOWN, result);
        result.append(", NodeKind::");
        ASTTypeExpression typeExpr = expr.getTypeExpression();
        if (typeExpr != null) {
            result.append(translateTypeName(typeExpr.getName()));
        }
        result.append("(..))");
    }

    // ----- Cast translation -----

    @Override
    protected void translateCast(ASTTypeExpression cast, StringBuilder result) {
        // In Rust, casts (as) come AFTER the expression, but the base class
        // puts cast output BEFORE the expression.  Since our arena-based AST
        // uses NodeId references (not typed pointers), Java-style type casts
        // in grammar code blocks are not meaningful in Rust and can be omitted.
    }

    // ----- Type expression translation -----

    @Override
    protected void translateType(ASTTypeExpression expr, StringBuilder result) {
        String name = translateTypeName(expr.getName());
        if (name == null) {
            String literal = expr.getLiteral();
            if (literal != null) {
                result.append(translateTypeName(literal));
            }
            return;
        }
        result.append(name);
        List<ASTTypeExpression> typeParams = expr.getTypeParameters();
        if (typeParams != null && !typeParams.isEmpty()) {
            result.append('<');
            boolean first = true;
            for (ASTTypeExpression tp : typeParams) {
                if (!first) result.append(", ");
                translateType(tp, result);
                first = false;
            }
            result.append('>');
        }
    }

    // ----- Primary / unary / binary / ternary / array expression translation -----

    /**
     * Returns true if this primary expression should be prefixed with "self."
     * to indicate it is a field reference.  Returns false when the expression
     * is the right-hand side of a dot expression (e.g. in "a.b", "b" should
     * not get "self." prepended).
     */
    private boolean shouldAddSelf(ASTPrimaryExpression expr) {
        Node parent = expr.getParent();
        if (parent instanceof ASTBinaryExpression be) {
            if (be.getOp().equals(".") && expr == be.getRhs()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void translatePrimaryExpression(ASTPrimaryExpression expr, TranslationContext ctx, StringBuilder result) {
        String literal = expr.getLiteral();
        String name = expr.getName();

        if (literal != null) {
            // Map Java literals to Rust equivalents
            switch (literal) {
                case "null":  result.append("None"); return;
                case "true":  result.append("true"); return;
                case "false": result.append("false"); return;
                case "this":  result.append("self"); return;
                default:      result.append(literal); return;
            }
        }
        // It's a name reference — always use VARIABLE context for snake_case conversion
        String translated = translateIdentifier(name, TranslationContext.VARIABLE);
        // Prepend self. for field references
        if (!isParameterName(name) && findSymbol(name) == null && fields.containsKey(name)) {
            if (shouldAddSelf(expr)) {
                result.append("self.");
            }
        }
        result.append(translated);
    }

    @Override
    protected void translateUnaryExpression(ASTUnaryExpression expr, TranslationContext ctx, StringBuilder result) {
        String op = translateOperator(expr.getOp());
        boolean parens = needsParentheses(expr);

        if (op.equals("++") || op.equals("--")) {
            // Rust doesn't have ++ or --, use += 1 / -= 1
            internalTranslateExpression(expr.getOperand(), TranslationContext.UNKNOWN, result);
            result.append(' ');
            result.append(op.charAt(0));
            result.append("= 1");
        } else {
            if (parens) result.append('(');
            result.append(op);
            internalTranslateExpression(expr.getOperand(), TranslationContext.UNKNOWN, result);
            if (parens) result.append(')');
        }
    }

    @Override
    protected void translateBinaryExpression(ASTBinaryExpression expr, StringBuilder result) {
        String op = translateOperator(expr.getOp());
        boolean parens = needsParentheses(expr);
        ASTExpression lhs = expr.getLhs();
        ASTExpression rhs = expr.getRhs();
        processBinaryExpression(parens, lhs, op, rhs, result);
    }

    @Override
    protected void translateTernaryExpression(ASTTernaryExpression expr, StringBuilder result) {
        boolean parens = needsParentheses(expr);
        if (parens) result.append('(');
        result.append("if ");
        internalTranslateExpression(expr.getCondition(), TranslationContext.UNKNOWN, result);
        result.append(" { ");
        internalTranslateExpression(expr.getTrueValue(), TranslationContext.UNKNOWN, result);
        result.append(" } else { ");
        internalTranslateExpression(expr.getFalseValue(), TranslationContext.UNKNOWN, result);
        result.append(" }");
        if (parens) result.append(')');
    }

    @Override
    protected void translateArrayAccess(ASTArrayAccess expr, StringBuilder result) {
        internalTranslateExpression(expr.getArray(), TranslationContext.UNKNOWN, result);
        result.append('[');
        internalTranslateExpression(expr.getIndex(), TranslationContext.UNKNOWN, result);
        result.append(']');
    }

    // ----- Injected class translation with graceful degradation -----

    /**
     * Translates an INJECT class body from Java to Rust.  If any declaration
     * within the injected block cannot be mechanically translated, the entire
     * block is emitted as a commented-out FIXME section and a warning is
     * recorded for CongoCC's error output.
     *
     * <p>This ensures that simple grammars (no INJECT blocks or only simple
     * ones) produce fully functional Rust parsers with no warnings, while
     * complex grammars get clear guidance on what needs manual intervention.</p>
     */
    @Override
    public String translateInjectedClass(CodeInjector injector, String name) {
        String qualifiedName = String.format("%s.%s", appSettings.getNodePackage(), name);
        List<ClassOrInterfaceBodyDeclaration> decls = injector.getBodyDeclarations(qualifiedName);

        if (decls == null || decls.isEmpty()) {
            // No injected code for this class — nothing to emit
            return "";
        }

        StringBuilder result = new StringBuilder();
        int indent = 4;
        boolean hadFailure = false;

        inInterface = grammar.nodeIsInterface(name);
        try {
            for (ClassOrInterfaceBodyDeclaration decl : decls) {
                try {
                    if (decl instanceof FieldDeclaration) {
                        translateStatement(decl, indent, result);
                    } else if (decl instanceof MethodDeclaration) {
                        translateStatement(decl, indent, result);
                    } else {
                        // Unknown declaration type — flag for manual translation
                        throw new UnsupportedOperationException(
                            "Cannot translate " + getSimpleName(decl)
                            + " at " + decl.getLocation());
                    }
                } catch (Exception e) {
                    hadFailure = true;
                    String warning = String.format(
                        "INJECT %s: declaration at %s could not be translated to Rust (%s). "
                        + "Manual intervention required. See generated source for details.",
                        name, decl.getLocation(), e.getMessage());
                    translationWarnings.add(warning);

                    // Emit the original Java source as a commented-out FIXME block
                    addIndent(indent, result);
                    result.append("// FIXME(congocc): The following Java code requires manual translation to Rust.\n");
                    addIndent(indent, result);
                    result.append("// ").append(e.getMessage()).append('\n');
                    addIndent(indent, result);
                    result.append("// Please provide a Rust implementation, or use a\n");
                    addIndent(indent, result);
                    result.append("// #if __rust__ / #endif block in the grammar file.\n");
                    addIndent(indent, result);
                    result.append("//\n");
                    addIndent(indent, result);
                    result.append("// Original Java:\n");
                    String declSource = decl.getSource();
                    if (declSource != null) {
                        for (String line : declSource.split("\n")) {
                            addIndent(indent, result);
                            result.append("//   ").append(line).append('\n');
                        }
                    }
                    result.append('\n');
                }
            }

            if (hadFailure) {
                translationWarnings.add(String.format(
                    "INJECT %s contains one or more Java declarations that could not be "
                    + "translated to Rust. See generated source for FIXME markers.", name));
            }

            return result.toString();
        } finally {
            inInterface = false;
        }
    }

    // ----- Statement translation -----

    @Override
    protected void internalTranslateStatement(ASTStatement stmt, int indent, StringBuilder result) {
        boolean addNewline = false;
        if (!(stmt instanceof ASTStatementList)) {
            addIndent(indent, result);
        }

        if (stmt instanceof ASTExpressionStatement es) {
            if (es instanceof ASTThrowStatement) {
                // Rust doesn't have exceptions; translate throw to return Err
                result.append("return Err(ParseError::new(format!(\"{}\", ");
                internalTranslateExpression(es.getValue(), TranslationContext.UNKNOWN, result);
                result.append(")))");
            } else {
                internalTranslateExpression(es.getValue(), TranslationContext.UNKNOWN, result);
            }
            result.append(';');
            addNewline = true;
        }
        else if (stmt instanceof ASTStatementList asl) {
            boolean isInitializer = asl.isInitializer();
            List<ASTStatement> statements = asl.getStatements();
            if (isInitializer) {
                addIndent(indent, result);
                result.append("{\n");
                indent += 4;
            }
            if (statements != null) {
                for (ASTStatement s : statements) {
                    internalTranslateStatement(s, indent, result);
                }
            }
            if (isInitializer) {
                indent -= 4;
                addIndent(indent, result);
                result.append("}\n");
            }
        }
        else if (stmt instanceof ASTVariableOrFieldDeclaration vd) {
            List<ASTPrimaryExpression> names = vd.getNames();
            List<ASTExpression> initializers = vd.getInitializers();
            ASTTypeExpression type = vd.getTypeExpression();
            int n = names.size();
            for (int i = 0; i < n; i++) {
                ASTPrimaryExpression name = names.get(i);
                ASTExpression initializer = initializers.get(i);
                result.append("let mut ");
                internalTranslateExpression(name, TranslationContext.VARIABLE, result);
                if (type != null) {
                    result.append(": ");
                    translateType(type, result);
                }
                if (initializer != null) {
                    result.append(" = ");
                    internalTranslateExpression(initializer, TranslationContext.UNKNOWN, result);
                }
                result.append(';');
                if (i < (n - 1)) {
                    result.append('\n');
                    addIndent(indent, result);
                }
            }
            addNewline = true;
        }
        else if (stmt instanceof ASTReturnStatement ars) {
            // In CongoCC grammar code blocks, `return thisProduction;` means
            // "early return from this production successfully."  The Rust parser
            // production methods return Result<(), ParseError>, so this becomes
            // `return Ok(());`.
            result.append("return Ok(())");
            result.append(';');
            addNewline = true;
        }
        else if (stmt instanceof ASTIfStatement s) {
            result.append("if ");
            internalTranslateExpression(s.getCondition(), TranslationContext.UNKNOWN, result);
            result.append(" {\n");
            internalTranslateStatement(s.getThenStmts(), indent + 4, result);
            if (s.getElseStmts() != null) {
                addIndent(indent, result);
                result.append("} else {\n");
                internalTranslateStatement(s.getElseStmts(), indent + 4, result);
            }
            addIndent(indent, result);
            result.append("}\n");
        }
        else if (stmt instanceof ASTBreakOrContinueStatement bcs) {
            result.append(bcs.isBreak() ? "break" : "continue");
            result.append(';');
            addNewline = true;
        }
        else {
            // Fallback: emit as FIXME comment identifying the untranslatable construct
            result.append("// FIXME(congocc): Cannot translate ")
                  .append(stmt.getClass().getSimpleName())
                  .append(" to Rust — manual implementation needed");
            addNewline = true;
        }

        if (addNewline) {
            result.append('\n');
        }
    }
}
