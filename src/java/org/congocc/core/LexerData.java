package org.congocc.core;

import java.util.*;

import org.congocc.parser.Node;
import org.congocc.app.Errors;
import org.congocc.core.nfa.LexicalStateData;
import org.congocc.parser.tree.*;

/**
 * Base object that contains lexical data. It contains LexicalStateData objects
 * that contain the data for each lexical state. The LexicalStateData objects
 * hold the data related to generating the NFAs for the respective lexical
 * states.
 */
@SuppressWarnings("unused")
public class LexerData {
    private final Grammar grammar;
    private final Errors errors;
    private final List<LexicalStateData> lexicalStates = new ArrayList<>();
    private final List<RegularExpression> regularExpressions = new ArrayList<>();

    private final Map<String, RegularExpression> namedTokensTable = new HashMap<>();
    private final Set<RegularExpression> overriddenTokens = new HashSet<>();
    private final Set<RegularExpression> lazyTokens = new HashSet<>();

    public LexerData(Grammar grammar) {
        this.grammar = grammar;
        this.errors = grammar.getErrors();
        RegularExpression reof = new EndOfFile();
        reof.setGrammar(grammar);
        regularExpressions.add(reof);
    }

    public boolean isLazy(RegularExpression type) {
        return lazyTokens.contains(type);
    }

    public int getOrdinal(RegularExpression re) {
        return regularExpressions.indexOf(re);
    }

    public String getTokenName(int ordinal) {
        if (ordinal < regularExpressions.size()) {
            return regularExpressions.get(ordinal).getLabel();
        }
        return grammar.getAppSettings().getExtraTokenNames().get(ordinal - regularExpressions.size());
    }

    public String getLexicalStateName(int index) {
        return lexicalStates.get(index).getName();
    }

    void addLexicalState(String name) {
        if (getLexicalState(name) == null) {
            lexicalStates.add(new LexicalStateData(grammar, name));
        }
    }

    public LexicalStateData getLexicalState(String name) {
        for (LexicalStateData lsd : lexicalStates) {
            if (lsd.getName().equals(name)) {
                return lsd;
            }
        }
        return null;
    }

    public int getMaxNfaStates() {
        return lexicalStates.stream().mapToInt(state -> state.getAllNfaStates().size()).max().getAsInt();
    }

    public List<RegularExpression> getRegularExpressions() {
        List<RegularExpression> result = new ArrayList<>(regularExpressions);
        result.removeIf(this::isOverridden);
        return result;
    }

    public boolean getHasLexicalStateTransitions() {
        return getNumLexicalStates() > 1 && regularExpressions.stream().anyMatch(re -> re.getNewLexicalState() != null);
    }

    public boolean getHasTokenActions() {
        return regularExpressions.stream().anyMatch(re -> re.getCodeSnippet() != null);
    }

    public int getNumLexicalStates() {
        return lexicalStates.size();
    }

    public List<LexicalStateData> getLexicalStates() {
        return lexicalStates;
    }

    private void addRegularExpression(RegularExpression regexp) {
        regularExpressions.add(regexp);
        if (regexp instanceof RegexpStringLiteral) {
            RegexpStringLiteral stringLiteral = (RegexpStringLiteral) regexp;
            for (String lexicalStateName : stringLiteral.getLexicalStateNames()) {
                LexicalStateData lsd = getLexicalState(lexicalStateName);
                lsd.addStringLiteral(stringLiteral);
            }
        }
    }

    public List<String> getTokenNames() {
        List<String> result = new ArrayList<>();
        for (RegularExpression regexp : regularExpressions) {
            result.add(regexp.getLabel());
        }
        return result;
    }
    

    static public boolean isJavaIdentifier(String s) {
        return !s.isEmpty() && Character.isJavaIdentifierStart(s.codePointAt(0))
                && s.codePoints().allMatch(Character::isJavaIdentifierPart);
    }

    boolean regexpLabelAlreadyUsed(String label, RegularExpression re) {
        for (RegularExpression regexp : regularExpressions) {
            if (regexp == re || regexp.label == null) continue;
            if (label.contentEquals(regexp.label))
                return true;
            if (label.equalsIgnoreCase(regexp.getLiteralString())) {
                return true;
            }
        }
        return false;
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
            if (isOverridden(re))
                continue;
            TokenProduction tp = re.getTokenProduction();
            if (tp != null && tp.getKind().equals(kind)) {
                result.set(re.getOrdinal());
            }
        }
        return result;
    }

    private void addNamedToken(String name, RegularExpression regexp) {
        if (namedTokensTable.containsKey(name)) {
            RegularExpression oldValue = namedTokensTable.get(name);
            namedTokensTable.replace(name, oldValue, regexp);
            overriddenTokens.add(oldValue);
        } else
            namedTokensTable.put(name, regexp);
    }

    public boolean isOverridden(RegularExpression regexp) {
        return overriddenTokens.contains(regexp);
    }

    public List<RegularExpression> getOrderedNamedTokens() {
        return new ArrayList<>(namedTokensTable.values());
    }

    // This method still really needs to be cleaned up!
    void buildData() {
        for (RegexpStringLiteral stringLiteral : grammar.descendants(RegexpStringLiteral.class, rsl->rsl.getParent() instanceof RegexpSpec)) {
            if (stringLiteral.hasLabel()) {
                String label = stringLiteral.getLabel();
                RegularExpression regexp = namedTokensTable.get(label);
                if (regexp != null) {
                    errors.addInfo(stringLiteral, "Token name \"" + label + " is redefined.");
                }
                addNamedToken(label, stringLiteral);
            }
            if (!stringLiteral.isPrivate()) {
                addRegularExpression(stringLiteral);
            }
        }
        for (RegexpStringLiteral stringLiteral : grammar.descendants(RegexpStringLiteral.class, rsl->rsl.getParent() instanceof Terminal)) {
            String image = stringLiteral.getLiteralString();
            String lexicalStateName = stringLiteral.getLexicalState();
            LexicalStateData lsd = getLexicalState(lexicalStateName);
            RegexpStringLiteral alreadyPresent = lsd.getStringLiteral(image);
            if (alreadyPresent == null) {
                addRegularExpression(stringLiteral);
            } else {
                String kind = alreadyPresent.getTokenProduction() == null ? "TOKEN"
                        : alreadyPresent.getTokenProduction().getKind();
                if (!kind.equals("TOKEN")) {
                    errors.addError(stringLiteral,
                            "String token \"" + image + "\" has been defined as a \"" + kind + "\" token.");
                } else {
                    stringLiteral.setCanonicalRegexp(alreadyPresent);
                }
            }
        }
        for (TokenProduction tp : grammar.descendants(TokenProduction.class)) {
            for (RegexpSpec res : tp.getRegexpSpecs()) {
                RegularExpression re = res.getRegexp();
                if (res.isLazy()) {
                    lazyTokens.add(re);
                }
                if (re instanceof RegexpStringLiteral) continue;
                if (re.hasLabel()) {
                    String label = re.getLabel();
                    RegularExpression regexp = namedTokensTable.get(label);
                    if (regexp != null) {
                        errors.addInfo(res.getRegexp(), "Token name \"" + label + " is redefined.");
                    }
                    addNamedToken(label, re);
                }
                if (!re.isPrivate()) {
                    addRegularExpression(re);
                }
            }
        }
        for (RegexpRef ref : grammar.descendants(RegexpRef.class)) {
            String label = ref.getLabel();
            if (grammar.getAppSettings().getExtraTokens().containsKey(label)) {
                continue;
            }
            RegularExpression referenced = namedTokensTable.get(label);
            if (referenced == null) {
                errors.addError(ref, "Undefined lexical token name \"" + label + "\".");
            } else if (ref.getTokenProduction() == null) {
                if (referenced.isPrivate()) {
                    errors.addError(ref,
                            "Token name \"" + label + "\" refers to a private (with a #) regular expression.");
                } else if (!referenced.getTokenProduction().getKind().equals("TOKEN")) {
                    errors.addError(ref, "Token name \"" + label
                            + "\" refers to a non-token (SKIP, MORE, UNPARSED) regular expression.");
                }
            }
            if (referenced != null) {
                ref.setRegexp(referenced);
            }
        }
        // Check for self-referential loops in regular expressions
        new RegexpVisitor().visit(grammar);
        for (TokenProduction tokenProduction : grammar.descendants(TokenProduction.class)) {
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
     * A visitor that checks whether there is a self-referential loop in a Regexp
     * reference.
     */
    class RegexpVisitor extends Node.Visitor {

        private final Set<RegularExpression> alreadyVisited = new HashSet<>();
        private final Set<RegularExpression> currentlyVisiting = new HashSet<>();

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
