package org.congocc.core;

import java.util.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.congocc.codegen.FilesGenerator;
import org.congocc.codegen.java.CodeInjector;
import org.congocc.codegen.TemplateGlobals;
import org.congocc.codegen.Translator;
import org.congocc.app.AppSettings;
import org.congocc.app.Errors;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;

/**
 * This object is the root Node of the data structure that contains all the
 * information regarding a congocc processing job.
 */
public class Grammar extends BaseNode {
    private String defaultLexicalState;
    private LexerData lexerData = new LexerData(this);
    private int includeNesting;

    private List<TokenProduction> tokenProductions = new ArrayList<>();

    private Map<String, BNFProduction> productionTable;
    private Map<String, RegularExpression> namedTokensTable = new LinkedHashMap<>();
    private Set<RegularExpression> overriddenTokens = new HashSet<>();
    private Map<String, String> tokenNamesToConstName = new HashMap<>();
    private Set<String> lexicalStates = new LinkedHashSet<>();
    private Map<String, String> preprocessorSymbols = new HashMap<>();
    private Map<Integer, String> tokenNames = new HashMap<>();
    private Set<String> nodeNames = new LinkedHashSet<>();
    private Map<String,String> nodeClassNames = new HashMap<>();
    // TODO use these later for Nodes that correspond to abstract
    // classes or interfaces
    private Set<String> abstractNodeNames = new HashSet<>();
    private Set<String> interfaceNodeNames = new HashSet<>();
    private Map<String, String> nodePackageNames = new HashMap<>();
    private Set<String> usedIdentifiers = new HashSet<>();
    private List<Node> codeInjections = new ArrayList<>();
    private List<String> lexerTokenHooks = new ArrayList<>(),
                         parserTokenHooks = new ArrayList<>(),
                         openNodeScopeHooks = new ArrayList<>(),
                         closeNodeScopeHooks = new ArrayList<>(),
                         resetTokenHooks = new ArrayList<>();
    private Map<String, List<String>> closeNodeHooksByClass = new HashMap<>();

    private Set<Path> alreadyIncluded = new HashSet<>();




    private Set<RegexpStringLiteral> stringLiteralsToResolve = new HashSet<>();

    private TemplateGlobals templateGlobals;
    private AppSettings appSettings;
    private Errors errors = new Errors();
//    private Translator translator;

    public Grammar(Path outputDir, String codeLang, int jdkTarget, boolean quiet, Map<String, String> preprocessorSymbols) {
        this.preprocessorSymbols = preprocessorSymbols;
        this.appSettings = new AppSettings(this);
        appSettings.setJdkTarget(jdkTarget);
        appSettings.setOutputDir(outputDir);
        appSettings.setCodeLang(codeLang);
        appSettings.setQuiet(quiet);
        this.templateGlobals = new TemplateGlobals(this);
    }

    public Grammar() {this.appSettings = new AppSettings(this);}


    public AppSettings getAppSettings() {return appSettings;}

    public TemplateGlobals getTemplateGlobals() {return templateGlobals;}

    public Errors getErrors() {return errors;}

    public void setSettings(Map<String, Object> settings) {
        appSettings.setSettings((settings));
    }

    public String getCodeLang() { return appSettings.getCodeLang(); }

    public Map<String,String> getPreprocessorSymbols() {
        return preprocessorSymbols;
    }

    public String[] getLexicalStates() {
        return lexicalStates.toArray(new String[]{});
    }

    public void addInplaceRegexp(RegularExpression regexp) {
        if (regexp instanceof RegexpStringLiteral) {
            stringLiteralsToResolve.add((RegexpStringLiteral) regexp);
        }
        TokenProduction tp = new TokenProduction();
        tp.setGrammar(this);
        tp.setExplicit(false);
        tp.setImplicitLexicalState(getDefaultLexicalState());
        addChild(tp);
        addTokenProduction(tp);
        RegexpSpec res = new RegexpSpec();
        res.addChild(regexp);
        tp.addChild(res);
    }

    private void resolveStringLiterals() {
        for (RegexpStringLiteral stringLiteral : stringLiteralsToResolve) {
            String label = lexerData.getStringLiteralLabel(stringLiteral.getImage());
            stringLiteral.setLabel(label);
        }
    }


    public String generateIdentifierPrefix(String basePrefix) {
        return basePrefix + appSettings.separatorString();
    }

    public String generateUniqueIdentifier(String prefix, Node exp) {
        String inputSource = exp.getInputSource();
        String sep = appSettings.separatorString();

        if (inputSource != null) {
            int lastSlash = Math.max(inputSource.lastIndexOf('\\'), inputSource.lastIndexOf('/'));
            if (lastSlash+1<inputSource.length()) inputSource = inputSource.substring(lastSlash+1);
        } else {
            inputSource = "";
        }
        String id = prefix + inputSource + sep + exp.getBeginLine() + sep + exp.getBeginColumn();
        id = removeNonJavaIdentifierPart(id);
        while (usedIdentifiers.contains(id)) {
            id += sep;
        }
        usedIdentifiers.add(id);
        return id;
    }

    public Node parse(Path file, boolean enterIncludes) throws IOException {
        Path canonicalPath = file.normalize();
        if (alreadyIncluded.contains(canonicalPath)) return null;
        else alreadyIncluded.add(canonicalPath);
        CongoCCParser parser = new CongoCCParser(this, canonicalPath, preprocessorSymbols);
        parser.setEnterIncludes(enterIncludes);
        Path prevIncludedFileDirectory = appSettings.getIncludedFileDirectory();
        if (!isInInclude()) {
            appSettings.setFilename(file);
        } else {
            appSettings.setIncludedFileDirectory(canonicalPath.getParent());
        }
        GrammarFile rootNode = parser.Root();
        appSettings.setIncludedFileDirectory(prevIncludedFileDirectory);
        if (!isInInclude()) {
            addChild(rootNode);
        }
        return rootNode;
    }



    public Node include(List<String> locations, Node includeLocation) throws IOException {
        Path path = appSettings.resolveLocation(locations);
        if (path == null) {
            errors.addError(includeLocation, "Could not resolve location of include file");
            throw new FileNotFoundException(includeLocation.getLocation());
        }
        String location = path.toString();
        if (location.toLowerCase().endsWith(".java") || location.toLowerCase().endsWith(".jav")) {
            Path includeFile = Paths.get(location);
            String content = new String(Files.readAllBytes(path),Charset.forName("UTF-8"));
            CompilationUnit cu = CongoCCParser.parseJavaFile(includeFile.normalize().toString(), content);
            codeInjections.add(cu);
            return cu;
        } else {
            Path prevLocation = appSettings.getFilename();
            String prevDefaultLexicalState = this.defaultLexicalState;
            boolean prevIgnoreCase = appSettings.isIgnoreCase();
            includeNesting++;
            Node root = parse(path, true);
            if (root==null) return null;
            includeNesting--;
            appSettings.setFilename(prevLocation);
            this.defaultLexicalState = prevDefaultLexicalState;
            appSettings.setIgnoreCase(prevIgnoreCase);
            return root;
        }
    }

    public void createOutputDir() {
        Path outputDir = Paths.get(".");
        if (!Files.isWritable(outputDir)) {
            errors.addError(null, "Cannot write to the output directory : \"" + outputDir + "\"");
        }
    }

    public void generateLexer() {
        lexerData.buildData();
    }

    public void doSanityChecks() {
        if (defaultLexicalState == null) {
            setDefaultLexicalState("DEFAULT");
        }
        for (String lexicalState : lexicalStates) {
            lexerData.addLexicalState(lexicalState);
        }
        new SanityChecker(this).doChecks();
        if (errors.getErrorCount() > 0) {
            return;
        }
        lexerData.ensureStringLabels();
        resolveStringLiterals();
    }

    public void generateFiles() throws IOException {
        Translator translator = Translator.getTranslatorFor(this);
        templateGlobals.setTranslator(translator);
        new FilesGenerator(this, appSettings.getCodeLang(), codeInjections).generateAll();
    }

    public LexerData getLexerData() {
        return lexerData;
    }
    
    public String getDefaultLexicalState() {
        return this.defaultLexicalState;
    }

    public void setDefaultLexicalState(String defaultLexicalState) {
        this.defaultLexicalState = defaultLexicalState;
        addLexicalState(defaultLexicalState);
    }

    private CodeInjector injector;

    public CodeInjector getInjector() {
        if (injector == null) {
            injector = new CodeInjector(this, appSettings.getParserPackage(), appSettings.getNodePackage(), codeInjections);
        }
        return injector;
    }

    public Collection<BNFProduction> getParserProductions() {
        List<BNFProduction> productions = descendantsOfType(BNFProduction.class);
        LinkedHashMap<String, BNFProduction> map = new LinkedHashMap<>();
        for (BNFProduction production: productions) {
            map.put(production.getName(), production);
        }
        return map.values();
    }

    /**
     * @return a List containing all the expansions that are at a choice point
     */
    public List<Expansion> getChoicePointExpansions() {
        return descendants(Expansion.class, Expansion::isAtChoicePoint);
    }

    public List<Expansion> getAssertionExpansions() {
        return descendants(Expansion.class, exp->exp.getParent() instanceof Assertion);
    }

    public List<Expansion> getExpansionsNeedingPredicate() {
        return descendants(Expansion.class, Expansion::getRequiresPredicateMethod);
    }

    public List<Expansion> getExpansionsNeedingRecoverMethod() {
        Set<String> alreadyAdded = new HashSet<>();
        List<Expansion> result = new ArrayList<>();
        for (Expansion exp : descendants(Expansion.class, Expansion::getRequiresRecoverMethod)) {
            String methodName = exp.getRecoverMethodName();
            if (!alreadyAdded.contains(methodName)) {
                result.add(exp);
                alreadyAdded.add(methodName);
            }
        }
        return result;
    }

    public List<String> getLexerTokenHooks() {
        return lexerTokenHooks;
    }

    public List<String> getParserTokenHooks() {
        return parserTokenHooks;
    }

    public List<String> getResetTokenHooks() {
        return resetTokenHooks;
    }

    public List<String> getOpenNodeScopeHooks() {
        return openNodeScopeHooks;
    }

    public List<String> getCloseNodeScopeHooks() {
        return closeNodeScopeHooks;
    }

    public Map<String, List<String>> getCloseNodeHooksByClass() {
        return closeNodeHooksByClass;
    }

    private List<String> getCloseNodeScopeHooks(String className) {
        List<String> result = closeNodeHooksByClass.get(className);
        if (result == null) {
            result = new ArrayList<>();
            closeNodeHooksByClass.put(className, result);
        }
        return result;
    }


    /**
     * A symbol table of all grammar productions.
     */
    public Map<String, BNFProduction> getProductionTable() {
        if (productionTable == null) {
            productionTable = new LinkedHashMap<>();
            for (BNFProduction production : descendants(BNFProduction.class )) {
                productionTable.put(production.getName(), production);
            }
        }
        return productionTable;
    }

    public BNFProduction getProductionByName(String name) {
        return getProductionTable().get(name);
    }

    /**
     * Add a new lexical state
     */
    public void addLexicalState(String name) {
        lexicalStates.add(name);
    }

    public List<Expansion> getExpansionsForFirstSet() {
        return getExpansionsForSet(0);
    }

    public List<Expansion> getExpansionsForFinalSet() {
        return getExpansionsForSet(1);
    }

    public List<Expansion> getExpansionsForFollowSet() {
        return getExpansionsForSet(2);
    }

    private List<Expansion> getExpansionsForSet(int type) {
        HashSet<String> usedNames = new HashSet<>();
        List<Expansion> result = new ArrayList<>();
        for (Expansion expansion : descendants(Expansion.class)) {
            if (expansion.getParent() instanceof BNFProduction) continue; // Handle these separately
            // Skip any sets which are related to the lexer
            if (type == 0) {    // first sets
                if ((expansion instanceof RegexpStringLiteral) ||
                        (expansion instanceof ZeroOrMoreRegexp) ||
                        (expansion instanceof ZeroOrOneRegexp) ||
                        (expansion instanceof OneOrMoreRegexp) ||
                        (expansion instanceof RegexpChoice) ||
                        (expansion instanceof RegexpSequence) ||
                        (expansion instanceof RegexpRef) ||
                        (expansion instanceof CodeBlock) ||
                        (expansion instanceof CharacterList)) {
                    continue;
                }
            }
//            else if (type == 1) {   // final sets
//
//            }
            else if (type == 2) {   // follow sets
                if ((expansion instanceof ZeroOrMoreRegexp) ||
                        (expansion instanceof ZeroOrOneRegexp) ||
                        (expansion instanceof OneOrMoreRegexp) ||
                        (expansion instanceof RegexpChoice) ||
                        (expansion instanceof RegexpSequence) ||
                        // Allow RegexpRef as they are referring to tokens, which will often happen in the parser
                        // (expansion instanceof RegexpRef) ||
                        (expansion instanceof CodeBlock) ||
                        (expansion instanceof CharacterList)) {
                    continue;
                }
            }
            String varName;
            if (type == 0) {
                varName = expansion.getFirstSetVarName();
            } else if (type == 1) {
                varName = expansion.getFinalSetVarName();
            } else {
                varName = expansion.getFollowSetVarName();
            }
            if (!usedNames.contains(varName)) {
                result.add(expansion);
                usedNames.add(varName);
            }
        }
        return result;
    }

    /**
     * The list of all TokenProductions from the input file. This list includes
     * implicit TokenProductions that are created for uses of regular
     * expressions within BNF productions.
     */
    public List<TokenProduction> getAllTokenProductions() {
        return tokenProductions;
    }

    public List<Lookahead> getAllLookaheads() {
        return this.descendants(Lookahead.class);
    }

    public List<LookBehind> getAllLookBehinds() {
        return this.descendants(LookBehind.class);
    }

    public void addTokenProduction(TokenProduction tp) {
        tokenProductions.add(tp);
    }

    /**
     * This is a symbol table that contains all named tokens (those that are
     * defined with a label). The index to the table is the image of the label
     * and the contents of the table are of type "RegularExpression".
     */
    public RegularExpression getNamedToken(String name) {
        return namedTokensTable.get(name);
    }

    public void addNamedToken(String name, RegularExpression regexp) {
        if (namedTokensTable.containsKey(name)) {
            RegularExpression oldValue = namedTokensTable.get(name);
            namedTokensTable.replace(name, oldValue, regexp);
            overriddenTokens.add(oldValue);
        }
        else namedTokensTable.put(name, regexp);
    }

    public boolean isOverridden(RegularExpression regexp) {
        return overriddenTokens.contains(regexp);
    }

    public boolean hasTokenOfName(String name) {
        return namedTokensTable.containsKey(name);
    }

    /**
     * Contains the same entries as "namedTokensTable", but this is an ordered
     * list which is ordered by the order of appearance in the input file.
     * (Actually, the only place where this is used is in generating the
     * TokenType enum)
     */
    public List<RegularExpression> getOrderedNamedTokens() {
        return new ArrayList<RegularExpression>(namedTokensTable.values());
    }

    /**
     * A mapping of ordinal values (represented as objects of type "Integer") to
     * the corresponding labels (of type "String"). An entry exists for an
     * ordinal value only if there is a labeled token corresponding to this
     * entry. If there are multiple labels representing the same ordinal value,
     * then only one label is stored.
     */

    public String getTokenName(int index) {
        String tokenName = tokenNames.get(index);
        return tokenName == null ? String.valueOf(index) : tokenName;
    }

    public String classNameFromTokenName(String tokenName) {
        if (Character.isDigit(tokenName.charAt(0))) {
            return null;
        }
        if (namedTokensTable.get(tokenName).isPrivate()) {
            return null;
        }
        tokenNamesToConstName.put(tokenName, tokenName);
        return tokenName;
    }

    public String constNameFromClassName(String className) {
        return this.tokenNamesToConstName.get(className);
    }

    public void addTokenName(int index, String name) {
        tokenNames.put(index, name);
    }

    public Set<String> getNodeNames() {
        return nodeNames;
    }

    public String getNodePrefix() {
        return appSettings.getNodePrefix();
    }

    public void addNodeType(String productionName, String nodeName) {
        if (nodeName.equals("void") || nodeName.equals("scan")) {
            return;
        }
        if (nodeName.equals("abstract")) {
            abstractNodeNames.add(productionName);
            nodeName = productionName;
        }
        else if (nodeName.equals("interface")) {
            interfaceNodeNames.add(productionName);
            nodeName = productionName;
        }
        else {
            abstractNodeNames.remove(nodeName);
            interfaceNodeNames.remove(nodeName);
        }
        nodeNames.add(nodeName);
        nodeClassNames.put(nodeName, getNodePrefix() + nodeName);
        nodePackageNames.put(nodeName, appSettings.getNodePackage());
    }

    public boolean nodeIsInterface(String nodeName) {
        return interfaceNodeNames.contains(nodeName);
    }

    public boolean nodeIsAbstract(String nodeName) {
        return abstractNodeNames.contains(nodeName);
    }

    public String getNodeClassName(String nodeName) {
        String className = nodeClassNames.get(nodeName);
        if (className ==null) {
            return getNodePrefix() + nodeName;
        }
        return className;
    }


    // A bit kludgy
    // Also, this code doesn't really belong in this class, I don't think.
    private void checkForHooks(Node node, String className) {
        if (node == null || node instanceof Token || node instanceof EmptyDeclaration) {
            return;
        }
        if (node instanceof CodeInjection) {
            CodeInjection ci = (CodeInjection) node;
            if (ci.name.equals(appSettings.getLexerClassName())) {
                checkForHooks(ci.body, appSettings.getLexerClassName());
            }
            else if (ci.name.equals(appSettings.getParserClassName())) {
                checkForHooks(ci.body, appSettings.getParserClassName());
            }
        }
        else if (node instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) node;
            String typeName = typeDecl.getName();
            if (typeName.equals(appSettings.getLexerClassName()) || typeName.endsWith("." + appSettings.getLexerClassName())) {
                for (Iterator<Node> it = typeDecl.iterator(); it.hasNext();) {
                    checkForHooks(it.next(), appSettings.getLexerClassName());
                }
            }
            else if (typeName.equals(appSettings.getParserClassName()) || typeName.endsWith("." + appSettings.getParserClassName())) {
                for (Iterator<Node> it = typeDecl.iterator(); it.hasNext();) {
                    checkForHooks(it.next(), appSettings.getParserClassName());
                }
            }
        }
        else if (node instanceof MethodDeclaration) {
            MethodDeclaration decl = (MethodDeclaration) node;
            String sig = decl.getFullSignature();
            String closeNodePrefix = generateIdentifierPrefix("closeNodeHook");
            if (sig != null) {
                String methodName = new StringTokenizer(sig, "(\n ").nextToken();
                if (className.equals(appSettings.getLexerClassName())) {
                    String prefix = generateIdentifierPrefix("tokenHook");
                    String resetPrefix = generateIdentifierPrefix("resetTokenHook");
                    if (methodName.startsWith(prefix) || methodName.equals("tokenHook") || methodName.equals("CommonTokenAction")) {
                        lexerTokenHooks.add(methodName);
                    }
                    else if (methodName.startsWith(resetPrefix) || methodName.startsWith("resetTokenHook$")) {
                        resetTokenHooks.add(methodName);
                    }
                }
                else if (className.equals(appSettings.getParserClassName())) {
                    if (methodName.startsWith("tokenHook$")) {
                        parserTokenHooks.add(methodName);
                    }
                    else if (methodName.startsWith("openNodeScopeHook")) {
                        openNodeScopeHooks.add(methodName);
                    }
                    else if (methodName.startsWith("closeNodeScopeHook")) {
                        closeNodeScopeHooks.add(methodName);
                    }
                }
                else if (methodName.startsWith(closeNodePrefix) || methodName.startsWith("closeNodeHook$")) {
                    getCloseNodeScopeHooks(className).add(methodName);
                }
            }
        }
        else {
            for (Iterator<Node> it= node.iterator();  it.hasNext();) {
                checkForHooks(it.next(), className);
            }
        }
    }

    public void addCodeInjection(Node n) {
        checkForHooks(n, null);
        codeInjections.add(n);
    }

    public boolean isInInclude() {
        return includeNesting >0;
    }

    public String getParserPackage() {
        return appSettings.getParserPackage();
    }

    static public String removeNonJavaIdentifierPart(String s) {
        StringBuilder buf = new StringBuilder(s.length());
        for (int ch : s.codePoints().toArray()) {
            boolean addChar = buf.length() == 0 ? (Character.isJavaIdentifierStart(ch)) : Character.isJavaIdentifierPart(ch);
            if (addChar) {
                buf.appendCodePoint(ch);
            }
            if (ch == '.') buf.appendCodePoint('_');
        }
        return buf.toString();
    }

//    public boolean isIgnoreCase() {return appSettings.isIgnoreCase();}
}