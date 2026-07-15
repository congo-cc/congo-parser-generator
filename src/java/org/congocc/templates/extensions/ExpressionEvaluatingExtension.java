package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.ExtensionExpression;

public abstract class ExpressionEvaluatingExtension implements Extension {

    @Override
    public Object get(Environment env, ExtensionExtension caller)
    {
        return get(env, caller, caller.getTarget().evaluate(env));
    }

    public abstract Object get(Environment env, Extensiontension caller, Object lhs);
}
