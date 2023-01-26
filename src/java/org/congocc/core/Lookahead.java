package org.congocc.core;

import org.congocc.parser.BaseNode;
import org.congocc.parser.tree.*;

public class Lookahead extends BaseNode {
    private Name LHS;
    private Expansion expansion, nestedExpansion;
    private boolean negated, semanticLookaheadNested;
    private Expression semanticLookahead;

    public Name getLHS() {return LHS;}

    public void setLHS(Name LHS) {this.LHS = LHS;}

    public Expansion getExpansion() {return expansion;}

    public void setExpansion(Expansion expansion) {this.expansion=expansion;}

    public Expansion getNestedExpansion() {return nestedExpansion;}

    public void setNestedExpansion(Expansion nestedExpansion) {this.nestedExpansion = nestedExpansion;}

    public Expression getSemanticLookahead() {return semanticLookahead;}

    public void setSemanticLookahead(Expression semanticLookahead) {this.semanticLookahead = semanticLookahead;}

    public boolean isSemanticLookaheadNested() {return semanticLookaheadNested;}

    public void setSemanticLookaheadNested(boolean semanticLookaheadNested) {this.semanticLookaheadNested = semanticLookaheadNested;}

    public boolean isNegated() {return negated;}

    public void setNegated(boolean negated) {this.negated = negated;}

    public boolean isAlwaysSuccessful() {
        return !hasSemanticLookahead() && (getAmount() == 0 || getLookaheadExpansion().isPossiblyEmpty()); 
    }

    boolean getRequiresScanAhead() {
        if (!getLookaheadExpansion().isPossiblyEmpty()) return true;
        if (getSemanticLookahead() != null) return true;
        if (this.getLookBehind()!=null) return true;
        return getAmount() >0;
    }

    public boolean hasSemanticLookahead() {
        return getSemanticLookahead() != null;
    }
    
    public Expansion getLookaheadExpansion() {
        Expansion result = getNestedExpansion();
        if (result != null) {
            return result;
        }
        return expansion;
    }

    public boolean getHasExplicitNumericalAmount() {
        return firstChildOfType(TokenType.INTEGER_LITERAL) != null;
    }

    public int getAmount() {
        IntegerLiteral it = firstChildOfType(IntegerLiteral.class);
        if (it!=null) return it.getValue();
        if (this instanceof LegacyLookahead) {
            if (getNestedExpansion() == null && hasSemanticLookahead()) return 0;
        }
        return Integer.MAX_VALUE;
    }

    public LookBehind getLookBehind() {
        return firstChildOfType(LookBehind.class);
    }

}