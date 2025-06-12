package org.congocc.templates.builtins;

import java.util.Iterator;
import java.util.function.Function;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;

/**
 * @author Attila Szegedi
 * @version $Id: $
 */
public class SequenceContainsBuiltIn extends ExpressionEvaluatingBuiltIn {

    @Override
    public Object get(Environment env, BuiltInExpression caller,
            Object model) 
    {
        if (model instanceof Iterable<?> it) {
            return new SequenceContainsFunction(it);
        }
        throw TemplateNode.invalidTypeException(model, caller.getTarget(), env, "sequence or collection");
    }

    static class SequenceContainsFunction implements Function<Object, Boolean> {
        final Iterable<?> collection;
        SequenceContainsFunction(Iterable<?> collection) {
            this.collection = collection;
        }

        public Boolean apply(Object arg) {
            Object compareToThis = arg;
            final DefaultComparator modelComparator = new DefaultComparator(Environment.getCurrentEnvironment());
            Iterator<?> it = collection.iterator();
            while (it.hasNext()) {
                if (modelComparator.areEqual(it.next(), compareToThis)) {
                    return true;
                }
            }
            return false;
        }
    }
}
