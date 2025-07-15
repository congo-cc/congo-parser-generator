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
import org.congocc.codegen.java.CodeInjector;
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
public class Grammar extends BaseNode {
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

    public Grammar(Path outputDir, String codeLang, int jdkTarget, boolean quiet, Map<String, String> preprocessorSymbols) {
        if (preprocessorSymbols == null) {
            preprocessorSymbols = new HashMap<>();
        }
        else {  // in case an unmodifiable map is passed - make a mutable copy.
            preprocessorSymbols = new HashMap<>(preprocessorSymbols);
        }
        this.preprocessorSymbols = preprocessorSymbols;
        this.appSettings = new AppSettings(this);
        appSettings.setJdkTarget(jdkTarget);
        appSettings.setOutputDir(outputDir);
        appSettings.setCodeLangString(codeLang);
        preprocessorSymbols.put("__" + codeLang + "__","1");
        appSettings.setQuiet(quiet);
        this.templateGlobals = new TemplateGlobals(this);
    }

    public Grammar() {this.appSettings = new AppSettings(this);}


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

    public String[] getLexicalStates() {
        return lexicalStates.toArray(new String[0]);
    }

    public GrammarFile parse(Path file, boolean enterIncludes) throws IOException {
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
            add(rootNode);
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
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            CompilationUnit cu = CongoCCParser.parseJavaFile(this, includeFile.normalize().toString(), content);
            codeInjections.add(cu);
            return cu;
        } else {
            Path prevLocation = appSettings.getFilename();
            String prevDefaultLexicalState = this.defaultLexicalState;
            boolean prevIgnoreCase = appSettings.isIgnoreCase();
            includeNesting++;
            GrammarFile root = parse(path, true);
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

    private CodeInjector injector;

    public CodeInjector getInjector() {
        if (injector == null) {
            injector = new CodeInjector(this, codeInjections);
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
        if (node instanceof CodeInjection ci) {
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
        CodeInjection.inject(this, nodeName, "{" + modifiers + " " + typeName + " " + fieldName + ";}");
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
    
    public class CardinalityChecker extends Visitor {
        private final Grammar context;
        CardinalityChecker(Grammar context) {
            this.context = context;
            visit(context);
        }
        
        @Override
        public void visit(Node n) {
            super.visit(n);
        }
        
        Stack<int[]> rangeStack = new Stack<>();
        
        public void visit(BNFProduction n) {
            recurse(n);
        }
        
        // check repetition cardinality constraints (depth first)
        public void visit(ExpansionWithParentheses n) {
            if (n.isCardinalityContainer()) {
                rangeStack.push(new int[] {0,Integer.MAX_VALUE});
            }
            recurse(n.getNestedExpansion());
            if (n.isCardinalityContainer()) {
                int[] repetitionRange = rangeStack.pop();
                if (repetitionRange[0] > 0 && (n instanceof ZeroOrMore)) {
                    // This is a very weak warning, as there are valid reasons to do this. Probably it is between info and warning (i.e., a caution).
                    context.errors.addInfo(n, "This ZeroOrMore expansion contains a minimum cardinality assertion of > 0; this might not behave as intended.");
                }
                if (!(n instanceof IteratingExpansion)) {
                    //FIXME: warn on constraints within ZeroOrOne (below does not work)
                    context.errors.addError(n, "Cardinality constraints may only allowed be contained in ZeroOrMore and OneOrMore expansions.");
                }
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
            try {
                if (s.isCardinalityConstrained()) {
                    int[] repetitionRange = rangeStack.peek();
                    int minCardinality = repetitionRange[0];
                    int maxCardinality = repetitionRange[1];
                    int numberOfConstraints = 0;
                    //TODO: warn on improperly telescoped constraints in single sequence (i.e., shrinking the min or expanding the max)
                    List<Assertion> assertions = s.getCardinalityAssertions();
                    if (assertions != null) {
                        for (Assertion a : assertions) {
                            if (a.isCardinalityConstraint()) {
                                int[] constraint = a.getCardinalityConstraint();
                                if (constraint[1] == 0) errors.addWarning(a, "Maximum cardinality is 0; this is likely an error.");
                                if (constraint[0] > constraint[1]) errors.addError(a, "Maximum cardinality is less than the minimum.");
                                
                                minCardinality = Math.max(constraint[0], minCardinality);
                                maxCardinality = Math.min(constraint[1], maxCardinality);
                            }
                        }
                    }
                    repetitionRange[0] = minCardinality;
                    repetitionRange[1] = maxCardinality;
                }
            } catch (Exception e) {
                s.firstAncestorOfType(BNFProduction.class).dump();
                throw e;
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
                errors.addWarning(exp, msg);
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
                    errors.addWarning(following, msg);
                }
            }
        }
    }
}