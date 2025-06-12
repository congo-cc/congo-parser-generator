package org.congocc.templates.builtins;

import java.util.function.Function;

import org.congocc.templates.core.variables.scope.Scope;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
//import org.congocc.templates.core.variables.VarArgsFunction;
import org.congocc.templates.TemplateException;

/**
 * Implementation of ?resolve built-in 
 */

public class resolveBI extends ExpressionEvaluatingBuiltIn {

    @Override
    public Function<String,Object> get(Environment env, BuiltInExpression caller, Object lhs) 
    {
        if (!(lhs instanceof Scope)) {
            throw new TemplateException("Expecting scope on left of ?resolve built-in", env);
        }
        Scope scope = (Scope) lhs;
        return arg->scope.resolveVariable(arg);
    }
}
