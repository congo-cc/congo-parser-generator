package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;

public abstract class ExpressionEvaluatingExtension implements Extension {

    @Override
    public Object get(DotExpression caller, Environment env)
    {
        return get(env, caller, caller.lhs().evaluate(env));
    }

    public abstract Object get(Environment env, DotExpression caller, Object lhs);
}
