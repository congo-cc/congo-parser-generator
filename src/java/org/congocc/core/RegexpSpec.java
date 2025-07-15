package org.congocc.core;

import org.congocc.parser.tree.BaseNode;
import org.congocc.parser.tree.CodeBlock;
import org.congocc.parser.tree.EmbeddedCode;
import org.congocc.parser.tree.TokenProduction;
import org.congocc.parser.Token;


public class RegexpSpec extends BaseNode {
    private String nextLexicalState;

    public String getNextLexicalState() {
        return nextLexicalState;
    }

    public void setNextLexicalState(String nextLexicalState) {
        this.nextLexicalState = nextLexicalState;
    }

    public RegularExpression getRegexp() {
        return firstChildOfType(RegularExpression.class);
    }

    public EmbeddedCode getCodeSnippet() {
        return firstChildOfType(EmbeddedCode.class);
    }

    public boolean isImplicit() {
        return !(getParent() instanceof TokenProduction);
    }

    public boolean isLazy() {
        return firstChildOfType(Token.TokenType.HOOK) != null;
    }
}


