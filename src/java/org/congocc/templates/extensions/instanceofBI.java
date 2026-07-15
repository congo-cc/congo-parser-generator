package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.ExtensionExpression;
import org.congocc.templates.core.EvaluationException;
import java.util.function.Function;

/**
 * Implementation of ?instanceof built-in
 */
public class instanceofBI extends ExpressionEvaluatingExpression {

    @Override
    public Function<String,Boolean> get(Environment env, ExtensionExpression caller, Object object) {
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