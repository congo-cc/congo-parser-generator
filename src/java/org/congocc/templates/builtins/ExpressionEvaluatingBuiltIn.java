package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;

public abstract class ExpressionEvaluatingBuiltIn implements BuiltIn {

    @Override
    public Object get(Environment env, BuiltInExpression caller) 
    {
        return get(env, caller, caller.getTarget().evaluate(env));
    }
    
    public abstract Object get(Environment env, BuiltInExpression caller, Object lhs);
}
