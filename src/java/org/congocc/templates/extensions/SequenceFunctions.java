package org.congocc.templates.extensions;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.nodes.ExtensionExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.reflection.JavaMethodCall;
import org.congocc.templates.core.reflection.VarArgsFunction;
import org.congocc.templates.core.TemplateHash;

import static org.congocc.templates.core.Wrap.*;

/**
 * Implementations of builtins for standard functions that operate on sequences
 */
public abstract class SequenceFunctions extends ExpressionEvaluatingExtension {

    static final int KEY_TYPE_STRING = 1;
    static final int KEY_TYPE_NUMBER = 2;


    @Override
    public Object get(Environment env, ExtensionExpression caller, Object model)
    {
        if (!isList(model)) {
            throw TemplateNode.invalidTypeException(model,
                    caller.lhs(), "sequence");
        }
        return apply(model);
    }

    public abstract Object apply(Object model);

    public static class First extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List<?> list = asList(sequence);
            return list.size() > 0 ? list.get(0) : null;
        }
    }

    public static class Last extends SequenceFunctions {
        @Override
        public Object apply(Object sequence) {
            List<?> list = asList(sequence);
            return list.isEmpty() ? null : list.get(list.size() - 1);
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
}
