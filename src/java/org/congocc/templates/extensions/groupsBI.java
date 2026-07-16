package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.extensions.StringFunctions.RegexMatchModel;

public class groupsBI extends ExpressionEvaluatingExtension
{
    @Override
    public Object get(Environment env, DotExpression caller, Object obj) {
        if (obj instanceof RegexMatchModel rmm) {
            return rmm.getGroups();
        }
        if (obj instanceof RegexMatchModel.Match match) {
            return match.subs;
        }
        else {
            throw TemplateNode.invalidTypeException(obj, caller.lhs(), "regular expression matcher");
        }
    }
}
