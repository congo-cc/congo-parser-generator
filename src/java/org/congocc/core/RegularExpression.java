package org.congocc.core;

import org.congocc.core.nfa.LexicalStateData;
import static org.congocc.core.LexerData.isJavaIdentifier;
import org.congocc.parser.tree.TokenProduction;
import org.congocc.parser.TokenSource;
import org.congocc.parser.tree.BaseNode;
import org.congocc.parser.tree.EmbeddedCode;
import org.congocc.parser.tree.EndOfFile;

/**
 * An abstract base class from which all the AST nodes that
 * are regular expressions inherit.
 */

public abstract class RegularExpression extends BaseNode {

    private Grammar grammar;
    private EmbeddedCode codeSnippet;

    public Grammar getGrammar() {
        if (grammar == null) {
            TokenSource ts = getTokenSource();
            if (ts != null) grammar = ts.getGrammar();
        }
        return grammar;
    }

    public void setGrammar(Grammar grammar) {
        this.grammar = grammar;
    }

    private LexicalStateData newLexicalState;

    public EmbeddedCode getCodeSnippet() {
        return codeSnippet;
    }

    public void setCodeSnippet(EmbeddedCode codeSnippet) {
        this.codeSnippet = codeSnippet;
    }

    public boolean getIgnoreCase() {
        TokenProduction tp = firstAncestorOfType(TokenProduction.class);
        if (tp != null)
            return tp.isIgnoreCase();
        return getAppSettings().isIgnoreCase();// REVISIT
    }

    /**
     * This flag is set if the regular expression has a label prefixed with the #
     * symbol - this indicates that the purpose of the regular expression is
     * solely for defining other regular expressions.
     */
    private boolean _private = false;

    String label;
    private int ordinal = -1;

    public TokenProduction getTokenProduction() {
        return firstAncestorOfType(TokenProduction.class);
    }

   public final void setLabel(String label) {
        assert label.equals("") || isJavaIdentifier(label);
        this.label = label;
    }

    public String getLabel() {
        if (label != null && label.length() != 0) {
            return label;
        }
        int id = getOrdinal();
        if (id == 0) {
            return label = "EOF";
        }
        String literalString = getLiteralString();
        if (literalString != null && isJavaIdentifier(literalString)) {
            literalString = literalString.toUpperCase();
            if (!getGrammar().getLexerData().regexpLabelAlreadyUsed(literalString, this)) {
                return label = literalString;
            }
        }
        assert id>=0;
        return label = "_TOKEN_" + id;
    }

    public final boolean hasLabel() {
        return label != null && isJavaIdentifier(label);
    }

    public int getOrdinal() {
        if (this instanceof EndOfFile) ordinal = 0;
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public boolean isContextual() {
        return false;
    }

    public void setNewLexicalState(LexicalStateData newLexicalState) {
        this.newLexicalState = newLexicalState;
    }

    public LexicalStateData getNewLexicalState() {
        return newLexicalState;
    }

    public boolean isPrivate() {
        return this._private;
    }

    public String getLiteralString() {
        return null;
    }

    public void setPrivate(boolean _private) {
        this._private = _private;
    }

    public String getGeneratedClassName() {
        if (generatedClassName==null) {
            if (hasLabel()) {
                generatedClassName = getLabel();
            } else {
                generatedClassName = getGrammar().getAppSettings().getBaseTokenClassName();
            }
        }
        return generatedClassName;
    }

    public void setGeneratedClassName(String generatedClassName) {
        this.generatedClassName = generatedClassName;
    }

    public String getGeneratedSuperClassName() {
        return generatedSuperClassName;
    }

    public void setGeneratedSuperClassName(String generatedSuperClassName) {
        this.generatedSuperClassName = generatedSuperClassName;
    }

    private String generatedClassName, generatedSuperClassName;

    abstract public boolean matchesEmptyString();
}
