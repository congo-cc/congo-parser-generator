package org.congocc.codegen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.congocc.app.*;
import org.congocc.core.*;
import org.congocc.codegen.rust.RustTranslator;
import org.congocc.parser.Node;
import static org.congocc.parser.Node.CodeLang.*;
import org.congocc.parser.tree.*;


/**
 * Class to hold various methods and variables
 * that are exposed to the template layer
 */
@SuppressWarnings("unused")
public class TemplateGlobals {

    private final Grammar grammar;
    private final AppSettings appSettings;
    private Translator translator;

    public TemplateGlobals(Grammar grammar) {
        this.grammar = grammar;
        this.appSettings = grammar.getAppSettings();
    }

    public void setTranslator(Translator translator) {this.translator = translator;}

    public boolean nodeIsInterface(String nodeName) {
        return grammar.nodeIsInterface(nodeName);
    }

    public String addEscapes(String str) {
        // TODO delegate to code in Lexer
        StringBuilder retval = new StringBuilder();
        for (int ch : str.codePoints().toArray()) {
            switch (ch) {
                case '\b' -> retval.append("\\b");
                case '\t' -> retval.append("\\t");
                case '\n' -> retval.append("\\n");
                case '\f' -> retval.append("\\f");
                case '\r' -> retval.append("\\r");
                case '\"' -> retval.append("\\\"");
                case '\'' -> retval.append("\\'");
                case '\\' -> retval.append("\\\\");
                default -> {
                    if (Character.isISOControl(ch)) {
                        String s = "0000" + java.lang.Integer.toString(ch, 16);
                        retval.append("\\u").append(s.substring(s.length() - 4));
                    } else {
                        retval.appendCodePoint(ch);
                    }
                }
            }
        }
        return retval.toString();
    }

    // For use from templates.
    public String getPreprocessorSymbol(String key, String defaultValue) {
        return grammar.getPreprocessorSymbols().getOrDefault(key, defaultValue);
    }

    public String getStringSetting(String name, String defaultValue) {
        return grammar.getAppSettings().getStringSetting(name, defaultValue);
    }

    public String getParserOutputDirectory() throws IOException {
        return appSettings.getParserOutputDirectory().getFileName().toString();
    }

    /**
     * @return a function that coverts a character to a displayable string
     *         in generated Java code. Rather than display the
     *         integer 97, we display 'a', for example.
     */
    public Function<Integer, String> getDisplayChar() {
        return this::displayChar;
    }

    public String displayChar(int ch) {
        return switch (ch) {
            case '\'' -> "'\\''";
            case '\\' -> "'\\\\'";
            case '\t' -> "'\\t'";
            case '\r' -> "'\\r'";
            case '\n' -> "'\\n'";
            case '\f' -> "'\\f'";
            case ' '  -> "' '";
            default -> {
                if (ch < 128 && !Character.isWhitespace(ch) && !Character.isISOControl(ch))
                    yield "'" + (char) ch + "'";
                String s = "0x" + Integer.toHexString(ch);
                if (appSettings.getCodeLang() == PYTHON) {
                    s = String.format("as_chr(%s)", s);
                }
                yield s;
            }
        };
    }

    // The following methods added for supporting generation in languages other than
    // Java. (It is only called from non-Java-generating templates, i.e. .cs.ctl and .py.ctl)
    public Map<String, Object> tokenSubClassInfo() {
        Map<String, String> tokenClassMap = new HashMap<>();
        Map<String, String> superClassMap = new HashMap<>();
        // List<String> classes = new ArrayList<>();

        for (RegularExpression re : grammar.getLexerData().getOrderedNamedTokens()) {
            if (re.isPrivate())
                continue;
            String tokenClassName = re.getGeneratedClassName();
            String superClassName = re.getGeneratedSuperClassName();

            if (superClassName == null) {
                superClassName = "Token";
            } else {
                if (!superClassMap.containsKey(superClassName)) {
                    // classes.add(superClassName);
                    superClassMap.put(superClassName, null); // TODO not always!
                }
            }
            if (!tokenClassMap.containsKey(tokenClassName)) {
                // classes.add(tokenClassName);
                tokenClassMap.put(tokenClassName, superClassName);
            }
        }
        // Sort out superclasses' superclasses
        String pkg = appSettings.getNodePackage();
        for (String key : superClassMap.keySet()) {
            String qualifiedName = String.format("%s.%s", pkg, key);
            Set<ObjectType> extendsList = grammar.getInjector().getExtendsList(qualifiedName);

            if ((extendsList == null) || (extendsList.size() == 0)) {
                superClassMap.put(key, "Token");
            } else {
                superClassMap.put(key, extendsList.iterator().next().toString());
            }
        }
        tokenClassMap.putAll(superClassMap);

        // Topologically sort classes
        Sequencer seq = new Sequencer();
        for (Map.Entry<String, String> entry : tokenClassMap.entrySet()) {
            seq.addNode(entry.getKey());
            seq.addNode(entry.getValue());
            seq.add(entry.getKey(), entry.getValue());
        }
        List<String> sorted = seq.steps("Token");
        sorted.remove(0);
        Map<String, Object> result = new HashMap<>();
        result.put("sortedNames", sorted);
        result.put("tokenClassMap", tokenClassMap);
        return result;
    }

    // Used in templates specifically for method name translation
    public String translateIdentifier(String ident) {
        return translator.translateIdentifier(ident, Translator.TranslationContext.METHOD);
    }

    // Used in templates for side effects, hence returning empty string
    @SuppressWarnings("SameReturnValue")
    public String startProduction() {
        Translator.SymbolTable symbols = new Translator.SymbolTable();
        translator.pushSymbols(symbols);
        return "";
    }

    // Used in templates for side effects, hence returning empty string
    @SuppressWarnings("SameReturnValue")
    public String endProduction() {
        translator.popSymbols();
        translator.clearParameterNames();
        return "";
    }

    public String translateParameters(FormalParameters parameters) {
        StringBuilder sb = new StringBuilder();
        translator.translateFormals(parameters.childrenOfType(FormalParameter.class), null, sb);
        return sb.toString();
    }

    public String translateExpression(Node expr) {
        StringBuilder result = new StringBuilder();
        translator.translateExpression(expr, result);
        return result.toString();
    }

    /**
     * Translates a Java expression to the target language with a safe fallback
     * for Rust.  If translation fails or produces obviously broken Rust code,
     * returns the given {@code fallback} value with a FIXME comment containing
     * the original Java source.  For non-Rust targets, delegates to
     * {@link #translateExpression(Node)}.
     *
     * @param expr     the Java expression AST node to translate
     * @param fallback the Rust expression to emit when translation fails
     *                 (e.g., "true" for boolean contexts)
     * @return the translated expression, or fallback with FIXME comment
     */
    public String translateExpressionSafe(Node expr, String fallback) {
        if (!(translator instanceof RustTranslator)) {
            return translateExpression(expr);
        }
        // Check the original Java source for patterns that are known to produce
        // broken Rust code (e.g., instanceof checks, parser API calls).  This
        // catches cases where the translator "succeeds" but the output references
        // undefined variables or types.
        String javaSource = (expr != null) ? expr.getSource() : null;
        if (javaSource != null && looksLikeUntranslatableJava(javaSource)) {
            String src = javaSource.replace("*/", "* /").replace("\n", " ").trim();
            return fallback + " /* FIXME(congocc): " + src + " */";
        }
        try {
            String translated = translateExpression(expr);
            if (looksLikeBadRustTranslation(translated)) {
                String src = (javaSource != null)
                    ? javaSource.replace("*/", "* /").replace("\n", " ").trim()
                    : "untranslatable expression";
                return fallback + " /* FIXME(congocc): " + src + " */";
            }
            return translated;
        } catch (Exception e) {
            String src = (javaSource != null)
                ? javaSource.replace("*/", "* /").replace("\n", " ").trim()
                : "expression translation failed";
            return fallback + " /* FIXME(congocc): " + src + " */";
        }
    }

    /**
     * Checks the original Java source of an expression for patterns that cannot
     * produce valid Rust code — e.g., instanceof checks that reference Java's
     * per-type class hierarchy, or calls to parser API methods that don't exist
     * in the Rust parser struct.
     */
    private static boolean looksLikeUntranslatableJava(String javaSource) {
        // instanceof checks reference Java class hierarchy; in Rust, the arena
        // model uses NodeKind enum matching which requires different variable scope
        if (javaSource.contains("instanceof")) return true;
        // Java parser API methods with no Rust equivalent
        if (javaSource.contains("peekNode") || javaSource.contains("popNode")) return true;
        if (javaSource.contains("tokenImage") || javaSource.contains("getTokenType")) return true;
        if (javaSource.contains("getToken(")) return true;
        if (javaSource.contains("lastConsumedToken")) return true;
        if (javaSource.contains("permissibleModifiers")) return true;
        if (javaSource.contains("isInProduction")) return true;
        if (javaSource.contains("EnumSet.of")) return true;
        if (javaSource.contains(".equals(")) return true;
        if (javaSource.contains("hasMatch(")) return true;
        return false;
    }

    // Regex patterns indicating broken Java-to-Rust translation output.
    // Double method call: some_method.some_method( — from incorrect camelCase decomposition.
    // Matches with or without a leading dot.
    private static final Pattern DOUBLE_METHOD_PATTERN =
        Pattern.compile("\\b([a-z_]+)\\.\\1\\(");
    // All-caps Java identifiers incorrectly snake_cased: p_u_b_l_i_c, f_i_n_a_l, etc.
    private static final Pattern OVER_SNAKE_CASED_PATTERN =
        Pattern.compile("\\b[a-z]_[a-z]_[a-z]_[a-z]\\b");

    /**
     * Heuristic check for Java-to-Rust translations that are syntactically
     * present but semantically broken — e.g., references to Java parser fields
     * that don't exist in Rust, incorrectly translated method calls, etc.
     */
    // Variable declarations with Java AST types that don't exist in Rust
    private static final Pattern JAVA_TYPE_DECL_PATTERN =
        Pattern.compile("let\\s+(?:mut\\s+)?\\w+:\\s*(?:Expression|Node|Statement|Token)\\b");

    private static boolean looksLikeBadRustTranslation(String code) {
        if (code.contains("FIXME(congocc)")) return true;
        if (DOUBLE_METHOD_PATTERN.matcher(code).find()) return true;
        if (code.contains("enum_set.")) return true;
        if (OVER_SNAKE_CASED_PATTERN.matcher(code).find()) return true;
        // Parser-internal method calls that don't exist in Rust
        if (code.contains("peek_node()") || code.contains("pop_node()")) return true;
        // Java .equals() translated to standalone eq() function
        if (code.contains("eq(")) return true;
        // Java parser API references that have no Rust equivalent
        if (code.contains("token_image") || code.contains("get_token_type")
            || code.contains("get_token(") || code.contains("last_consumed_token")
            || code.contains("is_in_production") || code.contains("this_production")
            || code.contains("permissible_modifiers") || code.contains("contains(")) return true;
        // Variable declarations using Java AST types (no per-type classes in Rust)
        if (JAVA_TYPE_DECL_PATTERN.matcher(code).find()) return true;
        return false;
    }

    private void translateStatements(Node node, int indent, StringBuilder result) {
        if (node instanceof Statement) {
            translator.translateStatement(node, indent, result);
        } else {
            for (Node child : node) {
                if (child instanceof Delimiter) {
                    continue; // could put in more checks here
                }
                translator.translateStatement(child, indent, result);
            }
        }
    }

    public String translateCodeBlock(Node javaCodeBlock, int indent) {
        if (javaCodeBlock == null) return "";
        StringBuilder result = new StringBuilder();
        Translator.SymbolTable syms = new Translator.SymbolTable();
        translator.pushSymbols(syms);
        if (translator instanceof RustTranslator) {
            translateCodeBlockForRust(javaCodeBlock, indent, result);
        } else {
            translateStatements(javaCodeBlock, indent, result);
        }
        translator.popSymbols();
        return result.toString();
    }

    /**
     * Translates a Java code block for Rust with FIXME fallback.  If the
     * translation produces obviously broken Rust (references to Java-only
     * parser fields, incorrect method translations, etc.), emits the original
     * Java source as a block comment with a FIXME marker instead.
     */
    private void translateCodeBlockForRust(Node codeBlock, int indent, StringBuilder result) {
        // Check original Java source for patterns known to be untranslatable
        String javaSource = codeBlock.getSource();
        if (javaSource != null && looksLikeUntranslatableJava(javaSource)) {
            emitCodeBlockFIXME(codeBlock, indent, result);
            return;
        }
        try {
            StringBuilder buf = new StringBuilder();
            translateStatements(codeBlock, indent, buf);
            String translated = buf.toString();
            if (looksLikeBadRustTranslation(translated)) {
                emitCodeBlockFIXME(codeBlock, indent, result);
            } else {
                result.append(translated);
            }
        } catch (Exception e) {
            emitCodeBlockFIXME(codeBlock, indent, result);
        }
    }

    /**
     * Emits a FIXME block comment containing the original Java source from a
     * code action that could not be translated to Rust.  The block comment is
     * valid Rust syntax anywhere (inside functions, match arms, etc.).
     */
    // Matches Java variable declarations: boolean varName, int varName, String varName
    private static final Pattern JAVA_VAR_DECL_PATTERN =
        Pattern.compile("\\b(boolean|int|long|String)\\s+([a-zA-Z_]\\w*)\\b");

    private void emitCodeBlockFIXME(Node codeBlock, int indent, StringBuilder result) {
        String pad = " ".repeat(Math.max(indent * 4, 8));
        String src = codeBlock.getSource();
        if (src != null && !src.isEmpty()) {
            // Emit default variable declarations for any variables defined in the
            // FIXME'd code block, so subsequent code that references them compiles.
            if (translator instanceof RustTranslator) {
                emitDefaultDeclarationsForFIXME(src, pad, result);
            }
            // Escape any existing block comment delimiters in the Java source
            src = src.replace("*/", "* /");
            result.append(pad).append("/* FIXME(congocc): Java code action not yet translated to Rust\n");
            for (String line : src.split("\n")) {
                result.append(pad).append("   ").append(line).append('\n');
            }
            result.append(pad).append("*/\n");
        }
    }

    /**
     * Scans Java source for variable declarations and emits Rust default
     * declarations for them.  This ensures that subsequent code referencing
     * these variables (e.g., non-terminal arguments) still compiles even
     * though the code block was replaced by a FIXME comment.
     */
    private void emitDefaultDeclarationsForFIXME(String javaSource, String pad, StringBuilder result) {
        java.util.regex.Matcher m = JAVA_VAR_DECL_PATTERN.matcher(javaSource);
        while (m.find()) {
            String javaType = m.group(1);
            String javaName = m.group(2);
            String rustType, rustDefault;
            switch (javaType) {
                case "boolean": rustType = "bool"; rustDefault = "false"; break;
                case "int": rustType = "i32"; rustDefault = "0"; break;
                case "long": rustType = "i64"; rustDefault = "0"; break;
                case "String": rustType = "String"; rustDefault = "String::new()"; break;
                default: continue;
            }
            String rustName = translator.translateIdentifier(javaName, Translator.TranslationContext.VARIABLE);
            result.append(pad).append("let mut ").append(rustName).append(": ")
                  .append(rustType).append(" = ").append(rustDefault).append(";\n");
        }
    }

    // used in templates
    public String translateNonterminalArgs(InvocationArguments args) {
        // The args are passed through as a string, but need to be translated according
        // to the language
        // being generated. For the Java template, they don't come through this method -
        // they are passed
        // straight through as a string by the Java template.
        return (args == null) ? "" : translator.translateNonterminalArgs(args);
    }

    // used in templates
    public String translateInjectedClass(String name) {
        String result;

        translator.startClass(name, false, null);
        result = translator.translateInjectedClass(grammar.getInjector(), name);
        translator.endClass(name, false, null);
        return result;
    }

    public String translateInjections(String className, boolean fields, boolean initializers) {
        StringBuilder result = new StringBuilder();
        if (fields) {
            translator.clearFields();
        }
//        String cn = lastPart(className, '.');
        String cn = className.substring(className.lastIndexOf('.')+1);
        translator.startClass(cn, fields, result);
        try {
            List<ClassOrInterfaceBodyDeclaration> declsToProcess = grammar.getInjector().getBodyDeclarations(className);
            if (declsToProcess != null) {
                int fieldIndent = translator.getFieldIndent();
                int methodIndent = translator.getMethodIndent();
                for (ClassOrInterfaceBodyDeclaration decl : declsToProcess) {
                    // If processing fields, we want to process FieldDeclarations or Initializers.
                    // Otherwise, we want to process TypeDeclarations, MethodDeclarations and
                    // ConstructorDeclarations
                    boolean process = (fields == (decl instanceof FieldDeclaration || decl instanceof Initializer));
                    if (process) {
                        if (decl instanceof FieldDeclaration || decl instanceof CodeBlock) {
                            if ((decl instanceof Initializer) && !initializers) {
                                continue;
                            }
                            translator.translateStatement(decl, fieldIndent, result);
                        } else if (decl instanceof MethodDeclaration || decl instanceof ConstructorDeclaration ||
                                decl instanceof EnumDeclaration || decl instanceof ClassDeclaration) {
                            translator.translateStatement(decl, methodIndent, result);
                        } else {
                            String s = String.format("Unable to translate %s at %s", Translator.getSimpleName(decl), decl.getLocation());
                            throw new UnsupportedOperationException(s);
                        }
                    }
                }
            }
        } finally {
            translator.endClass(cn, fields, result);
        }
        return result.toString();
    }

    protected String translateInitializers(String className) {
        StringBuilder result = new StringBuilder();
        List<ClassOrInterfaceBodyDeclaration> declsToProcess = grammar.getInjector().getBodyDeclarations(className);
        if (declsToProcess != null) {
            int fieldIndent = translator.getFieldIndent();
            for (ClassOrInterfaceBodyDeclaration decl : declsToProcess) {
                if (decl instanceof Initializer) {
                    translator.translateStatement(decl, fieldIndent, result);
                }
            }
        }
        return result.toString();
    }

    public List<String> injectedFieldNames(String className) {
        ArrayList<String> result = new ArrayList<>();
        Map<String, List<ClassOrInterfaceBodyDeclaration>> bodyDeclarations = grammar.getInjector().getBodyDeclarations();
        List<ClassOrInterfaceBodyDeclaration> declsToProcess = bodyDeclarations.get(className);
        if (declsToProcess != null) {
            for (ClassOrInterfaceBodyDeclaration decl : declsToProcess) {
                if ((decl instanceof MethodDeclaration) ||
                        (decl instanceof ConstructorDeclaration) ||
                        (decl instanceof Initializer) ||
                        (decl instanceof EnumDeclaration) ||
                        (decl instanceof ClassDeclaration)) {
                    continue;
                }
                if (decl instanceof FieldDeclaration) {
                    ArrayList<String> names = new ArrayList<>();
                    for (Node child : decl.children()) {
                        if (child instanceof Identifier) {
                            names.add(((Identifier) child).toString());
                        }
                        else if (child instanceof VariableDeclarator) {
                            Identifier ident = child.firstDescendantOfType(Identifier.class);
                            if (ident == null) {
                                String s = String.format("Identifier not found for %s at %s", Translator.getSimpleName(child), child.getLocation());
                                throw new UnsupportedOperationException(s);
                            }
                            names.add(ident.toString());
                        }
                    }
                    if (names.size() == 0) {
                        String s = String.format("No names found for %s at %s", Translator.getSimpleName(decl), decl.getLocation());
                        throw new UnsupportedOperationException(s);
                    }
                    for (String name : names) {
                        result.add(translator.translateIdentifier(name,
                                Translator.TranslationContext.VARIABLE));
                    }
                }
                else {
                    String s = String.format("Unable to process %s at %s", Translator.getSimpleName(decl), decl.getLocation());
                    throw new UnsupportedOperationException(s);
                }
            }
        }
        return result;
    }

    public List<String> injectedTokenFieldNames() {
        String className = String.format("%s.Token", appSettings.getParserPackage());
        return injectedFieldNames(className);
    }

    public List<String> injectedLexerFieldNames() {
        String className = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getLexerClassName());
        return injectedFieldNames(className);
    }

    // used in templates
    public List<String> injectedParserFieldNames() {
        String className = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getParserClassName());
        return injectedFieldNames(className);
    }

    // used in templates
    public String translateNestedTypes(String className, boolean fields) {
        className = String.format("%s.%s", appSettings.getNodePackage(), className);
        return translateInjections(className, fields, false);
    }

    // used in templates
    public String translateTokenInjections(boolean fields) {
        String className = String.format("%s.Token", appSettings.getParserPackage());
        return translateInjections(className, fields, fields && translator.isIncludeInitializers());
    }

    // used in templates
    public String translateLexerInjections(boolean fields) {
        String className = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getLexerClassName());
        return translateInjections(className, fields, fields && translator.isIncludeInitializers());
    }

    // used in templates
    public String translateParserInjections(boolean fields) {
        String className = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getParserClassName());
        return translateInjections(className, fields, fields && translator.isIncludeInitializers());
    }

    // used in Rust inject.rs.ctl template — includes original Java source in FIXME comments
    public String translateParserClassInjection(boolean fields) {
        if (translator instanceof org.congocc.codegen.rust.RustTranslator rustTranslator) {
            return rustTranslator.translateParserClassInjection(grammar.getInjector(), fields);
        }
        // Fall back to the generic path for non-Rust translators
        return translateParserInjections(fields);
    }

    // used in templates
    public String translateLexerInitializers() {
        String className = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getLexerClassName());
        return translateInitializers(className);
    }

    // used in templates
    public String translateParserInitializers() {
        String className = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getParserClassName());
        return translateInitializers(className);
    }

    // used in templates
    public String translateTokenSubclassInjections(String className, boolean fields) {
        className = String.format("%s.%s", appSettings.getNodePackage(), className);
        return translateInjections(className, fields, fields && translator.isIncludeInitializers());
    }

    // used in templates
    public String translateType(String type) {
        return translator.translateTypeName(type);
    }

    // used in templates
    public String translateModifiers(String modifiers) {
        return modifiers;
    }

    protected void processImports(Set<ImportDeclaration> imports, StringBuilder result) {
        String prefix = String.format("%s.", appSettings.getNodePackage());
        for (ImportDeclaration decl : imports) {
            String name = decl.get(1).toString();
            if (name.startsWith("java.") || name.startsWith(prefix)) {
                continue;
            }
            translator.translateImport(name, result);
        }
    }

    // used in templates
    public String translateLexerImports() {
        StringBuilder result = new StringBuilder();
        String cn = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getLexerClassName());
        Set<ImportDeclaration> imports = grammar.getInjector().getImportDeclarations(cn);

        if (imports != null) {
            processImports(imports, result);
        }
        return result.toString();
    }

    // used in templates
    public String translateParserImports() {
        StringBuilder result = new StringBuilder();
        String cn = String.format("%s.%s", appSettings.getParserPackage(), appSettings.getParserClassName());
        Set<ImportDeclaration> imports = grammar.getInjector().getImportDeclarations(cn);

        if (imports != null) {
            processImports(imports, result);
        }
        return result.toString();
    }

    public List<String> getSortedNodeClassNames() {
        Sequencer seq = new Sequencer();
        String pkg = appSettings.getNodePackage();
        String bnn = appSettings.getBaseNodeClassName();

        seq.addNode(bnn);
        for (String cn : grammar.getNodeNames()) {
            String qn = String.format("%s.%s", pkg, cn);
            Set<ObjectType> elist = grammar.getInjector().getExtendsList(qn);
            Set<ObjectType> ilist = grammar.getInjector().getImplementsList(qn);
            List<String> preds = new ArrayList<>();
            if (elist != null) {
                for (ObjectType ot : elist) {
                    preds.add(ot.toString());
                }
            }
            if (ilist != null) {
                for (ObjectType ot : ilist) {
                    preds.add(ot.toString());
                }
            }
            if (preds.isEmpty()) {
                preds.add(bnn);
            }
            for (String pn : preds) {
                seq.addNode(pn);
                seq.addNode(cn);
                seq.add(cn, pn); // Add in reverse order
            }
        }
        List<String> result = seq.steps(bnn);
        result.remove(0); // The bnn value
        return result;
    }
}