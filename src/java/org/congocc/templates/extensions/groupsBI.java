package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.ExtensionExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.extensions.StringFunctions.RegexMatchModel;
import static org.congocc.templates.core.Wrap.unwrap;

public class groupsBI extends ExpressionEvaluatingExpression
{
    @Override
    public Object get(Environment env, ExtensionExpression caller, Object model) {
        model = unwrap(model);
        if (model instanceof RegexMatchModel rmm) {
            return rmm.getGroups();
        }
        if (model instanceof RegexMatchModel.Match match) {
            return match.subs;
        }
        else {
            throw TemplateNode.invalidTypeException(model, caller.getTarget(), "regular expression matcher");
        }
    }
}
