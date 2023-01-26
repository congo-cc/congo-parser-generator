package org.congocc.core;

import java.util.List;
import java.util.Set;

public class ExpansionChoice extends Expansion {
    public List<ExpansionSequence> getChoices() {
        return childrenOfType(ExpansionSequence.class);
    }
    
    public TokenSet getFirstSet() {
         if (firstSet == null) {
            firstSet = new TokenSet(getGrammar());
            for (ExpansionSequence choice : childrenOfType(ExpansionSequence.class)) {
                firstSet.or(choice.getLookaheadExpansion().getFirstSet());
            }
         }
         return firstSet;
    }
    
    public TokenSet getFinalSet() {
        TokenSet finalSet = new TokenSet(getGrammar());
        for (ExpansionSequence choice : childrenOfType(ExpansionSequence.class)) {
            finalSet.or(choice.getFinalSet());
        }
        return finalSet;
    }
    
    
    public boolean isPossiblyEmpty() {
        return childrenOfType(ExpansionSequence.class).stream().anyMatch(choice->choice.isPossiblyEmpty());
    }
 
    public boolean isAlwaysSuccessful() {
        return childrenOfType(ExpansionSequence.class).stream().anyMatch(choice->choice.isAlwaysSuccessful());
    }
    
    public int getMinimumSize() {
        int result = Integer.MAX_VALUE;
        for (ExpansionSequence choice : childrenOfType(ExpansionSequence.class)) {
           int choiceMin = choice.getMinimumSize();
           if (choiceMin ==0) return 0;
           result = Math.min(result, choiceMin);
        }
        return result;
    }
 
    public int getMaximumSize() {
        int result = 0;
        for (ExpansionSequence exp : childrenOfType(ExpansionSequence.class)) {
            result = Math.max(result, exp.getMaximumSize());
            if (result == Integer.MAX_VALUE) break;
        }
        return result;
    }
    
    public boolean potentiallyStartsWith(String productionName, Set<String> alreadyVisited) {
        for (ExpansionSequence choice : childrenOfType(ExpansionSequence.class)) {
            if (choice.potentiallyStartsWith(productionName, alreadyVisited)) return true;
        }
        return false;
    }

    public boolean isSingleToken() {
        if (!super.isSingleToken()) return false;
        for (ExpansionSequence exp : childrenOfType(ExpansionSequence.class)) {
            if (!exp.isSingleToken()) return false;
        }
        return true;
    }
}
