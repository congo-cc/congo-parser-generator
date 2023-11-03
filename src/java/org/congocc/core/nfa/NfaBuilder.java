package org.congocc.core.nfa;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.congocc.core.Grammar;
import org.congocc.core.RegularExpression;
import org.congocc.parser.Node;
import org.congocc.parser.tree.*;

/**
 * A Visitor object that builds a "finite automaton" from a Regular expression. 
 * This visitor object builds a lot of dummy states that are 
 * effectively a kind of scaffolding that are removed in a separate stage, 
 * after doing the so-called "epsilon closure". At that point, the various
 * remaining NfaState objects (the ones that are actually used) are 
 * consolidated into CompositeStateSet objects that get expressed as
 * NFA_XXX methods in the generated code.
 * 
 * @author revusky
 */
class NfaBuilder extends Node.Visitor {

    // the starting and ending NfaState objects
    // of the last regexp that we "visited"
    private NfaState start, end;
    private final boolean ignoreCase;
    private final LexicalStateData lexicalState;
    final Grammar grammar;
    private final RegularExpression type;

    NfaBuilder(LexicalStateData lexicalState, RegularExpression type, boolean ignoreCase) {
        this.lexicalState = lexicalState;
        this.type = type;
        this.grammar = lexicalState.grammar;
        this.ignoreCase = ignoreCase;
    }

    /**
     * This method sets the start and end states
     * of the regexp passed in. The start state is
     * then added as an "epsilon move" to the lexical state's
     * initial state.
     */
    void buildStates() {
        visit(type);
        end.setFinal(true);
        lexicalState.getInitialState().addEpsilonMove(start);
    }

    void visit(CharacterList charList) {
        List<CharacterRange> ranges = orderedRanges(charList, ignoreCase);
        start = new NfaState(lexicalState, type);
        end = new NfaState(lexicalState, type);
        for (CharacterRange cr : ranges) {
            start.addRange(cr.getLeft(), cr.getRight());
        }
        start.setNextState(end);
    }

    void visit(ZeroOrMoreRegexp zom) {
        final NfaState startState = new NfaState(lexicalState, type);
        final NfaState finalState = new NfaState(lexicalState, type);
        visit(zom.getRegexp());
        startState.addEpsilonMove(this.start);
        startState.addEpsilonMove(finalState);
        this.end.addEpsilonMove(finalState);
        this.end.addEpsilonMove(this.start);
        this.start = startState;
        this.end = finalState;
    }

    void visit(OneOrMoreRegexp oom) {
        final NfaState startState = new NfaState(lexicalState, type);
        final NfaState finalState = new NfaState(lexicalState, type);
        visit(oom.getRegexp());
        startState.addEpsilonMove(this.start);
        this.end.addEpsilonMove(this.start);
        this.end.addEpsilonMove(finalState);
        this.start = startState;
        this.end = finalState;
    }

    void visit(RegexpChoice choice) {
        List<RegularExpression> choices = choice.getChoices();
        if (choices.size() == 1) {
            visit(choices.get(0));
            return;
        }
        NfaState startState = new NfaState(lexicalState, type);
        NfaState finalState = new NfaState(lexicalState, type);
        for (RegularExpression curRE : choices) {
            visit(curRE);
            startState.addEpsilonMove(this.start);
            this.end.addEpsilonMove(finalState);
        }
        this.start = startState;
        this.end = finalState;
    }

    void visit(RegexpStringLiteral stringLiteral) {
        NfaState state = end = start = new NfaState(lexicalState, type);
        for (int ch : stringLiteral.getLiteralString().codePoints().toArray()) {
            state.setCharMove(ch, ignoreCase || grammar.getAppSettings().isIgnoreCase());
            this.end = new NfaState(lexicalState, type);
            state.setNextState(this.end);
            state = this.end;
        }
    }

    void visit(ZeroOrOneRegexp zoo) {
        NfaState startState = new NfaState(lexicalState, type);
        NfaState finalState = new NfaState(lexicalState, type);
        visit(zoo.getRegexp());
        startState.addEpsilonMove(this.start);
        startState.addEpsilonMove(finalState);
        this.end.addEpsilonMove(finalState);
        this.start = startState;
        this.end = finalState;
    }

    void visit(RegexpRef ref) {
        visit(ref.getRegexp());
    }

    void visit(RegexpSequence sequence) {
        if (sequence.getUnits().size() == 1) {
            visit(sequence.getUnits().get(0));
            return;
        }
        NfaState startState = new NfaState(lexicalState, type);
        NfaState finalState = new NfaState(lexicalState, type);
        NfaState prevStartState = null;
        NfaState prevEndState = null;
        for (RegularExpression re : sequence.getUnits()) {
            visit(re);
            if (prevStartState == null) {
                startState.addEpsilonMove(this.start);
            } else {
                prevEndState.addEpsilonMove(this.start);
            }
            prevStartState = this.start;
            prevEndState = this.end;
        }
        this.end.addEpsilonMove(finalState);
        this.start = startState;
        this.end = finalState;
    }

    void visit(RepetitionRange repRange) {
        RegexpSequence seq = new RegexpSequence();
        for (int i=0; i < repRange.getMin(); i++) {
            seq.add(repRange.getRegexp());
        }
        if (repRange.hasMax() && repRange.getMax() == -1) { // Unlimited
            ZeroOrMoreRegexp zom = new ZeroOrMoreRegexp();
            zom.setGrammar(grammar);
            zom.setRegexp(repRange.getRegexp());
            seq.add(zom);
        }
        else for (int i = repRange.getMin(); i< repRange.getMax(); i++) {
            ZeroOrOneRegexp zoo = new ZeroOrOneRegexp();
            zoo.setGrammar(grammar);
            zoo.setRegexp(repRange.getRegexp());
            seq.add(zoo);
        }
        visit(seq);
    }

    static private List<CharacterRange> orderedRanges(CharacterList charList, boolean caseNeutral) {
        BitSet bs = rangeListToBS(charList.getDescriptors());
        if (caseNeutral) {
            BitSet upperCaseDiffPoints = (BitSet) bs.clone();
            BitSet lowerCaseDiffPoints = (BitSet) bs.clone();
            upperCaseDiffPoints.and(upperCaseDiffSet);
            lowerCaseDiffPoints.and(lowerCaseDiffSet);
            upperCaseDiffPoints.stream().forEach(ch -> bs.set(Character.toUpperCase(ch)));
            lowerCaseDiffPoints.stream().forEach(ch -> bs.set(Character.toLowerCase(ch)));
        }
        if (charList.isNegated()) {
            bs.flip(0, 0x110000);
        }
        return bsToRangeList(bs);
    }

    // BitSet that holds which characters are not the same in lower case
    static private final BitSet lowerCaseDiffSet = caseDiffSetInit(false);
    // BitSet that holds which characters are not the same in upper case
    static private final BitSet upperCaseDiffSet = caseDiffSetInit(true);

    static private BitSet caseDiffSetInit(boolean upper) {
        BitSet result = new BitSet();
        for (int ch = 0; ch <= 0x16e7f; ch++) {
            int converted = upper ? Character.toUpperCase(ch) : Character.toLowerCase(ch);
            if (converted != ch) {
                result.set(ch);
            }
        }
        return result;
    }

    // Convert a list of CharacterRange's to a BitSet
    static private BitSet rangeListToBS(List<CharacterRange> ranges) {
        BitSet result = new BitSet();
        for (CharacterRange range : ranges) {
            result.set(range.getLeft(), range.getRight()+1);
        }
        return result;
    }

    //Convert a BitSet to a list of CharacterRange's
    static private List<CharacterRange> bsToRangeList(BitSet bs) {
        List<CharacterRange> result = new ArrayList<>();
        if (bs.isEmpty()) return result;
        int curPos = 0;
        while (curPos >=0) {
            int left = bs.nextSetBit(curPos);
            int right = bs.nextClearBit(left) -1;
            result.add(new CharacterRange(left, right));
            curPos = bs.nextSetBit(right+1);
        }
        return result;
    }
}
