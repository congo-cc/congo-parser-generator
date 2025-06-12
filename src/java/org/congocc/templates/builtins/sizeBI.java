package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.TemplateHash;
import org.congocc.templates.TemplateSequence;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/**
 * Implementation of ?c built-in 
 */
public class sizeBI extends ExpressionEvaluatingBuiltIn {

    @Override
    public Object get(Environment env, BuiltInExpression caller, Object value) {
        if (value instanceof Collection c) {
            return c.size();
        }
        else if (value instanceof Map m) {
            return m.size();
        }
        else if (value instanceof TemplateSequence ts) {
            return ts.size();
        }
        else if (value instanceof TemplateHash th) {
            return th.size();
        }
        else if (value.getClass().isArray()) {
            return Array.getLength(value);
        }
        else {
            throw TemplateNode.invalidTypeException(value, caller.getTarget(), env, "a sequence or extended hash");
        }
    }
}