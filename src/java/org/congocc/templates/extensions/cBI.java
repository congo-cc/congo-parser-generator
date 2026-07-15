package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.InvalidReferenceException;
import org.congocc.templates.core.nodes.BuiltInExpression;

/**
 * Implementation of ?c built-in
 */
public class cBI extends ExpressionEvaluatingBuiltIn {

    @Override
    public Object get(Environment env, BuiltInExpression caller, Object model)
    {
        Number num;
        try {
            num = (Number)model;
        } catch (ClassCastException e) {
            throw new EvaluationException(
                    "Expecting a number on the left side of ?c");
        } catch (NullPointerException e) {
            throw new InvalidReferenceException("Undefined number");
        }
        if (num instanceof Integer) {
            // We accelerate this fairly common case
            return num.toString();
        } else {
            return (env == null ? Environment.getNewCNumberFormat() : env.getCNumberFormat()).format(num);
        }
    }
}