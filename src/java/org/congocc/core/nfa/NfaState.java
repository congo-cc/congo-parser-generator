package org.congocc.core.nfa;

import java.util.*;
import org.congocc.core.RegularExpression;

/**
 * Class representing a single state of a Non-deterministic Finite Automaton (NFA)
 * Note that any given lexical state is implemented as an NFA.
 * Thus, any given NfaState object is associated with one lexical state.
 */
public class NfaState {  

    final LexicalStateData lexicalState;
    private RegularExpression type;
    private NfaState nextState;
    private final Set<NfaState> epsilonMoves = new HashSet<>();
    private String movesArrayName;
    private boolean isFinal;

    // An ordered list of the ranges of characters that this 
    // NfaState "accepts". A single character is stored as a 
    // range in which the left side is the same as the right side.
    // Thus, for example, the (ASCII) characters that can start an identifier would be:
    // '$','$','A','Z','_','_',a','z'
    private final List<Integer> moveRanges = new ArrayList<>();

    NfaState(LexicalStateData lexicalState, RegularExpression type) {
        this.lexicalState = lexicalState;
        this.type = type;
        lexicalState.addState(this);
    }

    void setFinal(boolean isFinal) {
        this.isFinal = isFinal;
    }

    public String getMethodName() {
        return getComposite().getMethodName();
    }

    public boolean isFinal() {return isFinal;}

    void setMovesArrayName(int index) {
        String lexicalStateName = lexicalState.getName();
        if (lexicalStateName.equals("DEFAULT")) 
            movesArrayName = "NFA_MOVES_" + index;
        else 
            movesArrayName = "NFA_MOVES_" + lexicalStateName + "_" + index; 
    }

    public String getMovesArrayName() {
        return movesArrayName;
    }

    public List<Integer> getMoveRanges() { return moveRanges; }

    public List<Integer> getAsciiMoveRanges() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i<moveRanges.size(); i+=2) {
            int left = moveRanges.get(i);
            int right = moveRanges.get(i+1);
            if (left >= 128) break;
            result.add(left);
            result.add(right);
            if (right >=128) break;
        }
        return result;
    }

    public List<Integer> getNonAsciiMoveRanges() {
        return moveRanges.subList(getAsciiMoveRanges().size(), moveRanges.size());
    }

    public boolean getHasAsciiMoves() {
        return moveRanges.get(0) < 128;
    }

    public boolean getHasNonAsciiMoves() {
        return moveRanges.get(moveRanges.size()-1) >= 128;
    }

    public RegularExpression getType() { return type; }

    public LexicalStateData getLexicalState() {return lexicalState;}

    public NfaState getNextState() {return nextState;}

    public int getNextStateIndex() {
        return nextState.getComposite().getIndex();
    }

    void setNextState(NfaState nextState) {
        assert nextState != this;
        this.nextState = nextState;
    }

    /**
     * @return the CompositeStateSet object that this NfaState is
     * part of.
     */
    public CompositeStateSet getComposite() {
        return lexicalState.getCanonicalComposite(epsilonMoves);
    }

    boolean isMoveCodeNeeded() {
        if (nextState == null) return false;
        return nextState.isFinal() || !nextState.epsilonMoves.isEmpty();
    }

    void setType(RegularExpression type) {
        this.type = type;
    }

    void addEpsilonMove(NfaState newState) {
        epsilonMoves.add(newState);
    }

    void addRange(int left, int right) {
        moveRanges.add(left);
        moveRanges.add(right);
    }

    void setCharMove(int c, boolean ignoreCase) {
        moveRanges.clear();
        if (!ignoreCase) {
            addRange(c, c);
        } else {//REVISIT, kinda messy
            int upper = Character.toUpperCase(c);
            int lower = Character.toLowerCase(c);
            addRange(upper, upper);
            if (upper != lower)
                addRange(lower, lower);
            if (c != upper && c!= lower)
                addRange(c, c);
            if (moveRanges.size() >2)
                Collections.sort(moveRanges);
        }
    }

    void doEpsilonClosure(Set<NfaState> alreadyVisited) {
        if (alreadyVisited.contains(this)) return;
        alreadyVisited.add(this);
        // Recursively do closure
        for (NfaState state : new ArrayList<>(epsilonMoves)) {
            state.doEpsilonClosure(alreadyVisited);
            if (this.isFinal) state.isFinal = true;
            if (state.isFinal) this.isFinal = true;
            for (NfaState otherState : state.epsilonMoves) {
                addEpsilonMove(otherState);
                otherState.doEpsilonClosure(alreadyVisited);
            }
        }
        addEpsilonMove(this);
        epsilonMoves.removeIf(state->state.moveRanges.isEmpty());
    }

    public boolean overlaps(Collection<NfaState> states) {
        return states.stream().anyMatch(this::overlaps);
    }

    private boolean overlaps(NfaState other) {
        return this == other || intersect(this.moveRanges, other.moveRanges);
    }

    static private BitSet moveRangesToBS(List<Integer> ranges) {
        BitSet result = new BitSet();
        for (int i=0; i< ranges.size(); i+=2) {
            int left = ranges.get(i);
            int right = ranges.get(i+1);
            result.set(left, right+1);
        }
        return result;
    }

    static private boolean intersect(List<Integer> moves1, List<Integer> moves2) {
        BitSet bs1 = moveRangesToBS(moves1);
        BitSet bs2 = moveRangesToBS(moves2);
        return bs1.intersects(bs2);
    }
}
