package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.ExtensionExpression;

public abstract class ExpressionEvaluatingExtension implements Extension {

    @Override
    public Object get(ExtensionExpression caller, Environment env)
    {
        return get(env, caller, caller.lhs().evaluate(env));
    }

    public abstract Object get(Environment env, ExtensionExpression caller, Object lhs);
}
