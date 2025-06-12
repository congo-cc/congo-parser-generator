package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.builtins.StringFunctions.RegexMatchModel;
import static org.congocc.templates.core.variables.Wrap.unwrap;

public class groupsBI extends ExpressionEvaluatingBuiltIn
{
    @Override
    public Object get(Environment env, BuiltInExpression caller,
            Object model) {
        model = unwrap(model);
        if (model instanceof RegexMatchModel rmm) {
            return rmm.getGroups();
        }
        if (model instanceof RegexMatchModel.Match match) {
            return match.subs;
        }
        else {
            throw TemplateNode.invalidTypeException(model, caller.getTarget(), env, "regular expression matcher");
        }
    }
}
