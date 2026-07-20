package org.congocc.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.congocc.codegen.FilesGenerator;
import org.congocc.codegen.java.JavaCodeInjector;
import org.congocc.codegen.TemplateGlobals;
import org.congocc.codegen.Translator;
import org.congocc.app.AppSettings;
import org.congocc.app.Errors;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;
import static org.congocc.parser.Node.CodeLang.*;

/**
 * This object is the root Node of the data structure that contains all the
 * information regarding a congocc processing job.
 */
@SuppressWarnings("unused")
public class Grammar extends GrammarFile {
    private String defaultLexicalState;
    private final LexerData lexerData = new LexerData(this);
    private int includeNesting;

    private Map<String, BNFProduction> productionTable;
    private final Set<String> lexicalStates = new LinkedHashSet<>();
    private Map<String, String> preprocessorSymbols = new HashMap<>();
    private final Set<String> nodeNames = new LinkedHashSet<>();
    private final Map<String,String> nodeClassNames = new HashMap<>();
    // TODO use these later for Nodes that correspond to abstract
    // classes or interfaces
    private final Set<String> abstractNodeNames = new HashSet<>();
    private final Set<String> interfaceNodeNames = new HashSet<>();
    private final Map<String, String> nodePackageNames = new HashMap<>();
    private final List<Node> codeInjections = new ArrayList<>();
    private final List<String> lexerTokenHooks = new ArrayList<>();
    private final List<String> parserTokenHooks = new ArrayList<>();
    private final List<String> openNodeScopeHooks = new ArrayList<>();
    private final List<String> closeNodeScopeHooks = new ArrayList<>();
    private final List<String> resetTokenHooks = new ArrayList<>();
    private final Map<String, List<String>> closeNodeHooksByClass = new HashMap<>();

    private final Set<Path> alreadyIncluded = new HashSet<>();

    private TemplateGlobals templateGlobals;
    private final AppSettings appSettings;
    private Errors errors;

    public Grammar(Path outputDir, String codeLang, boolean quiet, Map<String, String> preprocessorSymbols) {
        if (preprocessorSymbols == null) {
            preprocessorSymbols = new HashMap<>();
        }
        else {  // in case an unmodifiable map is passed - make a mutable copy.
            preprocessorSymbols = new HashMap<>(preprocessorSymbols);
        }
        this.preprocessorSymbols = preprocessorSymbols;
        this.appSettings = new AppSettings(this);
        appSettings.setOutputDir(outputDir);
        appSettings.setCodeLangString(codeLang);
        preprocessorSymbols.put("__" + codeLang + "__","1");
        appSettings.setQuiet(quiet);
        this.templateGlobals = new TemplateGlobals(this);
    }

    public Grammar() {this.appSettings = new AppSettings(this);}

    public Grammar getGrammar() {return this;}

    public AppSettings getAppSettings() {return appSettings;}

    public TemplateGlobals getTemplateGlobals() {return templateGlobals;}

    public Errors getErrors() {
        if (errors == null) errors = new Errors();
        return errors;
    }

    private void convertAndSet(Map<String, Object> settings, String key, String value) {
        if (appSettings.isABooleanSetting(key)) {
            value = value.toLowerCase();
            if (value.equals("true")) {
                settings.put(key, true);
            }
            else if (value.equals("false")) {
                settings.put(key, false);
            }
            else {
                // Not a valid boolean value
                // TODO warn or bail
            }
        }
        else if (appSettings.isAStringSetting(key)) {
            settings.put(key, value);
        }
        else if (appSettings.isAnIntegerSetting(key)) {
            try {
                settings.put(key, Integer.parseInt(value));
            }
            catch (NumberFormatException e) {
                // Not a valid integer value
                // TODO warn or bail
            }
        }
/*
        else {
            // Not a known setting - ignore, as there could be other environment variables / preprocessor symbols
            // we come across
        }
 */
    }

    private void addEnvironmentOverrides(Map<String, Object> settings) {
        Map<String, String> envVars = System.getenv();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("CONGOCC_")) {
                convertAndSet(settings, key.substring(8), entry.getValue());  // after the CONGOCC_ prefix
            }
        }
    }

    private void addCommandLineOverrides(Map<String, Object> settings) {
        for (Map.Entry<String, String> entry : preprocessorSymbols.entrySet()) {
            convertAndSet(settings, entry.getKey(), entry.getValue());
        }
    }

    public void setSettings(Map<String, Object> settings) {
        // First, get the settings from the grammar file. Then, overwrite with any from the environment or
        // command-line. Order of priority is grammar file < environment < command-line. For environment,
        // look for CONGOCC_<setting name>; for command-line, look for <setting-name> in the -p argument,
        // i.e. in the preprocessor symbols (this could be moved later to a -D setting if desired, but currently
        // it's for internal use to facilitate testing).
        // Sanity-checking will be done as it is now, so minimal checks are done here.
        addEnvironmentOverrides(settings);
        addCommandLineOverrides(settings);
        appSettings.setSettings((settings));
        if (appSettings.getSyntheticNodesEnabled() && appSettings.getCodeLang()==JAVA) {
        	addNodeType(null, appSettings.getBaseNodeClassName());
        }

    }

    public Map<String,String> getPreprocessorSymbols() {
        return preprocessorSymbols;
    }

    public Set<String> getLexicalStates() {
        return lexicalStates;
    }

    public GrammarFile parse(Path file, String defaultLexicalState) throws IOException {
        Path canonicalPath = file.normalize();
        if (alreadyIncluded.contains(canonicalPath)) return null;
        else alreadyIncluded.add(canonicalPath);
        CongoCCParser parser = new CongoCCParser(this, canonicalPath, preprocessorSymbols, defaultLexicalState);
        Path prevIncludedFileDirectory = appSettings.getIncludedFileDirectory();
        if (!isInInclude()) {
            appSettings.setFilename(file);
        } else {
            appSettings.setIncludedFileDirectory(canonicalPath.getParent());
        }
        GrammarFile rootNode = parser.Root();
        appSettings.setIncludedFileDirectory(prevIncludedFileDirectory);
        if (!isInInclude()) {
            add(rootNode);
        }
        return rootNode;
    }

    public Node include(List<String> locations, Node includeLocation, String defaultLexicalState) throws IOException {
        Path path = appSettings.resolveLocation(locations);
        if (path == null) {
            errors.addError(includeLocation, "Could not resolve location of include file");
            throw new FileNotFoundException(includeLocation.getLocation());
        }
        String location = path.toString();
        if (location.toLowerCase().endsWith(".java") || location.toLowerCase().endsWith(".jav")) {
            Path includeFile = Paths.get(location);
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            CompilationUnit cu = CongoCCParser.parseJavaFile(this, includeFile.normalize().toString(), content);
            codeInjections.add(cu);
            return cu;
        } else {
            Path prevLocation = appSettings.getFilename();
            String prevDefaultLexicalState = this.defaultLexicalState;
            boolean prevIgnoreCase = appSettings.isIgnoreCase();
            includeNesting++;
            GrammarFile root = parse(path, defaultLexicalState);
            if (root==null) return null;
            includeNesting--;
            appSettings.setFilename(prevLocation);
            this.defaultLexicalState = prevDefaultLexicalState;
            appSettings.setIgnoreCase(prevIgnoreCase);
            return root;
        }
    }

    public void generateLexer() {
        lexerData.buildData();
    }

    public void generateFiles() throws IOException {
        Translator translator = Translator.getTranslatorFor(this);
        templateGlobals.setTranslator(translator);
        new FilesGenerator(this).generateAll();
    }

    public LexerData getLexerData() {
        return lexerData;
    }

    public String getDefaultLexicalState() {
        return defaultLexicalState == null ? "DEFAULT" : defaultLexicalState;
    }

    public void setDefaultLexicalState(String defaultLexicalState) {
        this.defaultLexicalState = defaultLexicalState;
        addLexicalState(defaultLexicalState);
    }

    private JavaCodeInjector injector;

    public JavaCodeInjector getInjector() {
        if (injector == null) {
            injector = new JavaCodeInjector(this, codeInjections);
        }
        return injector;
    }

    public Collection<BNFProduction> getParserProductions() {
        List<BNFProduction> productions = descendants(BNFProduction.class);
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

    public List<ExpansionSequence> getExpansionsNeedingPredicate() {
        return descendants(ExpansionSequence.class, ExpansionSequence::getRequiresPredicateMethod);
    }

    public List<Expansion> getExpansionsNeedingRecoverMethod() {
        Set<String> alreadyAdded = new LinkedHashSet<>();
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
        return closeNodeHooksByClass.computeIfAbsent(className, k -> new ArrayList<>());
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
        Set<String> usedNames = new LinkedHashSet<>();
        List<Expansion> result = new ArrayList<>();
        for (Expansion expansion : descendants(Expansion.class)) { // | is this one necessary now that BNFProduction is an Expansion? [jb]
                                                                  //  V
            if ((expansion instanceof BNFProduction) || (expansion.getParent() instanceof BNFProduction)) continue; // Handle these separately
            if (type == 0 || type == 2) {   // follow sets
                if (expansion instanceof EmbeddedCode) {
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

    public List<Lookahead> getAllLookaheads() {
        return this.descendants(Lookahead.class);
    }

    public List<LookBehind> getAllLookBehinds() {
        return this.descendants(LookBehind.class);
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
        return interfaceNodeNames.contains(nodeName) || getInjector().isDeclaredInterface(nodeName);
    }

    public boolean nodeIsAbstract(String nodeName) {
        return abstractNodeNames.contains(nodeName) || getInjector().isDeclaredAbstract(nodeName);
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
        if (node instanceof JavaCodeInjection1 ci) {
            if (ci.getName().equals(appSettings.getLexerClassName())) {
                checkForHooks(ci.body, appSettings.getLexerClassName());
            }
            else if (ci.getName().equals(appSettings.getParserClassName())) {
                checkForHooks(ci.body, appSettings.getParserClassName());
            }
        }
        else if (node instanceof TypeDeclaration typeDecl) {
            String typeName = typeDecl.getName();
            if (typeName.equals(appSettings.getLexerClassName()) || typeName.endsWith("." + appSettings.getLexerClassName())) {
                for (Node value : typeDecl) {
                    checkForHooks(value, appSettings.getLexerClassName());
                }
            }
            else if (typeName.equals(appSettings.getParserClassName()) || typeName.endsWith("." + appSettings.getParserClassName())) {
                for (Node value : typeDecl) {
                    checkForHooks(value, appSettings.getParserClassName());
                }
            }
        }
        else if (node instanceof MethodDeclaration decl) {
            String sig = decl.getFullSignature();
            String closeNodePrefix = appSettings.generateIdentifierPrefix("closeNodeHook");
            if (sig != null) {
                String methodName = new StringTokenizer(sig, "(\n ").nextToken();
                if (className.equals(appSettings.getLexerClassName())) {
                    String prefix = appSettings.generateIdentifierPrefix("tokenHook");
                    String resetPrefix = appSettings.generateIdentifierPrefix("resetTokenHook");
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
                    else if (methodName.startsWith("openNodeHook$")) {
                        openNodeScopeHooks.add(methodName);
                    }
                    else if (methodName.startsWith("closeNodeHook$")) {
                        closeNodeScopeHooks.add(methodName);
                    }
                }
                else if (methodName.startsWith(closeNodePrefix) || methodName.startsWith("closeNodeHook$")) {
                    getCloseNodeScopeHooks(className).add(methodName);
                }
            }
        }
        else {
            for (Node value : node) {
                checkForHooks(value, className);
            }
        }
    }

    public void addCodeInjection(Node n) {
        checkForHooks(n, null);
        codeInjections.add(n);
    }

    /**
     * Adds an injected field to the specified {@link Node} dynamically (post parsing).
     * @param nodeName is the name of the {@code Node}
     * @param modifiers is the string of modifiers needed (if any)
     * @param typeName is the type of the field
     * @param fieldName is the name of the field to be injected
     */
    public void addFieldInjection(String nodeName, String modifiers, String typeName, String fieldName) {
        JavaCodeInjection1.inject(this, nodeName, "{" + modifiers + " " + typeName + " " + fieldName + ";}");
    }

    public boolean isInInclude() {
        return includeNesting > 0;
    }

    private boolean checkReferences() {
        // Check that non-terminals have all been defined.
        List<NonTerminal> undefinedNTs = descendants(NonTerminal.class, nt->nt.getProduction() == null);
        for (NonTerminal nt : undefinedNTs) {
            errors.addError(nt, "Non-terminal " + nt.getName() + " has not been defined.");
        }
        return undefinedNTs.isEmpty();
    }

    public boolean isUsingCardinality() {
        for (Assertion assertion : descendants(Assertion.class)) {
            if (assertion.isCardinalityConstraint()) return true;
        }
        return false;
    }

    private final Map<Assertion, DelegatedCardinalityLink> delegatedCardinalityByAssertion = new IdentityHashMap<>();
    private final Map<BNFProduction, DelegatedCardinalityLink> delegatedCardinalityByCallee = new IdentityHashMap<>();
    private final Map<ExpansionWithParentheses, List<DelegatedCardinalityLink>> delegatedCardinalityByLoop = new IdentityHashMap<>();

    public ExpansionWithParentheses getDelegatedCardinalityLoop(Assertion assertion) {
        DelegatedCardinalityLink link = delegatedCardinalityByAssertion.get(assertion);
        return link == null ? null : link.getLoop();
    }

    public boolean isDelegatedCardinalityAssertion(Assertion assertion) {
        return delegatedCardinalityByAssertion.containsKey(assertion);
    }

    public boolean isDelegatedCardinalityProduction(BNFProduction production) {
        return delegatedCardinalityByCallee.containsKey(production);
    }

    public List<DelegatedCardinalityLink> getDelegatedCardinalityLinks(ExpansionWithParentheses loop) {
        List<DelegatedCardinalityLink> links = delegatedCardinalityByLoop.get(loop);
        return links == null ? Collections.emptyList() : Collections.unmodifiableList(links);
    }

    /**
     * Bias of {@code nt}'s callee block within the nearest enclosing iterating loop's
     * {@code RepetitionCardinality} array, or {@code 0} if not a delegated call site.
     */
    public int getDelegatedCardinalityBias(NonTerminal nt) {
        ExpansionWithParentheses loop = nearestIteratingAncestor(nt);
        if (loop == null) {
            return 0;
        }
        return loop.getDelegatedCardinalityBias(nt.getProduction());
    }

    /**
     * Link orphan RCAs in callee productions to each enclosing iterating expansion
     * at consistent call sites, then re-index affected loops. Multi-parent is allowed:
     * each loop gets its own contiguous block and bias for the callee.
     * Must run after {@link #checkReferences()} so NonTerminal → production resolves.
     */
    void discoverDelegatedCardinality() {
        delegatedCardinalityByAssertion.clear();
        delegatedCardinalityByCallee.clear();
        delegatedCardinalityByLoop.clear();

        Map<BNFProduction, List<NonTerminal>> callSitesByCallee = new IdentityHashMap<>();
        for (NonTerminal nt : descendants(NonTerminal.class)) {
            BNFProduction callee = nt.getProduction();
            if (callee == null) {
                continue;
            }
            callSitesByCallee.computeIfAbsent(callee, k -> new ArrayList<>()).add(nt);
        }

        Map<BNFProduction, List<DelegatedCardinalityLink>> candidatesByCallee = new IdentityHashMap<>();

        for (ExpansionWithParentheses loop : descendants(ExpansionWithParentheses.class,
                e -> e instanceof IteratingExpansion)) {
            List<NonTerminal> directCallSites = collectNonTerminalsStoppingAtInnerLoops(loop.getNestedExpansion());
            for (NonTerminal nt : directCallSites) {
                BNFProduction callee = nt.getProduction();
                if (callee == null) {
                    continue;
                }
                List<Assertion> orphans = collectOrphanCardinalityAssertions(callee);
                if (orphans.isEmpty()) {
                    continue;
                }
                DelegatedCardinalityLink link = new DelegatedCardinalityLink(loop, nt, callee, orphans);
                candidatesByCallee.computeIfAbsent(callee, k -> new ArrayList<>()).add(link);
            }
        }

        Set<ExpansionWithParentheses> loopsToRefresh = new LinkedHashSet<>();
        Map<ExpansionWithParentheses, LinkedHashSet<Assertion>> assertionsByLoop = new IdentityHashMap<>();
        Map<ExpansionWithParentheses, List<DelegatedCardinalityLink>> linksByLoop = new IdentityHashMap<>();

        for (Map.Entry<BNFProduction, List<DelegatedCardinalityLink>> entry : candidatesByCallee.entrySet()) {
            BNFProduction callee = entry.getKey();
            List<DelegatedCardinalityLink> candidates = entry.getValue();
            List<NonTerminal> allCallSites = callSitesByCallee.getOrDefault(callee, Collections.emptyList());

            List<NonTerminal> nonDelegating = new ArrayList<>();
            for (NonTerminal nt : allCallSites) {
                if (nearestIteratingAncestor(nt) == null) {
                    nonDelegating.add(nt);
                }
            }

            if (!nonDelegating.isEmpty()) {
                errors.addError(callee,
                    "Production " + callee.getName()
                    + " has repetition cardinality constraints with no local ZeroOrMore/OneOrMore, "
                    + "but is also invoked outside a ZeroOrMore/OneOrMore (delegated cardinality requires "
                    + "consistent call sites).");
                continue;
            }

            if (candidates.isEmpty()) {
                continue;
            }

            // Relative indices within the callee (stable across all parent loops).
            List<Assertion> orphans = candidates.get(0).getDelegatedAssertions();
            for (int i = 0; i < orphans.size(); i++) {
                orphans.get(i).setAssertionIndex(i);
            }

            DelegatedCardinalityLink representative = candidates.get(0);
            delegatedCardinalityByCallee.put(callee, representative);

            for (DelegatedCardinalityLink link : candidates) {
                ExpansionWithParentheses loop = link.getLoop();
                LinkedHashSet<Assertion> ordered =
                        assertionsByLoop.computeIfAbsent(loop, k -> new LinkedHashSet<>());
                List<DelegatedCardinalityLink> accepted =
                        linksByLoop.computeIfAbsent(loop, k -> new ArrayList<>());
                accepted.add(link);
                for (Assertion a : link.getDelegatedAssertions()) {
                    ordered.add(a);
                    // One representative link per assertion is enough for isDelegated checks;
                    // bias is looked up per call site / loop.
                    delegatedCardinalityByAssertion.putIfAbsent(a, link);
                }
                loopsToRefresh.add(loop);
            }
        }

        for (Map.Entry<ExpansionWithParentheses, LinkedHashSet<Assertion>> e : assertionsByLoop.entrySet()) {
            ExpansionWithParentheses loop = e.getKey();
            loop.setDelegatedCardinalityAssertions(new ArrayList<>(e.getValue()));
            delegatedCardinalityByLoop.put(loop, linksByLoop.get(loop));
        }

        for (ExpansionWithParentheses loop : loopsToRefresh) {
            loop.refreshAssertionIndices();
        }
    }

    private static ExpansionWithParentheses nearestIteratingAncestor(Node node) {
        return node.firstAncestorOfType(ExpansionWithParentheses.class, e -> e instanceof IteratingExpansion);
    }

    /**
     * NonTerminals under {@code expansion}, not descending into nested iterating expansions.
     */
    private static List<NonTerminal> collectNonTerminalsStoppingAtInnerLoops(Expansion expansion) {
        List<NonTerminal> result = new ArrayList<>();
        if (expansion == null) {
            return result;
        }
        collectNonTerminalsStoppingAtInnerLoops(expansion, result);
        return result;
    }

    private static void collectNonTerminalsStoppingAtInnerLoops(Node node, List<NonTerminal> result) {
        for (Node child : node.children()) {
            if (child instanceof NonTerminal nt) {
                result.add(nt);
            } else if (child instanceof IteratingExpansion) {
                // Inner loop wins: do not collect call sites beneath it.
                continue;
            } else {
                collectNonTerminalsStoppingAtInnerLoops(child, result);
            }
        }
    }

    /**
     * Cardinality assertions in {@code production} with no enclosing IteratingExpansion
     * (and not merely under ZeroOrOne — those remain hard errors, not candidates for delegation).
     */
    private static List<Assertion> collectOrphanCardinalityAssertions(BNFProduction production) {
        List<Assertion> orphans = new ArrayList<>();
        Expansion body = production.getExpansion();
        if (body == null) {
            return orphans;
        }
        for (Assertion assertion : body.descendantsOfType(Assertion.class)) {
            if (!assertion.isCardinalityConstraint()) {
                continue;
            }
            if (assertion.hasAncestorOfType(ZeroOrOne.class)
                    && nearestIteratingAncestor(assertion) == null) {
                // ZeroOrOne-only RCAs are never delegated.
                continue;
            }
            if (nearestIteratingAncestor(assertion) == null) {
                orphans.add(assertion);
            }
        }
        return orphans;
    }

    public class CardinalityChecker extends Visitor {
        private final Grammar context;
        CardinalityChecker(Grammar context) {
            this.context = context;
            visit(context);
        }

        Stack<int[]> rangeStack = new Stack<>();

        public void visit(BNFProduction n) {
            recurse(n);
            if (context.isDelegatedCardinalityProduction(n)) {
                return;
            }
            boolean hasOrphanCardinalityAssertion = false;
            boolean hasLocalCardinalityContainer = false;
            for (Assertion assertion : n.descendants(Assertion.class)) {
                if (!assertion.isCardinalityConstraint()) {
                    continue;
                }
                if (context.isDelegatedCardinalityAssertion(assertion)) {
                    continue;
                }
                ExpansionWithParentheses iter = assertion.firstAncestorOfType(
                        ExpansionWithParentheses.class, exp -> exp instanceof IteratingExpansion);
                if (iter == null && !assertion.hasAncestorOfType(ZeroOrOne.class)) {
                    hasOrphanCardinalityAssertion = true;
                }
            }
            for (ExpansionWithParentheses exp : n.descendants(ExpansionWithParentheses.class)) {
                if (exp.isCardinalityContainer()) {
                    hasLocalCardinalityContainer = true;
                    break;
                }
            }
            if (hasOrphanCardinalityAssertion && !hasLocalCardinalityContainer) {
                context.errors.addInfo(n,
                    "This production uses repetition cardinality constraints but has no local ZeroOrMore/OneOrMore loop; "
                    + "a parent production iterator (delegated cardinality) may be required.");
            }
        }

        public void visit(Assertion assertion) {
            if (assertion.isCardinalityConstraint()) {
                if (context.isDelegatedCardinalityAssertion(assertion)) {
                    recurse(assertion);
                    return;
                }
                ExpansionWithParentheses iterContainer = assertion.firstAncestorOfType(
                        ExpansionWithParentheses.class, exp -> exp instanceof IteratingExpansion);
                if (iterContainer == null) {
                    if (assertion.hasAncestorOfType(ZeroOrOne.class)) {
                        context.errors.addError(assertion,
                            "Repetition cardinality constraints may not be used inside a ZeroOrOne (...)? or [...] optional expansion; "
                            + "use ZeroOrMore or OneOrMore instead.");
                    } else {
                        context.errors.addError(assertion,
                            "Repetition cardinality constraint is not within a ZeroOrMore or OneOrMore repetition.");
                    }
                }
            }
            recurse(assertion);
        }

        // check repetition cardinality constraints (depth first)
        public void visit(ExpansionWithParentheses n) {
            if (n.isCardinalityContainer()) {
                rangeStack.push(new int[] {0, Integer.MAX_VALUE});
            }
            // visit (not recurse) so a nested ExpansionSequence receives its own visit hook
            visit(n.getNestedExpansion());
            if (n.isCardinalityContainer()) {
                rangeStack.pop();
            }
        }

        public void visit(AttemptBlock attempt) {
            /*
             *REVISIT:JB This restriction should probably be relaxed for consistency and least surprise. If/When it is, it
             *needs to provide a way to save the state of the currently active cardinality (if any) in the parser state and restore
             *it when recovering.
             */
            if (rangeStack.size() != 0) {
                context.errors.addError(attempt, "Cardinality constraints are not allowed to be asserted within an ATTEMPT...RECOVER block.");
            }
            recurse(attempt);
        }

        public void visit(ExpansionSequence s) {
            recurse(s);
            if (!s.isCardinalityConstrained()) {
                return;
            }
            // Combine in-scope RCAs that are direct children of this sequence (telescoping on one sequence).
            List<Assertion> sequenceAssertions = new ArrayList<>();
            for (Assertion a : s.childrenOfType(Assertion.class)) {
                if (s.isInScopeConstraint.test(a)) {
                    sequenceAssertions.add(a);
                }
            }
            if (sequenceAssertions.isEmpty()) {
                for (Assertion a : s.getCardinalityAssertions()) {
                    if (!sequenceAssertions.contains(a)) {
                        sequenceAssertions.add(a);
                    }
                }
            }
            if (rangeStack.isEmpty() || sequenceAssertions.isEmpty()) {
                return;
            }
            int sequenceMin = 0;
            int sequenceMax = Integer.MAX_VALUE;
            for (Assertion a : sequenceAssertions) {
                if (!a.isCardinalityConstraint()) {
                    continue;
                }
                int[] constraint = a.getCardinalityConstraint();
                if (constraint[1] == 0) {
                    context.errors.addWarning(a, "Maximum cardinality is 0; this is likely an error.");
                }
                if (constraint[0] > constraint[1]) {
                    context.errors.addError(a, "Maximum cardinality is less than the minimum.");
                }
                sequenceMin = Math.max(constraint[0], sequenceMin);
                sequenceMax = Math.min(constraint[1], sequenceMax);
            }
            if (sequenceMin > sequenceMax) {
                context.errors.addWarning(s,
                    "Combined repetition cardinality constraints on this sequence cannot all be satisfied "
                    + "(effective minimum exceeds effective maximum).");
            }
        }
    }

    /**
     * Run over the tree and do some sanity checks
     */
    public void doSanityChecks() {
        if (defaultLexicalState == null) {
            setDefaultLexicalState("DEFAULT");
            lexerData.addLexicalState("DEFAULT");
        }
        for (String lexicalState : lexicalStates) {
            lexerData.addLexicalState(lexicalState);
        }
        if (!checkReferences()) return;
        // Check whether we have any LOOKAHEADs at non-choice points
        for (ExpansionSequence sequence : descendants(ExpansionSequence.class)) {
            if (sequence.getHasExplicitLookahead()
               && !sequence.isAtChoicePoint())
            {
                errors.addError(sequence, "Encountered scanahead at a non-choice location." );
            }
            if (sequence.getHasExplicitScanLimit() && !sequence.isAtChoicePoint()) {
                errors.addError(sequence, "Encountered an up-to-here marker at a non-choice location.");
            }
            if (sequence.getHasExplicitLookahead() && sequence.getHasSeparateSyntacticLookahead() && sequence.getHasExplicitScanLimit()) {
                errors.addError(sequence, "An expansion cannot have both syntactic lookahead and a scan limit.");
            }
            if (sequence.getHasExplicitNumericalLookahead() && sequence.getHasExplicitScanLimit()) {
                errors.addError(sequence, "An expansion cannot have both numerical lookahead and a scan limit.");
            }
/*
            if (sequence.getHasExplicitLookahead()) {
                if (sequence.getHasExplicitLookahead()
                    && !sequence.getHasSeparateSyntacticLookahead()
                    && !sequence.getHasScanLimit()
                    && !sequence.getHasExplicitNumericalLookahead()
                    && sequence.getMaximumSize() > 1) {
                        errors.addWarning(sequence, "Expansion defaults to a lookahead of 1. In a similar spot in JavaCC 21, it would be an indefinite lookahead here, but this changed in Congo");
                    }
            }*/
        }
        for (Expansion exp : descendants(Expansion.class, Expansion::isScanLimit)) {
            if (!((Expansion) exp.getParent()).isAtChoicePoint()) {
                errors.addError(exp, "The up-to-here delimiter can only be at a choice point.");
            }
        }
        for (Expansion exp : descendants(Expansion.class)) {
            String lexicalStateName = exp.getSpecifiedLexicalState();
            if (lexicalStateName != null && lexerData.getLexicalState(lexicalStateName) == null) {
                errors.addError(exp, "Lexical state \""
                + lexicalStateName + "\" has not been defined.");
            }
        }
        // Check that no LookBehind predicates refer to an undefined Production
        for (LookBehind lb : getAllLookBehinds()) {
            for (String name: lb.getPath()) {
                if (Character.isJavaIdentifierStart(name.codePointAt(0))) {
                    if (getProductionByName(name) == null) {
                        errors.addError(lb, "Predicate refers to undefined Non-terminal: " + name);
                    }
                }
            }
        }
        // Check that any lexical state referred to actually exists
        for (RegexpSpec res : descendants(RegexpSpec.class)) {
            String nextLexicalState = res.getNextLexicalState();
            if (nextLexicalState != null && lexerData.getLexicalState(nextLexicalState) == null) {
                Node lastChild = res.get(res.size()-1);
                errors.addError(lastChild, "Lexical state \""
                + nextLexicalState + "\" has not been defined.");
            }
        }

        for (RegexpSpec regexpSpec : descendants(RegexpSpec.class)) {
            if (regexpSpec.getRegexp().matchesEmptyString()) {
                errors.addError(regexpSpec, "Regular Expression can match empty string. This is not allowed here.");
            }
        }

        for (BNFProduction prod : descendants(BNFProduction.class)) {
            String lexicalStateName = prod.getLexicalState();
            if (lexicalStateName != null && lexerData.getLexicalState(lexicalStateName) == null) {
                errors.addError(prod, "Lexical state \""
                + lexicalStateName + "\" has not been defined.");
            }
            if (prod.isLeftRecursive()) {
                errors.addError(prod, "Production " + prod.getName() + " is left recursive.");
            }
        }

        for (Assignment assignment : descendants(Assignment.class)) {
            if (assignment.isPropertyAssignment() || assignment.isNamedAssignment()) {
                BNFProduction production = assignment.firstAncestorOfType(BNFProduction.class);
                if (production.getTreeNodeBehavior() != null) {
                    if (production.getTreeNodeBehavior().isNeverInstantiated()) {
                        errors.addError(assignment, "Cannot assign to production node property or named child list; production node is never instantiated.");
                    }
                }
            }
        }

        if (isUsingCardinality()) {
            discoverDelegatedCardinality();
            new CardinalityChecker(this);
        }

        if (errors.getErrorCount() >0) return;

        for (ExpansionChoice choice : descendants(ExpansionChoice.class)) {
            List<ExpansionSequence> units = choice.getChoices();
            for (int i =0; i< units.size();i++) {
                ExpansionSequence seq = units.get(i);
                if (seq.isEnteredUnconditionally()) {
                    if (i < units.size() -1) {
                        String msg = "This expansion is entered unconditionally but is not the last choice. That may not be your intention.";
                        errors.addWarning(seq, msg);
                        break;
                    }
                }
            }
        }

        for (ExpansionWithNested exp : descendants(ExpansionWithNested.class, e->e instanceof ZeroOrMore || e instanceof OneOrMore || e instanceof ZeroOrOne)) {
            if (exp.getNestedExpansion().isEnteredUnconditionally()) {
                errors.addWarning(exp, "The expansion inside this construct is entered unconditionally. That is probably not your intention.");
                //errors.addError(exp, "The expansion inside this construct is entered unconditionally. This is not permitted here.");
            }
        }

        for (Expansion exp : descendantsOfType(ExpansionSequence.class,
                                               exp->exp.getHasExplicitLookahead()
                                                 && exp.getHasNumericalLookahead())) {
            int amount = exp.getLookaheadAmount();
            int maxSize = exp.getMaximumSize();
            if (amount > maxSize) {
                int minSize = exp.getMinimumSize();
                String message = "The expansion has a lookahead of " + amount + " tokens but consumes ";
                if (maxSize == 0) {
                    message += "no tokens.";
                }
                else if (maxSize == 1) {
                    if (minSize == 1) {
                       message += "just one token.";
                    } else {
                        message += "at most one token.";
                    }
                }
                else {
                    if (minSize != maxSize) {
                        message += ("at most " + maxSize + " tokens.");
                    } else {
                        message += ("just " + maxSize + " tokens.");
                    }
                }
                errors.addWarning(exp, message);
            }
        }

        for (Token tok : descendantsOfType(Token.class,
                   t->t.getType() == Token.TokenType.__ASSERT
                   && t.firstAncestorOfType(Lookahead.class) != null)) {
            errors.addWarning(tok, "ASSERT keyword inside a lookahead, should really be ENSURE");
        }

    }

    public void checkUnparsedContent() {
        for (RawCode ucb : descendants(RawCode.class)) {
            ucb.parseContent();
            if (ucb.getParseException()!=null) {
                errors.addError(ucb, "Error in embedded code block");
                ucb.getParseException().printStackTrace();
            }
        }
    }

    public void reportDeadCode() {
        for (ExpansionChoice choice : descendants(ExpansionChoice.class)) {
            findDeadCode(choice);
        }
        for (ExpansionWithNested exp : descendants(ExpansionWithNested.class, e->e instanceof ZeroOrMore || e instanceof OneOrMore)) {
            Expansion nestedExp = exp.getNestedExpansion();
            if (!nestedExp.getRequiresPredicateMethod()) {
                findFollowingDeadCode(exp);
            }
        }
    }

    private void findDeadCode(ExpansionChoice choice) {
        final TokenSet matchedTokens = new TokenSet(this);
        for (ExpansionSequence exp : choice.getChoices()) {
            if (exp.isEnteredUnconditionally() || exp.getRequiresPredicateMethod()) break;
            else {
                TokenSet firstSet = exp.getFirstSet();
                if (matchedTokens.isEmpty()) {
                    matchedTokens.or(firstSet);
                    continue;
                }
                TokenSet notMatched = firstSet.copy();
                notMatched.andNot(matchedTokens);
                // Now firstSetCopy contains all the tokens
                // that are not already matched in previous
                // choices.
                if (notMatched.isEmpty()) {
                    // The choice is completely unreachable.
                    errors.addWarning(exp, "Expansion is unreachable.");
                    continue;
                }
                if (notMatched.cardinality() == firstSet.cardinality()) {
                    matchedTokens.or(firstSet);
                    continue;
                }
                String msg = "The tokens";
                TokenSet matched = firstSet.copy();
                matched.andNot(notMatched);
                for (String name : matched.getTokenNames()) {
                    if (!msg.equals("The tokens")) {
                        msg += ",";
                    }
                    msg += " ";
                    msg += name;
                }
                if (matched.cardinality() == 1) {
                    msg = msg.replaceFirst("tokens", "token");
                }
                msg += " cannot be matched at this point.";
                errors.addInfo(exp, msg);
//                errors.addWarning(exp, msg);
                matchedTokens.or(firstSet);
            }
        }
    }

    private Expansion nextExpansion(Expansion exp) {
        Node n = exp.nextSibling();
        while (n != null && !(n instanceof Expansion)) {
            n = n.nextSibling();
        }
        Expansion next = (Expansion) n;
        if (next == null) {
            Expansion enclosing = (Expansion) exp.getParent().getParent();
            if (enclosing != null && enclosing.getClass() == ExpansionWithParentheses.class) {
                return nextExpansion(enclosing);
            }
        }
        return next;
    }

    private void findFollowingDeadCode(ExpansionWithNested exp) {
        TokenSet matchedTokens = exp.getFirstSet().copy();
        Expansion following = nextExpansion(exp);
        while (following !=null) {
            if (following.getMaximumSize()>0) break;
            following = nextExpansion(following);
        }
        if (following != null) {
            if (following.getNestedExpansion() != null) {
                //Just exit the whole mess if lookahead or up-to-here is present
                // We assume the grammar author knows what he's doing so the
                // dead code check is superfluous.
                if (following.getNestedExpansion().getRequiresPredicateMethod()) return;
            }
            TokenSet followingSet = following.getFirstSet();
            if (followingSet.intersects(matchedTokens)) {
                TokenSet intersecting = matchedTokens.copy();
                intersecting.and(matchedTokens);
                if (intersecting.cardinality()==followingSet.cardinality()) {
                    errors.addWarning(following, "Expansion is unreachable.");
                }
                else {
                    String msg = "The tokens";
                    followingSet.and(matchedTokens);
                    for (String name : followingSet.getTokenNames()) {
                        if (!msg.equals("The tokens")) {
                            msg += ",";
                        }
                        msg += " ";
                        msg += name;
                    }
                    if (followingSet.cardinality() == 1) {
                        msg = msg.replaceFirst("tokens", "token");
                    }
                    msg += " cannot be matched at this point.";
                    errors.addInfo(following, msg);
                }
            }
        }
    }
}
