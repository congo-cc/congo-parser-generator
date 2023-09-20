package org.congocc.core;

import org.congocc.core.ExpansionSequence.SyntaxElement;
import org.congocc.parser.Token.TokenType;
import org.congocc.parser.tree.*;
import java.util.Set;

public class NonTerminal extends Expansion implements SyntaxElement {
    
    private Name LHS;
    private boolean lhsProperty;
    private boolean suppressInjection;
    
    public Name getLHS() {return LHS;}
    public void setLHS(Name LHS) {this.LHS=LHS;}
    public boolean isLhsProperty() {return lhsProperty;}
    public void setLhsProperty(boolean lhsProperty) {this.lhsProperty=lhsProperty;}
    public boolean isSuppressInjection() {return suppressInjection;}
    public void setSuppressInjection(boolean suppressInjection) {this.suppressInjection=suppressInjection;}
    
    //REVISIT: why are the above properties coded here instead of being injected as is the case for Expansion?

    /**
     * The production this non-terminal corresponds to.
     */
    public BNFProduction getProduction() {
        return getGrammar().getProductionByName(getName());
    }

    public Expansion getNestedExpansion() {
        BNFProduction production = getProduction();
        if (production == null) {
            throw new NullPointerException("Production " + getName() + " is not defined. " + getLocation());
        }
        return production.getExpansion();
    }

    public Lookahead getLookahead() {
        return getNestedExpansion().getLookahead();
    }

    public InvocationArguments getArgs() {
        return firstChildOfType(InvocationArguments.class);
    }

    public String getName() {
        return firstChildOfType(TokenType.IDENTIFIER).getSource();
    }
    
    /**
     * The basic logic of when we actually stop at a scan limit 
     * encountered inside a NonTerminal
     */

    public boolean getStopAtScanLimit() {
        if (isInsideLookahead()) return false;
        ExpansionSequence parent = (ExpansionSequence) getNonSuperfluousParent();
        if (!parent.isAtChoicePoint()) return false;
        if (parent.getHasExplicitNumericalLookahead() || parent.getHasExplicitScanLimit()) return false;
        return parent.firstNonEmpty() == this;
    }

    public final boolean getScanToEnd() {
        return !getStopAtScanLimit();
    }

    public TokenSet getFirstSet() {
        if (firstSet == null) {
            firstSet = getNestedExpansion().getFirstSet();
        }
        return firstSet;
     }
     
     public TokenSet getFinalSet() {
          return getNestedExpansion().getFinalSet();
     }
     
     protected int getMinimumSize(Set<String> visitedNonTerminals) {
        if (minSize >=0) return minSize;
         if (visitedNonTerminals.contains(getName())) {
            return Integer.MAX_VALUE;
         }
         visitedNonTerminals.add(getName());
         minSize = getNestedExpansion().getMinimumSize(visitedNonTerminals);
         visitedNonTerminals.remove(getName());
         return minSize;
     }

     protected int getMaximumSize(Set<String> visitedNonTerminals) {
        if (maxSize >= 0) return maxSize;
        if (visitedNonTerminals.contains(getName())) {
            return Integer.MAX_VALUE;
        }
        visitedNonTerminals.add(getName());
        maxSize = getNestedExpansion().getMaximumSize(visitedNonTerminals);
        visitedNonTerminals.remove(getName());
        return maxSize;
     }
    
     // We don't nest into NonTerminals
     @Override
     public boolean getHasScanLimit() {
        Expansion exp = getNestedExpansion();
        if (exp instanceof ExpansionSequence) {
            for (Expansion sub : ((ExpansionSequence) exp).allUnits()) {
                if (sub.isScanLimit()) return true;
            }
        }
        return false;
     }

     public boolean isSingleTokenLookahead() {
        return getNestedExpansion().isSingleTokenLookahead();
     }

     public boolean startsWithLexicalChange() {
        if (getProduction().getLexicalState() != null) return true;
        Expansion nested = getNestedExpansion();
        if (nested instanceof ExpansionSequence) {
            for (Expansion sub : nested.childrenOfType(Expansion.class)) {
                if (!(sub instanceof NonTerminal)) {
                    // KLUDGE? For now we don't nest further into nonterminals
                    // It seems like we should, but the code blows up. I need
                    // to revisit this! For now, it's "good enough for government work, I suppose."
                    if (sub.startsWithLexicalChange()) return true;
                }
                if (!sub.isPossiblyEmpty()) break;
            }
        }
        return false;
     }

     public boolean startsWithGlobalCodeAction() {
        CodeBlock javaCode = getProduction().getJavaCode();
        if (javaCode != null && javaCode.isAppliesInLookahead()) return true;
        Expansion nested = getNestedExpansion();
        if (nested instanceof ExpansionSequence) {
            for (Expansion sub: nested.childrenOfType(Expansion.class)) {
                if (!(sub instanceof NonTerminal)) {
                    // We don't nest recursively into nonterminals here either.
                    if (sub.startsWithLexicalChange()) return true;
                }
                if (!sub.isPossiblyEmpty()) break;
            }
        }
        return false;
     }

     @Override
     public boolean potentiallyStartsWith(String productionName, Set<String> alreadyVisited) {
        if (productionName.equals(getName())) {
            return true;
        }
        if (alreadyVisited.contains(getName())) return false;
        alreadyVisited.add(getName());
        return getNestedExpansion().potentiallyStartsWith(productionName, alreadyVisited);
    }
}