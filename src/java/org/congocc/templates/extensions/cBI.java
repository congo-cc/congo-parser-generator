package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.nodes.generated.DotExpression;

/**
 * Implementation of ?c built-in
 */
public class cBI extends ExpressionEvaluatingExtension {

    @Override
    public Object get(Environment env, DotExpression caller, Object arg)
    {
        if (arg instanceof Number num) {
            if (num instanceof Integer) {
                // We accelerate this fairly common case
                return num.toString();
            } else {
                return (env == null ? Environment.getNewCNumberFormat() : env.getCNumberFormat()).format(num);
            }
        }
        else {
            throw new EvaluationException("Expecting a number on the left side of ?c");
        }
    }
}