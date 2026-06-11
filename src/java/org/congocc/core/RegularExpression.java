package org.congocc.core;

import org.congocc.core.nfa.LexicalStateData;
import static org.congocc.core.LexerData.isJavaIdentifier;
import org.congocc.parser.tree.TokenProduction;
import org.congocc.parser.TokenSource;
import org.congocc.parser.tree.BaseNode;
import org.congocc.parser.tree.EmbeddedCode;
import org.congocc.parser.tree.EndOfFile;
import org.congocc.parser.tree.RegexpStringLiteral;
import java.util.Set;

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
            return this.label = "EOF";
        }
        if (this instanceof RegexpStringLiteral) {
            return this.label = createLabel(getLiteralString());
        }
        assert id > 0;
        return this.label = "_TOKEN_" + id;
    }

    private String createLabel(String literalString) {
        literalString = literalString.toUpperCase();
        String newLabel = alphabetize(literalString);
        if (!isJavaIdentifier(newLabel)) {
            newLabel = "_TOKEN_" + getOrdinal();
        }
        RegularExpression alreadyUsing = getGrammar().getLexerData().regexpLabelAlreadyUsed(newLabel, this);
        if (alreadyUsing == null) {
            return this.label = newLabel;
        }
        String lexState = ((RegexpStringLiteral) this).getImplicitLexicalState();
        if (!alreadyUsing.isInLexicalState(lexState)) {
            newLabel = "_" + lexState + "_" + newLabel;
            alreadyUsing = getGrammar().getLexerData().regexpLabelAlreadyUsed(newLabel, this);
            if (alreadyUsing == null) {
                return this.label = newLabel;
            }
        }
        for (int i = 0; i < 1000; i++) {
           String tryThisOne = newLabel + "_" + i;
           alreadyUsing = getGrammar().getLexerData().regexpLabelAlreadyUsed(newLabel, this);
           if (alreadyUsing == null) {
              newLabel = tryThisOne;
              break;
           }
        }
        return this.label = newLabel;
    }

    private String alphabetize(String s) {
        if (s.equals("_")) {
            // special case
            return "_UNDERSCORE";
        }
        StringBuilder buf = new StringBuilder();
        int firstCodePoint = s.codePointAt(0);
        int startRest = 0;
        if (Character.isJavaIdentifierStart(firstCodePoint)) {
            buf.appendCodePoint(s.codePointAt(0));
            startRest = firstCodePoint > 0xFFFF ? 2 : 1;
        }
        s.substring(startRest).codePoints().forEach(
            ch -> buf.append(alphabetize(ch))
        );
        return buf.toString();
    }

    private String alphabetize(int ch) {
        if (Character.isJavaIdentifierPart(ch)) {
            return codePointToString(ch);
        }
        return switch(ch) {
            case '&' -> "_AMPERSAND";
            case '@' -> "_AT";
            case '\\' -> "_BACKSLASH";
            case ':' -> "_COLON";
            case '=' -> "_EQUALS";
            case '{' -> "_LBRACE";
            case '[' -> "_LBRACKET";
            case '(' -> "_LPAREN";
            case '-' -> "_MINUS";
            case '%' -> "_PERCENT";
            case '+' -> "_PLUS";
            case '?' -> "_QUESTION";
            case '}' -> "_RBRACE";
            case ']' -> "_RBRACKET";
            case ')' -> "_RPAREN";
            case ';' -> "_SEMICOLON";
            case '/' -> "_SLASH";
            case '*' -> "_STAR";
            case '~' -> "_TILDE";
            default -> codePointToString(ch);
        };
    }

    static private String codePointToString(int ch) {
        StringBuilder buf = new StringBuilder();
        buf.appendCodePoint(ch);
        return buf.toString();
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

    public Set<String> getLexicalStateNames() {
       return getTokenProduction().getLexicalStateNames();
    }

    public boolean isInLexicalState(String lexicalState) {
       //return isContextual() || getLexicalStateNames().contains(lexicalState);
       return getLexicalStateNames().contains(lexicalState);
    }

    abstract public boolean matchesEmptyString();
}
