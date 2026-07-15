package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.nodes.ExtensionExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.TemplateException;

import static org.congocc.templates.core.Wrap.*;

public class numberBI extends ExpressionEvaluatingExtension
{
    @Override
    public Object get(Environment env, ExtensionExpression caller, Object value) {
        if(value instanceof Number) {
            return value;
        }
        final String string;
        try {
            string = asString(value);
        }
        catch(ClassCastException ex) {
            throw TemplateNode.invalidTypeException(value, caller.getTarget(), "string or number");
        }
        ArithmeticEngine e = env == null ? caller.getTemplate().getArithmeticEngine() : env.getArithmeticEngine();
        try {
            //return wrap(e.toNumber(string));
            return e.toNumber(string);
        } catch(NumberFormatException nfe) {
                String mess = "Error: " + caller.getLocation()
                + "\nExpecting a number in string here, found: " + string;
                throw new TemplateException(mess);
            }
    }
}