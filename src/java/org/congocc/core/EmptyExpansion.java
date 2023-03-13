package org.congocc.core;

import java.util.Set;

/**
 * A convenience base class for defining empty expansions, i.e. that
 * do not consume any tokens.
 */
abstract public class EmptyExpansion extends Expansion {
    @Override
    public final TokenSet getFirstSet() {
        return new TokenSet(getGrammar());
    }

    @Override
    public final TokenSet getFinalSet() {
        return new TokenSet(getGrammar());
    }

    @Override
    protected final int getMinimumSize(Set<String> unused) {
        return 0;
    }

    @Override
    protected final int getMaximumSize(Set<String> unused) {
        return 0;
    }
}