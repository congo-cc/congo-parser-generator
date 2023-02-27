package org.congocc.core;

import java.util.*;

import org.congocc.parser.Node;
import org.congocc.app.Errors;
import org.congocc.core.nfa.LexicalStateData;
import org.congocc.parser.tree.*;

/**
 * Base object that contains lexical data. 
 * It contains LexicalStateData objects that contain
 * the data for each lexical state. The LexicalStateData
 * objects hold the data related to generating the NFAs 
 * for the respective lexical states.
 */
public class LexerData {
    private Grammar grammar;
    private Errors errors;
    private List<LexicalStateData> lexicalStates = new ArrayList<>();
    private List<RegularExpression> regularExpressions = new ArrayList<>();
    
    private Map<String, RegularExpression> namedTokensTable = new LinkedHashMap<>();
    private Set<RegularExpression> overriddenTokens = new HashSet<>();

    public LexerData(Grammar grammar) {
        this.grammar = grammar;
        this.errors = grammar.getErrors();
        RegularExpression reof = new EndOfFile();
        reof.setGrammar(grammar);
        reof.setLabel("EOF");
        regularExpressions.add(reof);
    }
    
    public String getTokenName(int ordinal) {
        if (ordinal < regularExpressions.size()) {
            return regularExpressions.get(ordinal).getLabel();
        }
        return grammar.getAppSettings().getExtraTokenNames().get(ordinal-regularExpressions.size());
    }

    public String getLexicalStateName(int index) {
        return lexicalStates.get(index).getName();
    }

    public void addLexicalState(String name) {
        lexicalStates.add(new LexicalStateData(grammar, name));
    }

    public LexicalStateData getLexicalState(String name) {
        return lexicalStates.stream().filter(state->state.getName().equals(name)).findFirst().get();
    }

    public LexicalStateData getDefaultLexicalState() {
        return lexicalStates.get(0);
    }

    public int getMaxNfaStates() {
        return lexicalStates.stream().mapToInt(state->state.getAllNfaStates().size()).max().getAsInt();
    }

    public List<RegularExpression> getRegularExpressions() {
        List<RegularExpression> result = new ArrayList<>(regularExpressions);
        result.removeIf(re->isOverridden(re));
        return result;
    }

    public boolean getHasLexicalStateTransitions() {
        return getNumLexicalStates() > 1 && 
               regularExpressions.stream().anyMatch(re->re.getNewLexicalState()!=null);
    }

    public boolean getHasTokenActions() {
        return regularExpressions.stream().anyMatch(re->re.getCodeSnippet()!=null);
    }

    public int getNumLexicalStates() {
        return lexicalStates.size();
    }

    public List<LexicalStateData> getLexicalStates() {
        return lexicalStates;
    }

    private void addRegularExpression(RegularExpression regexp) {
        regexp.setOrdinal(regularExpressions.size());
        regularExpressions.add(regexp);
    }

    private void resolveStringLiterals() {
        for (RegexpStringLiteral rsl : grammar.descendants(RegexpStringLiteral.class, r->!r.isResolved())) {
            String label = getStringLiteralLabel(rsl.getLiteralString());
            rsl.setLabel(label);
            rsl.setResolved(true);
        }
    }
    
    private void ensureRegexpLabels() {
        for (ListIterator<RegularExpression> it = regularExpressions.listIterator();it.hasNext();) {
            RegularExpression regexp = it.next();
            if (!isJavaIdentifier(regexp.getLabel())) {
                String label = "_TOKEN_" + it.previousIndex();
                if (regexp instanceof RegexpStringLiteral) {
                    String s= ((RegexpStringLiteral)regexp).getLiteralString().toUpperCase();
                    if (isJavaIdentifier(s) && !regexpLabelAlreadyUsed(s)) label = s;
                }
                regexp.setLabel(label);
            }
        }
    }
   
    static public boolean isJavaIdentifier(String s) {
        return !s.isEmpty() && Character.isJavaIdentifierStart(s.codePointAt(0)) 
              && s.codePoints().allMatch(ch->Character.isJavaIdentifierPart(ch));
    }
   
    private boolean regexpLabelAlreadyUsed(String label) {
        for (RegularExpression regexp : regularExpressions) {
            if (label.contentEquals(regexp.getLabel())) return true;
        }
        return false;
    }
    
    public String getStringLiteralLabel(String image) {
        for (RegularExpression regexp : regularExpressions) {
            if (regexp instanceof RegexpStringLiteral) {
                if (regexp.getLiteralString().equals(image)) {
                    return regexp.getLabel();
                }
                if (regexp.getIgnoreCase() && regexp.getLiteralString().equalsIgnoreCase(image)) {
                    return regexp.getLabel();
                }
            }
        }
        return null;
    }

    public int getTokenCount() {
        return regularExpressions.size() + grammar.getAppSettings().getExtraTokenNames().size();
    }
    
    public TokenSet getMoreTokens() {
        return getTokensOfKind("MORE");
    } 

    public TokenSet getSkippedTokens() {
        return getTokensOfKind("SKIP");
    }
    
    public TokenSet getUnparsedTokens() {
        return getTokensOfKind("UNPARSED");
    }

    public TokenSet getRegularTokens() {
        TokenSet result = getTokensOfKind("TOKEN");
        for (RegularExpression re : regularExpressions) {
            if (re.getTokenProduction() == null) {
                result.set(re.getOrdinal());
            }
        }
        return result;
    }

    private TokenSet getTokensOfKind(String kind) {
        TokenSet result = new TokenSet(grammar);
        for (RegularExpression re : regularExpressions) {
            if (isOverridden(re)) continue;
            TokenProduction tp = re.getTokenProduction();
            if (tp != null && tp.getKind().equals(kind)) {
                result.set(re.getOrdinal());
            } 
        }
        return result;
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

    /**
     * Contains the same entries as "namedTokensTable", but this is an ordered
     * list which is ordered by the order of appearance in the input file.
     * (Actually, the only place where this is used is in generating the
     * TokenType enum)
     */
    public List<RegularExpression> getOrderedNamedTokens() {
        return new ArrayList<RegularExpression>(namedTokensTable.values());
    }


    // This method still really needs to be cleaned up!
    public void buildData() {
        for (TokenProduction tp : grammar.descendants(TokenProduction.class)) { 
            for (RegexpSpec res : tp.getRegexpSpecs()){
                RegularExpression re = res.getRegexp();
                if (re.hasLabel()) {
                    String label = re.getLabel();
                    RegularExpression regexp = getNamedToken(label);
                    if (regexp != null) {
                        errors.addInfo(res.getRegexp(),
                                "Token name \"" + label + " is redefined.");
                    } 
                    addNamedToken(label, re);
                }
            }
        }        
        for (TokenProduction tp : grammar.getAllTokenProductions()) {
            for (RegexpSpec res : tp.getRegexpSpecs()) {
                RegularExpression regexp = res.getRegexp();
                if (regexp.isPrivate() || regexp instanceof RegexpRef) continue;
                if (!(regexp instanceof RegexpStringLiteral)) {
                    addRegularExpression(res.getRegexp());
                } else {
                    RegexpStringLiteral stringLiteral = (RegexpStringLiteral) regexp;
                    String image = stringLiteral.getLiteralString();
            // This loop performs the checks and actions with respect to
                    // each lexical state.
                    for (String name : tp.getLexicalStateNames()) {
                        LexicalStateData lsd = getLexicalState(name);
                        RegexpStringLiteral alreadyPresent = lsd.getStringLiteral(image);
                        if (alreadyPresent == null) {
                            if (stringLiteral.getOrdinal() == 0) {
                                addRegularExpression(stringLiteral);
                            }
                            lsd.addStringLiteral(stringLiteral);
                        } 
                        else if (!tp.isExplicit()) {
                            String kind = alreadyPresent.getTokenProduction() == null ? "TOKEN" 
                                          : alreadyPresent.getTokenProduction().getKind();
                            if (!kind.equals("TOKEN")) {
                                errors.addError(stringLiteral,
                                        "String token \""
                                                + image
                                                + "\" has been defined as a \""
                                                + kind
                                                + "\" token.");
                            } else {
                                // This is now a reference to an
                                // existing StringLiteralRegexp.
                                stringLiteral.setOrdinal(alreadyPresent.getOrdinal());
                            }
                        }
                    }
                } 
            }
        }    
        ensureRegexpLabels();
        resolveStringLiterals();
        for (RegexpRef ref : grammar.descendants(RegexpRef.class)) {
            String label = ref.getLabel();
            if (grammar.getAppSettings().getExtraTokens().containsKey(label)) continue;
            RegularExpression referenced = getNamedToken(label);
            if (referenced == null) {
                errors.addError(ref,  "Undefined lexical token name \"" + label + "\".");
            } else if (ref.getTokenProduction() == null || !ref.getTokenProduction().isExplicit()) {
                if (referenced.isPrivate()) {
                    errors.addError(ref, "Token name \"" + label + "\" refers to a private (with a #) regular expression.");
                }   else if (!referenced.getTokenProduction().getKind().equals("TOKEN")) {
                    errors.addError(ref, "Token name \"" + label + "\" refers to a non-token (SKIP, MORE, UNPARSED) regular expression.");
                } 
            } 
            ref.setOrdinal(referenced.getOrdinal());
            ref.setRegexp(referenced);
        }        
// Check for self-referential loops in regular expressions
        new RegexpVisitor().visit(grammar);        
        for (TokenProduction tokenProduction : grammar.descendants(TokenProduction.class, tp->tp.isExplicit())) {
            for (String lexStateName : tokenProduction.getLexicalStateNames()) {
                LexicalStateData lexState = getLexicalState(lexStateName);
                lexState.addTokenProduction(tokenProduction);
            }
        }
        for (LexicalStateData lexState : lexicalStates) {
            lexState.process();
        }
    }


    /**
     * A visitor that checks whether there is a self-referential loop in a 
     * Regexp reference. 
     */
    class RegexpVisitor extends Node.Visitor {

        private HashSet<RegularExpression> alreadyVisited = new HashSet<>(), currentlyVisiting = new HashSet<>();

        void visit(RegexpRef ref) {
            RegularExpression referredTo = ref.getRegexp();
            if (referredTo != null && !alreadyVisited.contains(referredTo)) {
                if (!currentlyVisiting.contains(referredTo)) {
                    currentlyVisiting.add(referredTo);
                    visit(referredTo);
                    currentlyVisiting.remove(referredTo);
                } else {
                    alreadyVisited.add(referredTo);
                    errors.addError(ref, "Self-referential loop detected");
                }
            }
        }
    }
}