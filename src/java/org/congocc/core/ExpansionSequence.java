package org.congocc.core;

import java.util.*;
import org.congocc.parser.Node;
import org.congocc.parser.tree.*;

public class ExpansionSequence extends Expansion {
	
	/**
	 * A marker interface in order to enumerate syntax element units within a sequence.
	 *
	 */
	public interface SyntaxElement extends Node {}

    /**
     * @return a List that includes child expansions that are
     *         inside of superfluous parentheses.
     */
    List<Expansion> allUnits() {
        List<Expansion> result = new ArrayList<>();
        for (Expansion unit : childrenOfType(Expansion.class)) {
            result.add(unit);
            if (unit.superfluousParentheses()) {
                result.addAll(unit.firstChildOfType(ExpansionSequence.class).allUnits());
            } 
        }
        return result;
    }
    
    final int getNumberOfSyntaxElements() {
    	return childrenOfType(SyntaxElement.class).size();
    }
    
    /**
     * Indicates that this {@link ExpansionSequence} is essential. That is, this
     * represents a sequence of more than one syntactical elements.
     * @return {@code true} iff the number of syntactical child elements > 1
     */
    public boolean isEssentialSequence() {
    	return getNumberOfSyntaxElements() > 1;
    }

    Expansion firstNonEmpty() {
        for (Expansion unit : childrenOfType(Expansion.class)) {
            if (unit instanceof ExpansionWithParentheses
                    && unit.superfluousParentheses()) {
                unit = unit.firstChildOfType(ExpansionSequence.class).firstNonEmpty();
                if (unit != null)
                    return unit;
            } else if (!unit.isPossiblyEmpty())
                return unit;
        }
        return null;
    }


    @Override
    public TokenSet getFirstSet() {
        if (firstSet == null) {
            firstSet = new TokenSet(getGrammar());
            for (Expansion child : allUnits()) {
                firstSet.or(child.getFirstSet());
                if (!child.isPossiblyEmpty()) {
                    break;
                }
            }
            Expansion lookaheadExpansion = getLookaheadExpansion();
            if (lookaheadExpansion != this) {
                if (!getLookahead().isNegated()) {
                    firstSet.and(lookaheadExpansion.getFirstSet());
                } 
                else if (lookaheadExpansion.isSingleToken()) {
                    firstSet.andNot(lookaheadExpansion.getFirstSet());
                }
            }
        }
        return firstSet;
    }

    @Override
    public TokenSet getFinalSet() {
        TokenSet finalSet = new TokenSet(getGrammar());
        List<Expansion> children = childrenOfType(Expansion.class);
        Collections.reverse(children);
        for (Expansion child : children) {
            finalSet.or(child.getFinalSet());
            if (!child.isPossiblyEmpty()) {
                break;
            }
        }
        return finalSet;
    }

    private boolean getRequiresScanAhead() {
        if (this.getHasExplicitScanLimit()) return true;
        for (Expansion unit : allUnits()) {
            if (unit instanceof NonTerminal) {
                NonTerminal nt = (NonTerminal) unit;
                if (nt.getHasScanLimit())
                    return true;
                if (nt.getProduction().getHasExplicitLookahead())
                    return true;
            }
            if (unit.getMaximumSize()>0) break;
        }
        Lookahead la = getLookahead();
        return la != null && la.getRequiresScanAhead();
    }

    private Lookahead lookahead;

    public void setLookahead(Lookahead lookahead) {
        this.lookahead = lookahead;
    }

    @Override
    public Lookahead getLookahead() {
        if (lookahead != null)
            return lookahead;
        for (Expansion unit : childrenOfType(Expansion.class)) {
            if (unit instanceof NonTerminal) {
                NonTerminal nt = (NonTerminal) unit;
                return nt.getLookahead();
            }
            if (unit.superfluousParentheses()) {
                ExpansionSequence seq = unit.firstChildOfType(ExpansionSequence.class);
                if (seq != null) {
                    return seq.getLookahead();
                }
            }
            if (unit.getMaximumSize() > 0)
                break;
        }
        return null;
    }

    public boolean getHasExplicitLookahead() {
        return lookahead != null;
    }

    public int getMinimumSize(Set<String> visitedNonTerminals) {
        if (this.minSize >= 0) return minSize;
        int result = 0;
        for (Expansion unit : childrenOfType(Expansion.class)) {
            int minUnit = unit.getMinimumSize(visitedNonTerminals);
            if (minUnit == Integer.MAX_VALUE)
                return Integer.MAX_VALUE;
            result += minUnit;
        }
        return result;
    }

    public int getMaximumSize(Set<String> visitedNonTerminals) {
        if (this.maxSize >=0) return maxSize;
        int result = 0;
        for (Expansion exp : childrenOfType(Expansion.class)) {
            int max = exp.getMaximumSize(visitedNonTerminals);
            if (max == Integer.MAX_VALUE)
                return Integer.MAX_VALUE;
            result += max;
        }
        return this.maxSize = result;
    }

    /**
     * @return whether we have a scan limit, including an implicit one inside a
     *         nested NonTerminal. However, the NonTerminal has to 
     *         start the sequence and we don't nest within the NonTerminal. I think
     *         that gets too confusing.
     */
    @Override
    public boolean getHasScanLimit() {
        boolean atStart = true;
        for (Expansion unit : allUnits()) {
            if (unit.isScanLimit())
                return true;
            if (atStart && unit instanceof NonTerminal) {
                atStart = false;
                if (unit.getHasScanLimit())
                    return true;
            }
            if (!(unit instanceof EmptyExpansion))
                atStart = false;
        }
        return false;
    }

    /**
     * @return whether we have an <em>explicit</em> scan limit,
     *         i.e. <em>not including</em> one that is inside a NonTerminal
     *         expansion.
     */
    @Override
    public boolean getHasExplicitScanLimit() {
        return allUnits().stream().anyMatch(Expansion::isScanLimit);
    }

    public List<Expansion> getUnits() {
        return childrenOfType(Expansion.class);
    }

    @Override
    public boolean potentiallyStartsWith(String productionName, Set<String> alreadyVisited) {
        boolean result = false;
        for (Expansion unit : allUnits()) {
            if (unit.potentiallyStartsWith(productionName, alreadyVisited)) result = true;
            if (!unit.isPossiblyEmpty()) break;
        }
        return result;
    }

    @Override
    public int getLookaheadAmount() {
        if (getHasExplicitScanLimit()) return Integer.MAX_VALUE;
        Lookahead la = getLookahead();
        if (la != null)
            return la.getAmount();
        return getRequiresScanAhead() ? Integer.MAX_VALUE : 1; // A bit kludgy, REVISIT 
    }

    /**
     * Does this expansion have a separate lookahead expansion?
     */
    @Override
    public boolean getHasSeparateSyntacticLookahead() {
        Lookahead la = getLookahead();
        return la != null && la.getNestedExpansion() != null;
    }

    @Override
    public Expansion getLookaheadExpansion() {
        Lookahead la = getLookahead();
        Expansion exp = la == null ? null : la.getNestedExpansion();
        return exp != null ? exp : this;
    }

    @Override
    public boolean isNegated() {
        return getLookahead() != null && getLookahead().isNegated();
    }

    @Override
    public LookBehind getLookBehind() {
        Lookahead la = getLookahead();
        return la == null ? null : la.getLookBehind();
    }

    @Override
    public final Expression getSemanticLookahead() {
        Lookahead la = getLookahead();
        return la == null ? null : la.getSemanticLookahead();
    }

    @Override
    public boolean getHasNumericalLookahead() {
        Lookahead la = getLookahead();
        return la != null && la.getHasExplicitNumericalAmount();
    }

    public boolean getHasExplicitNumericalLookahead() {
        return lookahead != null && lookahead.getHasExplicitNumericalAmount();
    }

    @Override
    public boolean getHasSemanticLookahead() {
        Lookahead la = getLookahead();
        return la != null && la.hasSemanticLookahead();
    }
    
   /**
     * Do we do a syntactic lookahead using this expansion itself as the lookahead
     * expansion?
     */
    @Override
    boolean getHasImplicitSyntacticLookahead() {
        if (!this.isAtChoicePoint())
            return false;
        if (getHasSeparateSyntacticLookahead())
            return false;
//        if (this.isAlwaysEntered())
//            return false;
        if (getHasScanLimit()) {
            return true;
        }
        if (getHasExplicitNumericalLookahead() && getLookaheadAmount() ==0)
            return false;
// REVISIT.            
//        if (getMaximumSize() <= 1) {
//            return false;
//        }
        return getLookahead() != null;
    }

    @Override
    public boolean isSingleTokenLookahead() {
        if (!super.isSingleTokenLookahead()) return false;
        for (Expansion exp : allUnits()) {
            // This is mostly in order to recurse into any NonTerminals 
            // in the expansion sequence.
            if (exp.getMaximumSize() == 1 && !exp.isSingleTokenLookahead()) return false;
        }
        return true;
    }

    @Override
    public boolean startsWithLexicalChange() {
        Node parent = getParent();
        if (parent instanceof BNFProduction) {
            if (((BNFProduction) parent).getLexicalState() != null) {
                return true;
            }
        }
        for (Expansion exp : childrenOfType(Expansion.class)) {
            if (exp.startsWithLexicalChange()) return true;
            if (!exp.isPossiblyEmpty()) break;
        }
        return false;
    }

    @Override
    public boolean startsWithGlobalCodeAction() {
        for (Expansion exp : allUnits()) {
            if (exp.startsWithGlobalCodeAction()) return true;
            if (!exp.isPossiblyEmpty()) break;
        }
        return false;
    }

    public final boolean getRequiresPredicateMethod() {
        if (!isAtChoicePoint()) {
            return false;
        }
        return getLookahead() != null && getLookahead().getRequiresScanAhead()
            || getHasImplicitSyntacticLookahead() 
            || startsWithGlobalCodeAction() 
            || startsWithLexicalChange();
    }

    public boolean isFailure() {
        for (Expansion exp : allUnits()) {
            if (exp instanceof Failure) return true;
        }
        return false;
    }
}