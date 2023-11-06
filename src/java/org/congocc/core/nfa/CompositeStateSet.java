package org.congocc.core.nfa;

import java.util.*;

import org.congocc.core.RegularExpression;

/**
 * A class representing a composite state set, i.e.
 * a set of one or more NfaState objects.
 * Each instance of this class ends up being implemented
 * in the generated code as an instance of the
 * XXXNfaData::NfaFunction functional interface.
 */
public class CompositeStateSet {
    private final Set<NfaState> states;
    final LexicalStateData lexicalState;
    private int index=-1;

    CompositeStateSet(Set<NfaState> states, LexicalStateData lsd) {
        this.states = new HashSet<>(states);
        this.lexicalState = lsd;
    }

    public int getIndex() {return index;}

    void setIndex(int index) {this.index = index;}

    public String getLabel() {
        for (NfaState state : states) {
            return state.getType().getLabel();
        }
        return null;
    }

    public RegularExpression getType() {
        assert index != 0;
        return states.iterator().next().getType();
    }

    public int getNumStates() {
        return states.size();
    }

    public NfaState getSingleState() {
        return states.iterator().next();
    }

    public boolean getHasFinalState() {
        return states.stream().anyMatch(state->state.getNextState().isFinal());
    }

    public String getMethodName() {
        String lexicalStateName = lexicalState.getName();
        if (lexicalStateName.equals("DEFAULT"))
            return "NfaIndex" + index;
        return "NfaName" + lexicalStateName + "Index" + index;
    }

    public String checkAllSameType() {
        String label = getLabel();
        for (NfaState state : states) {
            assert state.getType().getLabel().equals(label);
        }
        return "";
    }

    public boolean equals(Object other) {
        return (other instanceof CompositeStateSet)
               && ((CompositeStateSet)other).states.equals(this.states);
    }

    /**
     * We return the NFA states in this composite
     * in order (decreasing) of the ordinal of the nextState's
     * ordinal, i.e. in increasing order of its priority in
     * terms of pattern matching.
     * @return sorted list of states
     */
    public List<NfaState> getOrderedStates() {
        List<NfaState> result = new ArrayList<>(states);
        result.sort(this::nfaComparator);
        return result;
    }

    private int nfaComparator(NfaState state1, NfaState state2) {
        int result = getOrdinal(state2.getNextState()) - getOrdinal(state1.getNextState());
        if (result == 0)
           result = (state1.getMoveRanges().get(0) - state2.getMoveRanges().get(0));
        if (result == 0)
           result = (state1.getMoveRanges().get(1) - state2.getMoveRanges().get(1));
        if (result == 0)
           result = state1.getMoveRanges().size() - state2.getMoveRanges().size();
        return result;
    }

    static private int getOrdinal(NfaState state) {
        return !state.isFinal() ? Integer.MAX_VALUE : state.getType().getOrdinal();
    }

    // Recursive method to figure out which composite state sets are actually used.
    // We invoke this on a lexical state's initial state.
    void findWhatIsUsed(Set<CompositeStateSet> alreadyVisited, Set<CompositeStateSet> usedStates) {
        if (alreadyVisited.contains(this)) return;
        alreadyVisited.add(this);
        if (states.isEmpty()) return;
        usedStates.add(this);
        for (NfaState state : states) {
            state.getNextState().getComposite().findWhatIsUsed(alreadyVisited, usedStates);
        }
    }
}
