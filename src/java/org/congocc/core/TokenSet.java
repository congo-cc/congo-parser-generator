package org.congocc.core;

import java.util.*;

/**
 * A class to represent a set of Token types.
 * Will probably eventually move this into the Token.java.ctl as
 * something available to all generated parsers.
 */
public class TokenSet extends BitSet {

	private static final long serialVersionUID = 1L;

	private final Grammar grammar;

	private boolean incomplete;

	public TokenSet(Grammar grammar) {
		this.grammar = grammar;
	}

	public TokenSet(Grammar grammar, boolean incomplete) {
		this.grammar=grammar;
		this.incomplete = incomplete;
	}

	public boolean isIncomplete() {
		return incomplete;
	}

	public void setIncomplete(boolean incomplete) {
		this.incomplete = incomplete;
	}

	public boolean getHasContextualTokens() {
		return stream().anyMatch(i->grammar.getLexerData().isContextualToken(i));
	}

	public List<String> getTokenNames() {
		List<String> names = new ArrayList<>();
		int tokCount = grammar.getLexerData().getTokenCount();
		for (int i = 0; i<tokCount; i++) {
			if (get(i)) {
				names.add(grammar.getLexerData().getTokenName(i));
			}
		}
		return names;
	}

	public TokenSet copy() {
		TokenSet result = new TokenSet(grammar);
		result.or(this);
		return result;
	}
}
