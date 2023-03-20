package org.congocc.core;

import org.congocc.parser.tree.BaseNode;
import org.congocc.parser.tree.CodeBlock;
import org.congocc.parser.tree.TokenProduction;


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

    public CodeBlock getCodeSnippet() {
        return firstChildOfType(CodeBlock.class);
    }

    public boolean isImplicit() {
        return !(getParent() instanceof TokenProduction);
    }
}


