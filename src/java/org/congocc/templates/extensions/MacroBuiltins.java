package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.ExtensionExpression;
import org.congocc.templates.core.nodes.generated.Macro;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import static org.congocc.templates.core.Wrap.JAVA_NULL;

/**
 * Implementations of ?scope and ?namespace built-ins
 * that expect macro on the lhs.
 */
public abstract class MacroBuiltins extends ExpressionEvaluatingExpression {

    @Override
    public Object get(Environment env, ExtensionExpression caller,
            Object model) {
        if (!(model instanceof Macro)) {
            throw TemplateNode.invalidTypeException(model, caller.getTarget(), "macro");
        }
        return apply(env, (Macro)model);
    }

    public abstract Object apply(Environment env, Macro macro);

    public static class Namespace extends MacroBuiltins {
        @Override
        public Object apply(Environment env, Macro macro)
        {
            return env.getMacroNamespace(macro);
        }
    }

    public static class Scope extends MacroBuiltins {
        @Override
        public Object apply(Environment env, Macro macro)
        {
            Object result = env.getMacroContext(macro);
            return result == null ? JAVA_NULL : result;
        }
    }
}