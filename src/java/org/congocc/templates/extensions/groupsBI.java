package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.extensions.StringFunctions.RegexMatchModel;
import static org.congocc.templates.core.Wrap.unwrap;

public class groupsBI extends ExpressionEvaluatingExtension
{
    @Override
    public Object get(Environment env, DotExpression caller, Object model) {
        model = unwrap(model);
        if (model instanceof RegexMatchModel rmm) {
            return rmm.getGroups();
        }
        if (model instanceof RegexMatchModel.Match match) {
            return match.subs;
        }
        else {
            throw TemplateNode.invalidTypeException(model, caller.lhs(), "regular expression matcher");
        }
    }
}
