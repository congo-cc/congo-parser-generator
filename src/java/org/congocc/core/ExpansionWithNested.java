package org.congocc.core;

/**
 * A convenience base class for defining expansions with an inner 
 * expansion 
 */
abstract public class ExpansionWithNested extends Expansion {

    public Expansion getNestedExpansion() {return firstChildOfType(Expansion.class);}

    @Override
    public int getMinimumSize() {return getNestedExpansion().getMinimumSize();}

    @Override
    public int getMaximumSize() {return getNestedExpansion().getMaximumSize();}

    @Override
    public boolean isAlwaysEntered() {return getNestedExpansion().isAlwaysEntered();}
    
    @Override 
    public TokenSet getFirstSet() {return getNestedExpansion().getFirstSet();}
    
    @Override 
    public TokenSet getFinalSet() {return getNestedExpansion().getFinalSet();}

    @Override 
    public boolean potentiallyStartsWith(String productionName, java.util.Set<String> alreadyVisited) {
        return getNestedExpansion().potentiallyStartsWith(productionName, alreadyVisited);
    }

    @Override 
    public boolean startsWithLexicalChange() {
        return getNestedExpansion().startsWithLexicalChange();
    }

    @Override 
    public boolean startsWithGlobalCodeAction() {
        return getNestedExpansion().startsWithGlobalCodeAction();
    }
}