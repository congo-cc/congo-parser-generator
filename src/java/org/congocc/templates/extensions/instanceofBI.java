package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.EvaluationException;
import java.util.function.Function;

/**
 * Implementation of ?instanceof built-in
 */
public class instanceofBI extends ExpressionEvaluatingExtension {

    @Override
    public Function<String,Boolean> get(Environment env, DotExpression caller, Object object) {
        return arg -> {
            try {
                Class<?> clazz = Class.forName(arg);
                return clazz.isInstance(object);
            } catch (Exception e) {
                throw new EvaluationException(e);
            }
        };
    }
}