package org.congocc.templates.builtins;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.core.variables.*;
import org.congocc.templates.TemplateHash;
import org.congocc.templates.TemplateSequence;
import org.congocc.templates.utility.StringUtil;

import static org.congocc.templates.core.variables.Wrap.*;

/**
 * Implementations of builtins for standard functions that operate on sequences
 */
public abstract class SequenceFunctions extends ExpressionEvaluatingBuiltIn {

    static final int KEY_TYPE_STRING = 1;
    static final int KEY_TYPE_NUMBER = 2;


    @Override
    public Object get(Environment env, BuiltInExpression caller,
            Object model) 
    {
        if (!isList(model)) {
            throw TemplateNode.invalidTypeException(model,
                    caller.getTarget(), env, "sequence");
        }
        return apply(model);
    }
    
    public abstract Object apply(Object model);
    
    public static class First extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List list = asList(sequence);
            return list.size() > 0 ? list.get(0) : null;
        }
    }

    public static class Last extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List list = asList(sequence);
            return list.size() > 0 ? list.get(list.size() - 1) : null;
        }
    }

    public static class Reverse extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List list = asList(sequence);
            list = new ArrayList(list);
            Collections.reverse(list);
            return list;
        }
    }

    public static class Sort extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List list = asList(sequence);
            return sort(asList(sequence), null);
        }
    }

    public static class SortBy extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            return new SortByMethod(asList(sequence));
        }
    }

    public static class Chunk extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            return new ChunkFunction(asList(sequence));
        }
    }

    public static class IndexOf extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            return new SequenceIndexOf(asList(sequence), false);
        }
    }

    public static class LastIndexOf extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List list = asList(sequence);
            return new SequenceIndexOf(list, true);
        }
    }

    static class ChunkFunction implements VarArgsFunction {

        private final List tsm;

        private ChunkFunction(List tsm) {
            this.tsm = tsm;
        }
        
        public Object apply(Object... args) {
            int numArgs = args.length;
            if (numArgs != 1 && numArgs != 2) {
                throw new EvaluationException(
                "?chunk(...) expects 1 or 2 arguments.");
            }
            Object chunkSize = args[0];
            if (!(chunkSize instanceof Number)) {
                throw new EvaluationException(
                        "?chunk(...) expects a number as "
                        + "its 1st argument.");
            }
            return new ChunkedSequence(tsm, ((Number)chunkSize).intValue(),
                    numArgs > 1 ? args[1] : null);
        }
    }

    static class ChunkedSequence implements TemplateSequence {

        private final List wrappedTsm;

        private final int chunkSize;

        private final Object fillerItem;

        private final int numberOfChunks;

        private ChunkedSequence(List wrappedTsm,
                int chunkSize, Object fillerItem)
        {
            if (chunkSize < 1) {
                throw new EvaluationException(
                "The 1st argument to ?chunk(...) must be at least 1.");
            }
            this.wrappedTsm = wrappedTsm;
            this.chunkSize = chunkSize;
            this.fillerItem = fillerItem;
            numberOfChunks = (wrappedTsm.size() + chunkSize - 1) / chunkSize;
        }

        public Object get(final int chunkIndex)
        {
            if (chunkIndex >= numberOfChunks) {
                return null;
            }

            return new TemplateSequence() {

                private final int baseIndex = chunkIndex * chunkSize;

                public Object get(int relIndex)
                {
                    int absIndex = baseIndex + relIndex;
                    if (absIndex < wrappedTsm.size()) {
                        return wrappedTsm.get(absIndex);
                    } else {
                        return absIndex < numberOfChunks * chunkSize ? fillerItem
                                : null;
                    }
                }

                public int size() {
                    return fillerItem != null
                    || chunkIndex + 1 < numberOfChunks ? chunkSize
                            : wrappedTsm.size() - baseIndex;
                }

            };
        }

        public int size() {
            return numberOfChunks;
        }

    }

    static Object sort(List seq, String[] keys)
    {
        int i;
        int keyCnt;

        int ln = seq.size();
        if (ln == 0) {
            return seq;
        }

        List<Object> result = new ArrayList<Object>(ln);
        Object item;
        item = seq.get(0);
        if (keys != null) {
            keyCnt = keys.length;
            if (keyCnt == 0) {
                keys = null;
            } else {
                for (i = 0; i < keyCnt; i++) {
                    if (!(item instanceof TemplateHash)) {
                        throw new EvaluationException(
                                "sorting failed: "
                                + (i == 0 ? "You can't use ?sort_by when the "
                                        + "sequence items are not hashes."
                                        : "The subvariable "
                                            + StringUtil
                                            .jQuote(keys[i - 1])
                                            + " is not a hash, so ?sort_by "
                                            + "can't proceed by getting the "
                                            + StringUtil
                                            .jQuote(keys[i])
                                            + " subvariable."));
                    }

                    item = ((TemplateHash) item).get(keys[i]);
                    if (item == null) {
                        throw new EvaluationException(
                                "sorting failed: "
                                + "The "
                                + StringUtil.jQuote(keys[i])
                                + " subvariable "
                                + (keyCnt == 1 ? "was not found."
                                        : "(specified by ?sort_by argument number "
                                            + (i + 1)
                                            + ") was not found."));
                    }
                }
            }
        } else {
            keyCnt = 0;
        }

        int keyType;
        if (item instanceof Number) {
            keyType = KEY_TYPE_NUMBER;
        } else {
            keyType = KEY_TYPE_STRING;
        } 

        if (keys == null) {
            if (keyType == KEY_TYPE_STRING) {
                for (i = 0; i < ln; i++) {
                    item = seq.get(i);
                    try {
                        result.add(new KVP(asString(item), item));
                    } catch (ClassCastException e) {
                            throw new EvaluationException(
                                    "Failure of ?sort built-in: "
                                    + "All values in the sequence must be "
                                    + "strings, because the first value "
                                    + "was a string. "
                                    + "The value at index " + i
                                    + " is not string.");
                    }
                }
            } else if (keyType == KEY_TYPE_NUMBER) {
                for (i = 0; i < ln; i++) {
                    item = seq.get(i);
                    try {
                        result.add(new KVP((Number)item, item));
                    } catch (ClassCastException e) {
                        throw new EvaluationException(
                                "sorting failed: " 
                                + "All values in the sequence must be "
                                + "numbers, because the first value "
                                + "was a number. "
                                + "The value at index " + i
                                + " is not number.");
                    }
                }
            } else {
                throw new RuntimeException("internal error: Bad key type");
            }
        } else {
            for (i = 0; i < ln; i++) {
                item = seq.get(i);
                Object key = item;
                for (int j = 0; j < keyCnt; j++) {
                    try {
                        key = ((TemplateHash) key).get(keys[j]);
                    } catch (ClassCastException e) {
                        if (!(key instanceof TemplateHash)) {
                            throw new EvaluationException(
                                    "sorting failed: " 
                                    + "Problem with the sequence item at index "
                                    + i
                                    + ": "
                                    + "Can't get the "
                                    + StringUtil.jQuote(keys[j])
                                    + " subvariable, because the value is not a hash.");
                        } else {
                            throw e;
                        }
                    }
                    if (key == null) {
                        throw new EvaluationException(
                                "sorting failed "  
                                + "Problem with the sequence item at index "
                                + i + ": " + "The "
                                + StringUtil.jQuote(keys[j])
                                + " subvariable was not found.");
                    }
                }
                if (keyType == KEY_TYPE_STRING) {
                    try {
                        result.add(new KVP(asString(key), item));
                    } catch (ClassCastException e) {
                            throw new EvaluationException(
                                    "sorting failed: " 
                                    + "All key values in the sequence must be "
                                    + "date/time values, because the first key "
                                    + "value was a date/time. The key value at "
                                    + "index " + i
                                    + " is not a date/time.");
                    }
                } else if (keyType == KEY_TYPE_NUMBER) {
                    try {
                        result.add(new KVP((Number)key, item));
                    } catch (ClassCastException e) {
                            throw new EvaluationException(
                                    "sorting failed: "
                                    + "All key values in the sequence must be "
                                    + "numbers, because the first key "
                                    + "value was a number. The key value at "
                                    + "index " + i
                                    + " is not a number.");
                    }
                } else {
                    throw new RuntimeException("internal error: Bad key type");
                }
            }
        }

        Comparator cmprtr;
        if (keyType == KEY_TYPE_STRING) {
            cmprtr = new LexicalKVPComparator(Environment
                    .getCurrentEnvironment().getCollator());
        } else if (keyType == KEY_TYPE_NUMBER) {
            cmprtr = new NumericalKVPComparator(Environment
                    .getCurrentEnvironment().getArithmeticEngine());
        } else {
            throw new RuntimeException("internal error: Bad key type");
        }
        Collections.sort(result, cmprtr);

        for (i = 0; i < ln; i++) {
            result.set(i, ((KVP) result.get(i)).value);
        }
        return result;
    }

    static class KVP {
        private KVP(Object key, Object value) {
            this.key = key;
            this.value = value;
        }

        private Object key;
        private Object value;
    }

    static class NumericalKVPComparator implements Comparator {
        private ArithmeticEngine ae;

        private NumericalKVPComparator(ArithmeticEngine ae) {
            this.ae = ae;
        }

        public int compare(Object arg0, Object arg1) {
            return ae.compareNumbers(
               (Number) ((KVP) arg0).key,
               (Number) ((KVP) arg1).key);
        }
    }

    static class LexicalKVPComparator implements Comparator {
        private Collator collator;

        LexicalKVPComparator(Collator collator) {
            this.collator = collator;
        }

        public int compare(Object arg0, Object arg1) {
            return collator.compare(
                    ((KVP) arg0).key, ((KVP) arg1).key);
        }
    }

    static class SortByMethod implements VarArgsFunction {
        List seq;

        SortByMethod(List seq) {
            this.seq = seq;
        }

        public Object apply(Object... params)
        {
            if (params.length == 0) {
                throw new EvaluationException(
                        "?sort_by(key) needs exactly 1 argument.");
            }
            String[] subvars;
            Object obj = params[0];
            if ((obj instanceof CharSequence)) {
                subvars = new String[]{obj.toString()};
            } else if (obj instanceof TemplateSequence seq) {
                int ln = seq.size();
                subvars = new String[ln];
                for (int i = 0; i < ln; i++) {
                    Object item = seq.get(i);
                    try {
                        subvars[i] = asString(item);
                    } catch (ClassCastException e) {
                        if (!(item instanceof CharSequence)) {
                            throw new EvaluationException(
                                    "The argument to ?sort_by(key), when it "
                                    + "is a sequence, must be a sequence of "
                                    + "strings, but the item at index " + i
                                    + " is not a string." );
                        }
                    }
                }
            } else {
                throw new EvaluationException(
                        "The argument to ?sort_by(key) must be a string "
                        + "(the name of the subvariable), or a sequence of "
                        + "strings (the \"path\" to the subvariable).");
            }
            return sort(seq, subvars); 
        }
    }

    static class SequenceIndexOf implements VarArgsFunction<Integer> {

        private final List sequence;
        private final boolean reverse;

        SequenceIndexOf(List sequence, boolean reverse) {
            this.sequence = sequence;
            this.reverse = reverse;
        }

        public Integer apply(Object... args) {
            final int argc = args.length;
            int startIndex;
            if (argc != 1 && argc != 2) {
                throw new EvaluationException("Expecting one or two arguments for ?seq_" + (reverse ? "last_" : "") + "index_of");
            }
            Object compareToThis = args[0];
            if (argc == 2) {
                try {
                    startIndex = ((Number)args[1]).intValue();
                } catch (ClassCastException cce) {
                    throw new EvaluationException("Expecting number as second argument to ?seq_" + (reverse ? "last_" : "") + "index_of");
                }
            }
            else {
                startIndex = reverse ? sequence.size() - 1 : 0;
            }
            if (startIndex>=sequence.size()) startIndex = sequence.size()-1;
            if (startIndex<0) startIndex = 0;
            final Environment env = Environment.getCurrentEnvironment();
            final DefaultComparator comparator = new DefaultComparator(env);
            if (reverse) {
                for (int i = startIndex; i > -1; --i) {
                    if (comparator.areEqual(sequence.get(i), compareToThis)) {
                        return i; 
                    }
                }
            }
            else {
                final int s = sequence.size();
                for (int i = startIndex; i < s; ++i) {
                    if (comparator.areEqual(sequence.get(i), compareToThis)) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

}
