package org.congocc.core;

/**
 * A convenience base class for defining empty expansions, i.e. that 
 * do not consume any tokens. 
 */

abstract public class EmptyExpansion extends Expansion {
    
    public boolean isPossiblyEmpty() {return true;}

    @Override
    public boolean isAlwaysEntered() {return true;}
    
    public TokenSet getFirstSet() {return new TokenSet(getGrammar());}
    
    public TokenSet getFinalSet() {return new TokenSet(getGrammar());}
     
    public int getMinimumSize() {return 0;}

    public int getMaximumSize() {return 0;}

    public boolean getSpecifiesLexicalStateSwitch() {return false;}
}