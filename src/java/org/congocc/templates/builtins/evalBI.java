package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.Expression;
import org.congocc.templates.core.parser.CTLLexer;
import org.congocc.templates.core.parser.CTLParser;

public class evalBI extends ExpressionEvaluatingBuiltIn {

    @Override
    public Object get(Environment env, BuiltInExpression caller, Object model) {
        String input = "(" + model + ")";
        CTLLexer token_source= new CTLLexer("input", input, CTLLexer.LexicalState.EXPRESSION, caller.getBeginLine(), caller.getBeginColumn());;
        CTLParser parser = new CTLParser(token_source);
        parser.setTemplate(caller.getTemplate());
        Expression exp = parser.Expression();
        return exp.evaluate(env);
    }
}