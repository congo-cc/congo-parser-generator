package org.congocc.core;

import org.congocc.core.nfa.LexicalStateData;
import static org.congocc.core.LexerData.isJavaIdentifier;
import org.congocc.parser.tree.CodeBlock;
import org.congocc.parser.tree.TokenProduction;
import org.congocc.parser.tree.BaseNode;

/**
 * An abstract base class from which all the AST nodes that
 * are regular expressions inherit.
 */

public abstract class RegularExpression extends BaseNode {

    /**
     * The ordinal value assigned to the regular expression. It is used for
     * internal processing and passing information between the parser and the
     * lexical analyzer.
     */
    private int id;

    private LexicalStateData newLexicalState;

    public CodeBlock getCodeSnippet() {
        return (getParent() instanceof RegexpSpec) ? getParent().firstChildOfType(CodeBlock.class) : null;
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

    private String label;

    protected TokenProduction getTokenProduction() {
        return firstAncestorOfType(TokenProduction.class);
    }

    public final void setLabel(String label) {
        assert label.equals("") || isJavaIdentifier(label);
        this.label = label;
    }

    public final String getLabel() {
        if (label != null && label.length() != 0) {
            return label;
        }
        if (id == 0) {
            return "EOF";
        }
        return String.valueOf(id);
    }

    public final boolean hasLabel() {
        return label != null && isJavaIdentifier(label);
    }

    public int getOrdinal() {
        return id;
    }

    protected final void setOrdinal(int id) {
        this.id = id;
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
        if (generatedClassName.equals("Token")) {
            generatedClassName = getLabel();
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

    private String generatedClassName = "Token", generatedSuperClassName;

    abstract public boolean matchesEmptyString();
}
