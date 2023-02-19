package org.congocc;

import java.util.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.*;

import org.congocc.core.BNFProduction;
import org.congocc.core.Expansion;
import org.congocc.core.LexerData;
import org.congocc.core.Lookahead;
import org.congocc.core.RegularExpression;
import org.congocc.core.SanityChecker;
import org.congocc.output.java.FilesGenerator;
import org.congocc.output.java.CodeInjector;
import org.congocc.output.Translator;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;

/**
 * This object is the root Node of the data structure that contains all the
 * information regarding a congocc processing job.
 */
public class Grammar extends BaseNode {
    private String baseName, 
                   parserClassName,
                   lexerClassName,
                   parserPackage,
                   baseNodeClassName,
                   defaultLexicalState;
    private Path filename;
    private Map<String, Object> settings = new HashMap<>();
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

    private Path includedFileDirectory;

    private Set<String> tokensOffByDefault = new LinkedHashSet<>();

    private Map<String, String> extraTokens = new LinkedHashMap<>();

    private Set<RegexpStringLiteral> stringLiteralsToResolve = new HashSet<>();

    List<String> errorMessages = new ArrayList<>(), warningMessages = new ArrayList<>(), infoMessages = new ArrayList<>();

	private int parseErrorCount;
	private int semanticErrorCount;
    private int warningCount;

    private Path outputDir;
    private boolean quiet;
    private String codeLang;
    private Translator translator;

    public Grammar(Path outputDir, String codeLang, int jdkTarget, boolean quiet, Map<String, String> preprocessorSymbols) {
        this.outputDir = outputDir;
        this.codeLang = codeLang;
        this.jdkTarget = jdkTarget;
        this.quiet = quiet;
        this.preprocessorSymbols = preprocessorSymbols;
    }

    public Grammar() {}

    public boolean isQuiet() {return quiet;}

    public String getCodeLang() { return codeLang; }

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

    private String separatorString() {
        // Temporary solution. Use capital sigma for Python / others, for now
        return codeLang.equals("java") ? "$": "\u03A3";
    }

    public String generateIdentifierPrefix(String basePrefix) {
        return basePrefix + separatorString();
    }

    public String generateUniqueIdentifier(String prefix, Node exp) {
        String inputSource = exp.getInputSource();
        String sep = separatorString();

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
        Path prevIncludedFileDirectory = includedFileDirectory;
        if (!isInInclude()) {
            setFilename(file);
        } else {
            includedFileDirectory = canonicalPath.getParent();
        }
        GrammarFile rootNode = parser.Root();
        includedFileDirectory = prevIncludedFileDirectory;
        if (!isInInclude()) {
            addChild(rootNode);
        }
        return rootNode;
    }

    private Path resolveLocation(List<String> locations) {
        for (String location : locations) {
            Path path = resolveLocation(location);
            if (path != null) return path;
        }
        return null;
    }

    private Path resolveLocation(String location) {
        Path path = Paths.get(location);
        if (Files.exists(path)) return path;
        if (!path.isAbsolute()) {
            path = filename.getParent();
            if (path == null) {
                path = Paths.get(".");
            }
            path = path.resolve(location);
            if (Files.exists(path)) return path;
        }
        if (includedFileDirectory != null) {
            path = includedFileDirectory.resolve(location);
            if (Files.exists(path)) return path;
        }
        if (LexerData.isJavaIdentifier(location)) {
            location = resolveAlias(location);
            /*
             * Look to see if a file with the same name exists in the directory of the current filename, If it does,
             * use it
             */
            path = filename.toAbsolutePath().getParent().resolve(Paths.get(location).getFileName());
            if (Files.exists(path)) return path;
        }
        URI uri = null;
        try {
            uri = getClass().getResource(location).toURI();
        } catch (Exception e) {
            return null;
        }
        try {
            return Paths.get(uri);
        } catch (FileSystemNotFoundException fsne) {
           try {
               FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
               return fs.getPath(location);
           }
           catch (Exception e) {
               e.printStackTrace();
           }
        }
        return null;
    }

    private static Map<String, String> locationAliases = new HashMap<String, String>() {
        {
            put("JAVA_IDENTIFIER_DEF", "/include/java/JavaIdentifierDef.ccc");
            put("JAVA_LEXER", "/include/java/JavaLexer.ccc");
            put("JAVA", "/include/java/Java.ccc");
            put("PYTHON_IDENTIFIER_DEF", "/include/python/PythonIdentifierDef.ccc");
            put("PYTHON_LEXER", "/include/python/PythonLexer.ccc");
            put("PYTHON", "/include/python/Python.ccc");
            put("CSHARP", "/include/csharp/CSharp.ccc");
            put("CSHARP_LEXER", "/include/csharp/CSharpLexer.ccc");
            put("CSHARP_IDENTIFIER_DEF", "/include/csharp/CSharpIdentifierDef.ccc");
            put("PREPROCESSOR", "/include/preprocessor/Preprocessor.ccc");
            put("JSON", "/include/json/JSON.ccc");
            put("JSONC", "/include/json/JSONC.ccc");
        }
    };

    private String resolveAlias(String location) {
        return locationAliases.getOrDefault(location, location);
    }

    public Node include(List<String> locations, Node includeLocation) throws IOException {
        Path path = resolveLocation(locations);
        if (path == null) {
            addError(includeLocation, "Could not resolve location of include file");
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
            Path prevLocation = this.filename;
            String prevDefaultLexicalState = this.defaultLexicalState;
            boolean prevIgnoreCase = this.ignoreCase;
            includeNesting++;
            Node root = parse(path, true);
            if (root==null) return null;
            includeNesting--;
            setFilename(prevLocation);
            this.defaultLexicalState = prevDefaultLexicalState;
            this.ignoreCase = prevIgnoreCase;
            return root;
        }
    }

    public void createOutputDir() {
        Path outputDir = Paths.get(".");
        if (!Files.isWritable(outputDir)) {
            addError(null, "Cannot write to the output directory : \"" + outputDir + "\"");
        }
    }

    /**
     * The grammar file being processed.
     */
    public Path getFilename() {
        return filename;
    }

    public void setFilename(Path filename) {
        this.filename = filename;
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
        if (getErrorCount() > 0) {
            return;
        }
        lexerData.ensureStringLabels();
        resolveStringLiterals();
    }

    public void generateFiles() throws IOException {
        translator = Translator.getTranslatorFor(this);
        new FilesGenerator(this, codeLang, codeInjections).generateAll();
    }

    public Translator getTranslator() {return translator;}

    public LexerData getLexerData() {
        return lexerData;
    }

    private String getBaseName() {
        if (baseName == null) {
            baseName = (String) settings.get("BASE_NAME");
        }
        if (baseName == null) {
            baseName = filename.getFileName().toString();
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot >0) {
                baseName = baseName.substring(0, lastDot);
            }
            baseName = removeNonJavaIdentifierPart(baseName);
            if (Character.isLowerCase(baseName.charAt(0))) {
                baseName = baseName.substring(0, 1).toUpperCase() 
                                  + baseName.substring(1);
            }
        }
        return baseName;
    }

    public String getParserClassName() {
        if (parserClassName ==null) {
            parserClassName = (String) settings.get("PARSER_CLASS");
        }
        if (parserClassName == null) {
            parserClassName = getBaseName() + "Parser";
        }
        return parserClassName;
    }

    public String getLexerClassName() {
        if (lexerClassName == null) {
            lexerClassName = (String) settings.get("LEXER_CLASS");
        }
        if (lexerClassName == null) {
            lexerClassName = getBaseName() + "Lexer";
        }
        return lexerClassName;
    }

    public String getBaseNodeClassName() {
        if (baseNodeClassName == null) {
            baseNodeClassName = (String) settings.get("BASE_NODE_CLASS");
        }
        if (baseNodeClassName == null) {
            if (getRootAPIPackage() == null || getBaseName().length() == 0) {
                baseNodeClassName = "BaseNode";
            } else {
                baseNodeClassName = getBaseName() + "Node";
            }
        }
        return baseNodeClassName;
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
            injector = new CodeInjector(this, parserPackage, getNodePackage(), codeInjections);
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

	/**
	 * Returns the warning count during grammar parsing.
	 *
	 * @return the warning count during grammar parsing.
	 */
	public int getWarningCount() {
		return warningCount;
	}

	/**
	 * Returns the parse error count during grammar parsing.
	 *
	 * @return the parse error count during grammar parsing.
	 */
	public int getParseErrorCount() {
		return parseErrorCount;
	}

	/**
	 * Returns the semantic error count during grammar parsing.
	 *
	 * @return the semantic error count during grammar parsing.
	 */
	public int getSemanticErrorCount() {
		return semanticErrorCount;
	}

    public void addError(String errorMessage) {
        errorMessages.add(errorMessage);
    }

    public void addError(Node location, String errorMessage) {
        String locationString = location == null ? "" : location.getLocation();
        errorMessages.add("Error: " + locationString + ":" + errorMessage);
    }

    public void addWarning(String warningMessage) {
        warningMessages.add(warningMessage);
    }

    public void addWarning(Node location, String warningMessage) {
        String locationString = location == null ? "" : location.getLocation();
        warningMessages.add("Warning: " + locationString + ":" + warningMessage);
    }

    public void addInfo(String infoMessage) {
        infoMessages.add(infoMessage);
    }

    public void addInfo(Node location, String infoMessage) {
        String locationString = location == null ? "" : location.getLocation();
        infoMessages.add("Info: " + locationString + ":" + infoMessage);
    }

	/**
	 * @return the total error count during grammar parsing.
	 */
	public int getErrorCount() {
        return errorMessages.size();
	}

    public Set<String> getNodeNames() {
        return nodeNames;
    }

    public String getNodePrefix() {
        String nodePrefix = (String) settings.get("NODE_PREFIX");
        if (nodePrefix == null) nodePrefix = "";
        return nodePrefix;
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
        nodePackageNames.put(nodeName, getNodePackage());
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

    public String getNodePackageName(String nodeName) {
        return nodePackageNames.get(nodeName);
    }

    // A bit kludgy
    // Also, this code doesn't really belong in this class, I don't think.
    private void checkForHooks(Node node, String className) {
        if (node == null || node instanceof Token || node instanceof EmptyDeclaration) {
            return;
        }
        if (node instanceof CodeInjection) {
            CodeInjection ci = (CodeInjection) node;
            if (ci.name.equals(getLexerClassName())) {
                checkForHooks(ci.body, lexerClassName);
            }
            else if (ci.name.equals(getParserClassName())) {
                checkForHooks(ci.body, parserClassName);
            }
        }
        else if (node instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) node;
            String typeName = typeDecl.getName();
            if (typeName.equals(getLexerClassName()) || typeName.endsWith("." +lexerClassName)) {
                for (Iterator<Node> it = typeDecl.iterator(); it.hasNext();) {
                    checkForHooks(it.next(), lexerClassName);
                }
            }
            else if (typeName.equals(getParserClassName()) || typeName.endsWith("." + parserClassName)) {
                for (Iterator<Node> it = typeDecl.iterator(); it.hasNext();) {
                    checkForHooks(it.next(), parserClassName);
                }
            }
        }
        else if (node instanceof MethodDeclaration) {
            MethodDeclaration decl = (MethodDeclaration) node;
            String sig = decl.getFullSignature();
            String closeNodePrefix = generateIdentifierPrefix("closeNodeHook");
            if (sig != null) {
                String methodName = new StringTokenizer(sig, "(\n ").nextToken();
                if (className.equals(lexerClassName)) {
                    String prefix = generateIdentifierPrefix("tokenHook");
                    String resetPrefix = generateIdentifierPrefix("resetTokenHook");
                    if (methodName.startsWith(prefix) || methodName.equals("tokenHook") || methodName.equals("CommonTokenAction")) {
                        lexerTokenHooks.add(methodName);
                    }
                    else if (methodName.startsWith(resetPrefix) || methodName.startsWith("resetTokenHook$")) {
                        resetTokenHooks.add(methodName);
                    }
                }
                else if (className.equals(parserClassName)) {
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
        if (parserPackage == null) {
            parserPackage = (String) settings.get("PARSER_PACKAGE");
        }
        if (parserPackage == null || parserPackage.length()==0) {
            parserPackage = getParserClassName().toLowerCase();
        }
        return parserPackage;
    }

    public Path getParserOutputDirectory() throws IOException {
        String baseSrcDir = (String) settings.get("BASE_SRC_DIR");
        if (baseSrcDir == null) {
            baseSrcDir = outputDir == null ? "." : outputDir.toString();
        }
        Path dir = Paths.get(baseSrcDir);
        if (!dir.isAbsolute()){
            Path inputFileDir = filename.toAbsolutePath().getParent();
            dir = inputFileDir.resolve(baseSrcDir);
        }
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String packageName = getParserPackage();
        if (packageName != null  && packageName.length() >0) {
            if (codeLang.equals("java")) {
                packageName = packageName.replace('.', '/');
                dir = dir.resolve(packageName);
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }
            }
            else if (codeLang.equals("python")) { // Use last part of package, append "parser"
                int dotPosition = packageName.lastIndexOf('.');

                if (dotPosition >= 0) {
                    packageName = packageName.substring(dotPosition + 1);
                }
                packageName = packageName.concat("parser");
                // Use a user-specified value if available
                packageName = preprocessorSymbols.getOrDefault("py.package", packageName);
                dir = dir.resolve(packageName);
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }
            }
            else if (codeLang.equals("csharp")) {
                // Use last part of package, append "parser", prepend "cs-"
                // only if outDir isn't specified
                if (outputDir == null) {
                    int dotPosition = packageName.lastIndexOf('.');

                    if (dotPosition >= 0) {
                        packageName = packageName.substring(dotPosition + 1);
                    }
                    packageName = "cs-".concat(packageName.concat("parser"));
                    dir = dir.resolve(packageName);
                    if (!Files.exists(dir)) {
                        Files.createDirectories(dir);
                    }
                }
            }
            else {
                throw new UnsupportedOperationException(String.format("Code generation in '%s' is not currently supported.", codeLang));
            }
        }
        return dir;
    }

    //FIXME.
    public String getBaseSourceDirectory() {
        return outputDir == null ? "." : outputDir.toString();
    }

    public Path getNodeOutputDirectory() throws IOException {
        String nodePackage = getNodePackage();
        String baseSrcDir = getBaseSourceDirectory();
        if (nodePackage == null || nodePackage.equals("") || baseSrcDir.equals("")) {
            return getParserOutputDirectory();
        }
        Path baseSource = Paths.get(baseSrcDir);
        if (!baseSource.isAbsolute()) {
            Path grammarFileDir = filename.normalize().getParent();
            if (grammarFileDir == null) grammarFileDir = Paths.get(".");
            baseSource = grammarFileDir.resolve(baseSrcDir).normalize();
        }
        if (!Files.isDirectory(baseSource)) {
            if (!Files.exists(baseSource)) {
                throw new FileNotFoundException("Directory " + baseSrcDir + " does not exist.");
            }
            throw new FileNotFoundException(baseSrcDir + " is not a directory.");
        }
        Path result = baseSource. resolve(nodePackage.replace('.', '/')).normalize();
        if (!Files.exists(result)) {
            Files.createDirectories(result);
        } else if (!Files.isDirectory(result)) {
            throw new IOException(result + " is not a directory.");
        }
        return result;
    }

    public String getNodePackage() {
        String nodePackage = (String) settings.get("NODE_PACKAGE");
        if (nodePackage == null || nodePackage.equals(getParserPackage())) {
            nodePackage = getParserPackage() + ".ast";
        }
        return nodePackage;
    }

    public String getRootAPIPackage() {
        String rootApiPackage = (String) settings.get("ROOT_API_PACKAGE");
        if (rootApiPackage != null && rootApiPackage.equals(getParserPackage())) {
            return null;
        }
        return rootApiPackage;
    }
    
    public String getCopyrightBlurb() {
        String result = (String) settings.get("COPYRIGHT_BLURB");
        return result != null ? result : "";
    }

    public String getCurrentNodeVariableName() {
        return getUtils().getCurrentNodeVariableName();
/*        
        if (nodeVariableNameStack.isEmpty())
            return "null";
        return nodeVariableNameStack.get(nodeVariableNameStack.size() - 1);*/
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

    public boolean getLegacyGlitchyLookahead() {
        Boolean b = (Boolean) settings.get("LEGACY_GLITCHY_LOOKAHEAD");
        return b!=null && b;
    }

    public boolean getTreeBuildingEnabled() {
        Boolean b = (Boolean) settings.get("TREE_BUILDING_ENABLED");
        return b == null || b;
    }

    public boolean getTreeBuildingDefault() {
        Boolean b = (Boolean) settings.get("TREE_BUILDING_DEFAULT");
        return b == null || b;
    }

    public boolean getNodeDefaultVoid() {
        Boolean b = (Boolean) settings.get("NODE_DEFAULT_VOID");
        return b != null && b;
    }

    public boolean getSmartNodeCreation() {
        Boolean b = (Boolean) settings.get("SMART_NODE_CREATION");
        return b == null || b;
    }

    public boolean getTokensAreNodes() {
        Boolean b = (Boolean) settings.get("TOKENS_ARE_NODES");
        return b == null || b;
    }

    public boolean getUnparsedTokensAreNodes() {
        Boolean b = (Boolean) settings.get("TOKENS_ARE_NODES");
        if (b == null) b = (Boolean) settings.get("SPECIAL_TOKENS_ARE_NODES");
        return b != null;
    }

    public boolean getNodeUsesParser() {
        Boolean b = (Boolean) settings.get("NODE_USES_PARSER");
        return b != null && b;
    }

    public boolean getLexerUsesParser() {
        Boolean b = (Boolean) settings.get("LEXER_USES_PARSER");
        return b != null && b;
    }

    public boolean getFaultTolerant() {
        Boolean b = (Boolean) settings.get("FAULT_TOLERANT");
        return b != null && b;
    }

    public boolean getEnsureFinalEOL() {
        Boolean b = (Boolean) settings.get("ENSURE_FINAL_EOL");
        return b != null && b;
    }

    public boolean getTokenChaining() {
        Boolean b = (Boolean) settings.get("TOKEN_CHAINING");
        return b!= null && b;
    }

    public boolean getMinimalToken() {
        if (getTokenChaining()) return false;
        Boolean b = (Boolean) settings.get("MINIMAL_TOKEN");
        return b!=null && b;
    }

    public boolean getPreserveLineEndings() {
        Boolean b = (Boolean) settings.get("PRESERVE_LINE_ENDINGS");
        return b != null && b;
    }

    public boolean getPreserveTabs() {
        Boolean b = (Boolean) settings.get("PRESERVE_TABS");
        if (b!=null) return b;
        if (settings.get("TAB_SIZE")==null && settings.get("TABS_TO_SPACES")==null)
            return true;
        return getTabSize() ==0;
    }

    public boolean getJavaUnicodeEscape() {
        Boolean b = (Boolean) settings.get("JAVA_UNICODE_ESCAPE");
        return b != null && b;
    }

    public boolean getCppContinuationLine() {
        Boolean b = (Boolean) settings.get("C_CONTINUATION_LINE");
        return b != null && b;
    }

    public boolean getUseCheckedException() {
        Boolean b = (Boolean) settings.get("USE_CHECKED_EXCEPTION");
        return b != null && b;
    }

    public int getTabSize() {
        Integer i = (Integer) settings.get("TAB_SIZE");
        if (i==null) {
            i = (Integer) settings.get("TABS_TO_SPACES");
        }
        return i==null ? 1 : i;
    }

    public int getJdkTarget() {
        if (jdkTarget == 0) return 8;
        return jdkTarget;
    }

    public Set<String> getDeactivatedTokens() {
        return tokensOffByDefault;
    }

    public Map<String, String> getExtraTokens() {
        return extraTokens;
    }
    public List<String> getExtraTokenNames() { return new ArrayList<>(extraTokens.keySet()); }
    public Collection<String> getExtraTokenClassNames() { return extraTokens.values(); }

    private boolean ignoreCase;
    public boolean isIgnoreCase() {return ignoreCase;}
    public void setIgnoreCase(boolean ignoreCase) {this.ignoreCase = ignoreCase;}

    private static Pattern extraTokenPattern = Pattern.compile("^(\\w+)(#\\w+)?$");

    public void setSettings(Map<String, Object> settings) {
        typeCheckSettings(settings);
        if (!isInInclude()) {
            this.settings = settings;
            sanityCheckSettings();
        }
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (key.equals("IGNORE_CASE")) {
                setIgnoreCase((Boolean) value);
            }
            else if (key.equals("DEFAULT_LEXICAL_STATE")) {
                setDefaultLexicalState((String) value);
            }
            else if (key.equals("DEACTIVATE_TOKENS")) {
                String tokens = (String) settings.get(key);
                for (StringTokenizer st = new StringTokenizer(tokens, ", \t\n\r"); st.hasMoreTokens();) {
                    String tokenName = st.nextToken();
                    tokensOffByDefault.add(tokenName);
                }
            }
            else if (key.equals("EXTRA_TOKENS")) {
                String tokens = (String) settings.get(key);
                for (StringTokenizer st = new StringTokenizer(tokens, ",\r\n"); st.hasMoreTokens();) {
                    String tokenNameAndMaybeClass = st.nextToken();
                    Matcher m = extraTokenPattern.matcher(tokenNameAndMaybeClass);
                    if (m.matches()) {
                        MatchResult mr = m.toMatchResult();
                        String tokenName = mr.group(1);
                        String tokenClassName = mr.group(2);
                        if (tokenClassName == null) {
                            tokenClassName = tokenName + "Token";
                        }
                        else {
                            tokenClassName = tokenClassName.substring(1);
                        }
                        extraTokens.put(tokenName, tokenClassName);
                    }
                }
            }
            else if (key.equals("BASE_SRC_DIR") || key.equals("OUTPUT_DIRECTORY")) {
                if (!isInInclude() && outputDir == null)
                    outputDir = Paths.get((String)value);
            }
            if (!isInInclude() && key.equals("JDK_TARGET") && jdkTarget ==0){
                int jdkTarget = (Integer) value;
                if (jdkTarget >=8 && jdkTarget <= 19) {
                    this.jdkTarget = (Integer) value; 
                }
                else {
                    this.jdkTarget = 8;
                    addWarning(null, "Invalid JDK Target " + jdkTarget);
                }
            }
        }
    }
    private int jdkTarget = 8;
    private String booleanSettings = ",FAULT_TOLERANT,PRESERVE_TABS,PRESERVE_LINE_ENDINGS,JAVA_UNICODE_ESCAPE,IGNORE_CASE,LEXER_USES_PARSER,NODE_DEFAULT_VOID,SMART_NODE_CREATION,NODE_USES_PARSER,TREE_BUILDING_DEFAULT,TREE_BUILDING_ENABLED,TOKENS_ARE_NODES,SPECIAL_TOKENS_ARE_NODES,UNPARSED_TOKENS_ARE_NODES,FREEMARKER_NODES,NODE_FACTORY,TOKEN_MANAGER_USES_PARSER,ENSURE_FINAL_EOL,MINIMAL_TOKEN,C_CONTINUATION_LINE,USE_CHECKED_EXCEPTION,LEGACY_GLITCHY_LOOKAHEAD,TOKEN_CHAINING,";
    private String stringSettings = ",BASE_NAME,PARSER_PACKAGE,PARSER_CLASS,LEXER_CLASS,BASE_SRC_DIR,BASE_NODE_CLASS,NODE_PREFIX,NODE_CLASS,NODE_PACKAGE,DEFAULT_LEXICAL_STATE,NODE_CLASS,OUTPUT_DIRECTORY,DEACTIVATE_TOKENS,EXTRA_TOKENS,ROOT_API_PACKAGE,COPYRIGHT_BLURB,";
    private String integerSettings = ",TAB_SIZE,TABS_TO_SPACES,JDK_TARGET,";

    public boolean isASetting(String key) {
        return booleanSettings.contains("," + key + ",")
              || stringSettings.contains("," + key + ",")
              || integerSettings.contains("," + key + ",");
    }
    
    private void typeCheckSettings(Map<String, Object> settings) {
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (booleanSettings.contains("," + key + ",")) {
                if (!(value instanceof Boolean)) {
                    errorMessages.add("The option " + key + " is supposed to be a boolean (true/false) type");
                }
            }
            else if (stringSettings.contains("," + key + ",")) {
                if (!(value instanceof String)) {
                    errorMessages.add("The option " + key + " is supposed to be a string");
                }
            }
            else if (integerSettings.contains("," + key + ",")) {
                if (!(value instanceof Integer)) {
                    errorMessages.add("The option " + key + " is supposed to be an integer");
                }
            }
            else {
                warningMessages.add("The option " + key + " is not recognized and will be ignored.");
            }
        }
    }

    public Map<String, Object> getSettings() {return settings;}

    /**
     * Some warnings if incompatible options are set.
     */
    private void sanityCheckSettings() {
        if (!getTreeBuildingEnabled()) {
            String msg = "You have specified the OPTION_NAME option but it is "
                    + "meaningless unless the TREE_BUILDING_ENABLED is set to true."
                    + " This option will be ignored.\n";
            if (settings.get("TOKENS_ARE_NODES") != null) {
                addWarning(null, msg.replace("OPTION_NAME", "TOKENS_ARE_NODES"));
            }
            if (settings.get("UNPARSED_TOKENS_ARE_NODES") != null) {
                addWarning(null, msg.replace("OPTION_NAME", "UNPARSED_TOKENS_ARE_NODES"));
            }
            if (settings.get("SMART_NODE_CREATION") != null) {
                addWarning(null, msg.replace("OPTION_NAME", "SMART_NODE_CREATION"));
            }
            if (settings.get("NODE_DEFAULT_VOID") != null) {
                addWarning(null, msg.replace("OPTION_NAME", "NODE_DEFAULT_VOID"));
            }
            if (settings.get("NODE_USES_PARSER") != null) {
                addWarning(null, msg.replace("OPTION_NAME", "NODE_USES_PARSER"));
            }
        }
    }

    private final TemplateGlobals utils = new TemplateGlobals(this);

    public TemplateGlobals getUtils() {return utils;}
}
