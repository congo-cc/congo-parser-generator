package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.TemplateException;

import static org.congocc.templates.core.variables.Wrap.*;

public class numberBI extends ExpressionEvaluatingBuiltIn
{
    @Override
    public Object get(Environment env, BuiltInExpression caller, Object value) {
        if(value instanceof Number) {
            return value;
        }
        final String string;
        try {
            string = asString(value);
        }
        catch(ClassCastException ex) {
            throw TemplateNode.invalidTypeException(value, caller.getTarget(), env, "string or number");
        }
        ArithmeticEngine e = env == null ? caller.getTemplate().getArithmeticEngine() : env.getArithmeticEngine();
        try {
            //return wrap(e.toNumber(string));
            return e.toNumber(string);
        } catch(NumberFormatException nfe) {
                String mess = "Error: " + caller.getLocation()
                + "\nExpecting a number in string here, found: " + string;
                throw new TemplateException(mess, env);
            }
    }
}