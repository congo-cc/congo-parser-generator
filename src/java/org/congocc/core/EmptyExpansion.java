package org.congocc.core;

/**
 * A convenience base class for defining empty expansions, i.e. that 
 * do not consume any tokens. 
 */
abstract public class EmptyExpansion extends Expansion {
    @Override
    public final boolean isAlwaysEntered() {return true;}
   
    @Override
    public final TokenSet getFirstSet() {return new TokenSet(getGrammar());}
   
    @Override
    public final TokenSet getFinalSet() {return new TokenSet(getGrammar());}
    
    @Override
    public final int getMinimumSize() {return 0;}

    @Override
    public final int getMaximumSize() {return 0;}
}