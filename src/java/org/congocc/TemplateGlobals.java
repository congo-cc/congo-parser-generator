package org.congocc;

import java.util.*;

import org.congocc.output.Sequencer;
import org.congocc.core.*;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;
import org.congocc.output.Translator;

/**
 * Class to hold various methods and variables
 * that are exposed to the template layer
 */
public class TemplateGlobals {

    private Grammar grammar;
    private List<String> nodeVariableNameStack = new ArrayList<>();
    private LexerData lexerData;

    TemplateGlobals(Grammar grammar) {
        this.grammar = grammar;
        this.lexerData = grammar.getLexerData();
    }

    public void pushNodeVariableName(String nodeName) {
        nodeVariableNameStack.add(nodeName);
    }

    public void popNodeVariableName() {
        nodeVariableNameStack.remove(nodeVariableNameStack.size() - 1);
    }

    public String toHexString(int i) {
        return "0x" + Integer.toHexString(i);
    }

    public String toHexStringL(long l) {
        return "0x" + Long.toHexString(l) + "L";
    }

    public String toOctalString(int i) {
        return "\\" + Integer.toOctalString(i);
    }

    public String lastPart(String source, int delimiter) {
        int i = source.lastIndexOf(delimiter);
        if (i < 0) {
            return source;
        }
        return source.substring(i + 1);
    }

    public boolean nodeIsInterface(String nodeName) {
        return grammar.nodeIsInterface(nodeName);
    }

    public String addEscapes(String str) {
        // TODO delegate to code in Lexer
        StringBuilder retval = new StringBuilder();
        for (int ch : str.codePoints().toArray()) {
            switch (ch) {
                case '\b':
                    retval.append("\\b");
                    continue;
                case '\t':
                    retval.append("\\t");
                    continue;
                case '\n':
                    retval.append("\\n");
                    continue;
                case '\f':
                    retval.append("\\f");
                    continue;
                case '\r':
                    retval.append("\\r");
                    continue;
                case '\"':
                    retval.append("\\\"");
                    continue;
                case '\'':
                    retval.append("\\\'");
                    continue;
                case '\\':
                    retval.append("\\\\");
                    continue;
                default:
                    if (Character.isISOControl(ch)) {
                        String s = "0000" + java.lang.Integer.toString(ch, 16);
                        retval.append("\\u" + s.substring(s.length() - 4, s.length()));
                    } else {
                        retval.appendCodePoint(ch);
                    }
                    continue;
            }
        }
        return retval.toString();
    }

    // For use from templates.
    public String getPreprocessorSymbol(String key, String defaultValue) {
        return grammar.getPreprocessorSymbols().getOrDefault(key, defaultValue);
    }

    /**
     * @param ch the code point. If it is not ASCII, we just display the integer in
     *           hex.
     * @return a String to use in generated Java code. Rather than display the
     *         integer 97, we display 'a',
     *         for example.
     */

    public String displayChar(int ch) {
        String s;

        if (ch == '\'')
            return "\'\\'\'";
        if (ch == '\\')
            return "\'\\\\\'";
        if (ch == '\t')
            return "\'\\t\'";
        if (ch == '\r')
            return "\'\\r\'";
        if (ch == '\n')
            return "\'\\n\'";
        if (ch == '\f')
            return "\'\\f\'";
        if (ch == ' ')
            return "\' \'";
        if (ch < 128 && !Character.isWhitespace(ch) && !Character.isISOControl(ch))
            return "'" + (char) ch + "'";
        s = "0x" + Integer.toHexString(ch);
        if (grammar.getCodeLang().equals("python")) {
            s = String.format("as_chr(%s)", s);
        }
        return s;
    }

    /**
     * This method is only here to help with debugging NFA state-related logic in
     * templates.
     * Sometimes, you want to see ASCII rather than code points.
     *
     * @param char_array a list of code points.
     * @return a String to use in generated template code.
     */
    public String displayChars(int[] char_array) {
        StringBuilder sb = new StringBuilder();
        int n = char_array.length;

        sb.append('[');
        for (int i = 0; i < n; i++) {
            sb.append(displayChar(char_array[i]));
            if (i < (n - 1)) {
                sb.append(", ");
            }
        }
        sb.append(']');
        return sb.toString();
    }

    // The following methods added for supporting generation in languages other than
    // Java.

    public Map<String, Object> tokenSubClassInfo() {
        Map<String, String> tokenClassMap = new HashMap<>();
        Map<String, String> superClassMap = new HashMap<>();
        // List<String> classes = new ArrayList<>();

        for (RegularExpression re : grammar.getOrderedNamedTokens()) {
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
        String pkg = grammar.getInjector().getNodePackage();
        for (String key : superClassMap.keySet()) {
            String qualifiedName = String.format("%s.%s", pkg, key);
            List<ObjectType> extendsList = grammar.getInjector().getExtendsList(qualifiedName);

            if ((extendsList == null) || (extendsList.size() == 0)) {
                superClassMap.put(key, "Token");
            } else {
                superClassMap.put(key, extendsList.get(0).toString());
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
        HashMap<String, Object> result = new HashMap<>();
        result.put("sortedNames", sorted);
        result.put("tokenClassMap", tokenClassMap);
        return result;
    }

    // Used in templates specifically for method name translation
    public String translateIdentifier(String ident) {
        return grammar.getTranslator().translateIdentifier(ident, Translator.TranslationContext.METHOD);
    }

    // Used in templates for side effects, hence returning empty string
    public String startProduction() {
        Translator.SymbolTable symbols = new Translator.SymbolTable();

        grammar.getTranslator().pushSymbols(symbols);
        return "";
    }

    // Used in templates for side effects, hence returning empty string
    public String endProduction() {
        grammar.getTranslator().popSymbols();
        grammar.getTranslator().clearParameterNames();
        return "";
    }

    public String translateParameters(String parameterList) {
        StringBuilder sb = new StringBuilder();
        // First construct the parameter list with parentheses, so
        // that we can parse it and get the AST
        sb.append('(');
        sb.append(parameterList);
        sb.append(')');
        CongoCCParser parser = new CongoCCParser(sb.toString());
        parser.FormalParameters();
        List<FormalParameter> parameters = ((FormalParameters) parser.rootNode()).getParams();
        // Now build the result
        sb.setLength(0);
        grammar.getTranslator().translateFormals(parameters, null, sb);
        return sb.toString();
    }

    public String translateExpression(Node expr) {
        StringBuilder result = new StringBuilder();
        grammar.getTranslator().translateExpression(expr, result);
        return result.toString();
    }

    public String translateString(String expr) {
        // For debugging. Just parse the passed string as an expression
        // and output the translation.
        CongoCCParser parser = new CongoCCParser(expr);
        parser.Expression();
        StringBuilder result = new StringBuilder();
        grammar.getTranslator().translateExpression(parser.rootNode(), result);
        return result.toString();
    }

    private void translateStatements(Node node, int indent, StringBuilder result) {
        if (node instanceof Statement) {
            grammar.getTranslator().translateStatement(node, indent, result);
        } else {
            for (int i = 0; i < node.getChildCount(); i++) {
                Node child = node.getChild(i);
                if (child instanceof Delimiter) {
                    continue; // could put in more checks here
                }
                grammar.getTranslator().translateStatement(child, indent, result);
            }
        }
    }

    public Set<String> getTokenNames() {
        HashSet<String> result = new HashSet<>();
        for (RegularExpression re : lexerData.getRegularExpressions()) {
            result.add(re.getLabel());
        }
        return result;
    }

    public String translateCodeBlock(String cb, int indent) {
        StringBuilder result = new StringBuilder();
        if (cb != null) {
            cb = cb.trim();
            if (cb.length() == 0) {
                grammar.getTranslator().translateEmptyBlock(indent, result);
            } else {
                String block = "{" + cb + "}";
                CongoCCParser parser = new CongoCCParser(block);
                parser.Block();
                Node node = parser.rootNode();
                Translator.SymbolTable syms = new Translator.SymbolTable();
                grammar.getTranslator().pushSymbols(syms);
                translateStatements(node, indent, result);
                grammar.getTranslator().popSymbols();
            }
        }
        return result.toString();
    }

    // used in templates
    public String translateNonterminalArgs(String args) {
        // The args are passed through as a string, but need to be translated according
        // to the language
        // being generated. For the Java template, they don't come through this method -
        // they are passed
        // straight through as a string by the Java template.
        return (args == null) ? "" : grammar.getTranslator().translateNonterminalArgs(args);
    }

    // used in templates
    public String translateInjectedClass(String name) {
        String result;

        grammar.getTranslator().startClass(name, false, null);
        result = grammar.getTranslator().translateInjectedClass(grammar.getInjector(), name);
        grammar.getTranslator().endClass(name, false, null);
        return result;
    }

    public String translateInjections(String className, boolean fields, boolean initializers) {
        StringBuilder result = new StringBuilder();
        if (fields) {
            grammar.getTranslator().clearFields();
        }
        String cn = lastPart(className, '.');
        grammar.getTranslator().startClass(cn, fields, result);
        try {
            List<ClassOrInterfaceBodyDeclaration> declsToProcess = grammar.getInjector().getBodyDeclarations(className);
            if (declsToProcess != null) {
                int fieldIndent = grammar.getTranslator().getFieldIndent();
                int methodIndent = grammar.getTranslator().getMethodIndent();
                for (ClassOrInterfaceBodyDeclaration decl : declsToProcess) {
                    // If processing fields, we want to process FieldDeclarations or Initializers.
                    // Otherwise, we want to process TypeDeclarations, MethodDeclarations and
                    // ConstructorDeclarations
                    boolean process = (fields == (decl instanceof FieldDeclaration || decl instanceof Initializer));
                    if (process) {
                        if (decl instanceof FieldDeclaration || decl instanceof CodeBlock
                                || decl instanceof Initializer) {
                            if ((decl instanceof Initializer) && !initializers) {
                                continue;
                            }
                            grammar.getTranslator().translateStatement(decl, fieldIndent, result);
                        } else if (decl instanceof MethodDeclaration || decl instanceof ConstructorDeclaration ||
                                decl instanceof EnumDeclaration || decl instanceof ClassDeclaration) {
                            grammar.getTranslator().translateStatement(decl, methodIndent, result);
                        } else {
                            throw new UnsupportedOperationException();
                        }
                    }
                }
            }
        } finally {
            grammar.getTranslator().endClass(cn, fields, result);
        }
        return result.toString();
    }

    protected String translateInitializers(String className) {
        StringBuilder result = new StringBuilder();
        List<ClassOrInterfaceBodyDeclaration> declsToProcess = grammar.getInjector().getBodyDeclarations(className);
        if (declsToProcess != null) {
            int fieldIndent = grammar.getTranslator().getFieldIndent();
            for (ClassOrInterfaceBodyDeclaration decl : declsToProcess) {
                if (decl instanceof Initializer) {
                    grammar.getTranslator().translateStatement(decl, fieldIndent, result);
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
                            names.add(((Identifier) child).getImage());
                        } else if (child instanceof VariableDeclarator) {
                            Identifier ident = child.firstChildOfType(Identifier.class);
                            if (ident == null) {
                                throw new UnsupportedOperationException();
                            }
                            names.add(ident.getImage());
                        }
                    }
                    if (names.size() == 0) {
                        throw new UnsupportedOperationException();
                    }
                    for (String name : names) {
                        result.add(grammar.getTranslator().translateIdentifier(name,
                                Translator.TranslationContext.VARIABLE));
                    }
                } else {
                    throw new UnsupportedOperationException();
                }
            }
        }
        return result;
    }

    public List<String> injectedTokenFieldNames() {
        String className = String.format("%s.Token", grammar.getParserPackage());
        return injectedFieldNames(className);
    }

    public List<String> injectedLexerFieldNames() {
        String className = String.format("%s.%s", grammar.getParserPackage(), grammar.getLexerClassName());
        return injectedFieldNames(className);
    }

    // used in templates
    public List<String> injectedParserFieldNames() {
        String className = String.format("%s.%s", grammar.getParserPackage(), grammar.getParserClassName());
        return injectedFieldNames(className);
    }

    // used in templates
    public String translateNestedTypes(String className, boolean fields) {
        className = String.format("%s.%s", grammar.getNodePackage(), className);
        return translateInjections(className, fields, false);
    }

    // used in templates
    public String translateTokenInjections(boolean fields) {
        String className = String.format("%s.Token", grammar.getParserPackage());
        return translateInjections(className, fields, fields && grammar.getTranslator().isIncludeInitializers());
    }

    // used in templates
    public String translateLexerInjections(boolean fields) {
        String className = String.format("%s.%s", grammar.getParserPackage(), grammar.getLexerClassName());
        return translateInjections(className, fields, fields && grammar.getTranslator().isIncludeInitializers());
    }

    // used in templates
    public String translateParserInjections(boolean fields) {
        String className = String.format("%s.%s", grammar.getParserPackage(), grammar.getParserClassName());
        return translateInjections(className, fields, fields && grammar.getTranslator().isIncludeInitializers());
    }

    // used in templates
    public String translateLexerInitializers() {
        String className = String.format("%s.%s", grammar.getParserPackage(), grammar.getLexerClassName());
        return translateInitializers(className);
    }

    // used in templates
    public String translateParserInitializers() {
        String className = String.format("%s.%s", grammar.getParserPackage(), grammar.getParserClassName());
        return translateInitializers(className);
    }

    // used in templates
    public String translateTokenSubclassInjections(String className, boolean fields) {
        className = String.format("%s.%s", grammar.getNodePackage(), className);
        return translateInjections(className, fields, fields && grammar.getTranslator().isIncludeInitializers());
    }

    // used in templates
    public String translateType(String type) {
        return grammar.getTranslator().translateTypeName(type);
    }

    // used in templates
    public String translateModifiers(String modifiers) {
        return modifiers;
    }

    protected void processImports(Set<ImportDeclaration> imports, StringBuilder result) {
        String prefix = String.format("%s.", grammar.getNodePackage());
        for (ImportDeclaration decl : imports) {
            String name = decl.getChild(1).toString();
            if (name.startsWith("java.") || name.startsWith(prefix)) {
                continue;
            }
            grammar.getTranslator().translateImport(name, result);
        }
    }

    // used in templates
    public String translateLexerImports() {
        StringBuilder result = new StringBuilder();
        String cn = String.format("%s.%s", grammar.getParserPackage(), grammar.getLexerClassName());
        Set<ImportDeclaration> imports = grammar.getInjector().getImportDeclarations(cn);

        if (imports != null) {
            processImports(imports, result);
        }
        return result.toString();
    }

    // used in templates
    public String translateParserImports() {
        StringBuilder result = new StringBuilder();
        String cn = String.format("%s.%s", grammar.getParserPackage(), grammar.getParserClassName());
        Set<ImportDeclaration> imports = grammar.getInjector().getImportDeclarations(cn);

        if (imports != null) {
            processImports(imports, result);
        }
        return result.toString();
    }

    public List<String> getSortedNodeClassNames() {
        Sequencer seq = new Sequencer();
        String pkg = grammar.getInjector().getNodePackage();
        String bnn = grammar.getInjector().getBaseNodeClassName();

        seq.addNode(bnn);
        for (String cn : grammar.getNodeNames()) {
            String qn = String.format("%s.%s", pkg, cn);
            List<ObjectType> elist = grammar.getInjector().getExtendsList(qn);
            List<ObjectType> ilist = grammar.getInjector().getImplementsList(qn);
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

    public String getCurrentNodeVariableName() {
        if (nodeVariableNameStack.isEmpty())
            return "null";
        return nodeVariableNameStack.get(nodeVariableNameStack.size() - 1);
    }
}