package org.congocc.core.nfa;

import java.util.*;

import org.congocc.app.Errors;
import org.congocc.core.Grammar;
import org.congocc.core.RegularExpression;
import org.congocc.parser.tree.RegexpSpec;
import org.congocc.parser.tree.RegexpStringLiteral;
import org.congocc.parser.tree.TokenProduction;

public class LexicalStateData {

    final Grammar grammar;
    private String name;

    private List<TokenProduction> tokenProductions = new ArrayList<>();

    private List<CompositeStateSet> compositeSets;
    private List<NfaState> simpleStates = new ArrayList<>();
    private Map<Set<NfaState>, CompositeStateSet> canonicalSetLookup = new HashMap<>();

    private Map<String, RegexpStringLiteral> caseSensitiveTokenTable = new HashMap<>();
    private Map<String, RegexpStringLiteral> caseInsensitiveTokenTable = new HashMap<>();

    private HashSet<RegularExpression> regularExpressions = new HashSet<>();

    private NfaState initialState;

    private Set<NfaState> allStates = new HashSet<>();

    private Errors errors;
    
    public LexicalStateData(Grammar grammar, String name) {
        this.grammar = grammar;
        this.errors = grammar.getErrors();
        this.name = name;
        initialState = new NfaState(this);
    }

    public List<CompositeStateSet> getCanonicalSets() {
        return compositeSets;
    }

    public String getName() {return name;}

    public List<NfaState> getAllNfaStates() {
        return simpleStates;
    }

    void addState(NfaState state) {
        allStates.add(state);
    }

    NfaState getInitialState() {return initialState;}

    public void addTokenProduction(TokenProduction tokenProduction) {
        tokenProductions.add(tokenProduction);
    }

    public boolean containsRegularExpression(RegularExpression re) {
        return regularExpressions.contains(re);
    }

    public void addStringLiteral(RegexpStringLiteral re) {
        if (re.getIgnoreCase()) {
            caseInsensitiveTokenTable.put(re.getLiteralString().toUpperCase(), re);
        } else {
            caseSensitiveTokenTable.put(re.getLiteralString(), re);
        }
    }

    public RegexpStringLiteral getStringLiteral(String image) {
        RegexpStringLiteral result = caseSensitiveTokenTable.get(image);
        if (result == null) {
            result = caseInsensitiveTokenTable.get(image.toUpperCase());
        }
        return result;
    }

    CompositeStateSet getCanonicalComposite(Set<NfaState> stateSet) {
        CompositeStateSet result = canonicalSetLookup.get(stateSet);
        if (result == null) {
            result = new CompositeStateSet(stateSet, this);
            canonicalSetLookup.put(stateSet, result);
        }
        return result;
    }

    public void process() {
        for (TokenProduction tp : tokenProductions) {
            processTokenProduction(tp);
        }
        if (regularExpressions.isEmpty()) {
            errors.addWarning("Warning: Lexical State " + getName() + " does not contain any token types!");
        }
        generateData();
    }

    private void generateData() {
        Set<NfaState> alreadyVisited = new HashSet<>();
        for (NfaState state: allStates) {
            state.doEpsilonClosure(alreadyVisited);
        }
        // Get rid of dummy states.
        allStates.removeIf(state->!state.isMoveCodeNeeded());
        for (NfaState state : allStates) {
            state.setMovesArrayName(simpleStates.size());
            simpleStates.add(state);
        }
        Set<CompositeStateSet> allComposites = new HashSet<>();
        CompositeStateSet initialComposite = initialState.getComposite();
        initialComposite.findWhatIsUsed(new HashSet<>(), allComposites);
        this.compositeSets = new ArrayList<>(allComposites);
        // Make sure the initial state is the first in the list.
        int indexInList = compositeSets.indexOf(initialComposite);
        if (indexInList == -1) {
            compositeSets.clear();
            compositeSets.add(initialComposite);
        }
        else Collections.swap(compositeSets, indexInList, 0);
        // Set the index on the various composites
        for (int i =0; i< compositeSets.size();i++) {
            compositeSets.get(i).setIndex(i);
        }
    }

    private void processTokenProduction(TokenProduction tp) {
        boolean ignore = tp.isIgnoreCase() || grammar.getAppSettings().isIgnoreCase();//REVISIT
        for (RegexpSpec regexpSpec : tp.getRegexpSpecs()) {
            RegularExpression currentRegexp = regexpSpec.getRegexp();
            if (currentRegexp.isPrivate() || grammar.getLexerData().isOverridden(currentRegexp)) {
                continue;
            }
            regularExpressions.add(currentRegexp);
            new NfaBuilder(this, ignore).buildStates(currentRegexp);
            if (regexpSpec.getNextLexicalState() != null && !regexpSpec.getNextLexicalState().equals(this.name)) {
                currentRegexp.setNewLexicalState(grammar.getLexerData().getLexicalState(regexpSpec.getNextLexicalState()));
            }
            currentRegexp.setCodeSnippet(regexpSpec.getCodeSnippet());
        }
    }
}
