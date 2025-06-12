package org.congocc.templates.builtins;

import java.util.Map;
//import java.util.function.Supplier;
import java.util.function.Function;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.Expression;
import org.congocc.templates.core.nodes.generated.ParentheticalExpression;
import org.congocc.templates.core.variables.VarArgsFunction;
import org.congocc.templates.core.variables.InvalidReferenceException;
import org.congocc.templates.TemplateHash;

import static org.congocc.templates.core.variables.Wrap.*;

public abstract class ExistenceBuiltIn implements BuiltIn {
    public Object get(Environment env, BuiltInExpression caller) 
    {
        final Expression target = caller.getTarget();
        try {
            return apply(target.evaluate(env));
        }
        catch(InvalidReferenceException e) {
            if(!(target instanceof ParentheticalExpression)) {
                throw e;
            }
            return apply(null);
        }
    }

    public abstract Object apply(Object obj);

    public static final class DefaultBuiltIn extends ExistenceBuiltIn {
        public Object apply(final Object value) {
            if(value == null || value == JAVA_NULL) {
                return FirstDefined.INSTANCE;
            }
            return (Function<Object,Object>) arg->value;
        }
    };

    public static class IfExistsBuiltIn extends ExistenceBuiltIn {
        public Object apply(final Object model) {
            return model == null || model == JAVA_NULL ? LOOSE_NULL : model;
        }
    };

    public static class ExistsBuiltIn extends ExistenceBuiltIn {
        public Object apply(final Object model) {
            return model != null && model != JAVA_NULL;
        }
    };
        
    public static class HasContentBuiltIn extends ExistenceBuiltIn {
        public Object apply(final Object value) {
            if (value == null || value == JAVA_NULL || value == LOOSE_NULL) return false;
            if (isIterable(value)) {
                return asIterator(value).hasNext();
            }
            if (value instanceof Map m) {
                return !m.isEmpty();
            }
            if (value instanceof TemplateHash th) {
                return !th.isEmpty();
            }
            return value.toString().length() > 0;
        }
    };

    public static class IsDefinedBuiltIn extends ExistenceBuiltIn {
        public Object apply(final Object model) {
            return model != null;
        }
    };

    private static class FirstDefined implements VarArgsFunction<Object> {
        static final FirstDefined INSTANCE = new FirstDefined();
        public Object apply(Object... args) {
            for (Object arg : args) {
                if (arg != null && arg != JAVA_NULL) {
                    return arg;
                }
            }
            return null;
        }
    };
}