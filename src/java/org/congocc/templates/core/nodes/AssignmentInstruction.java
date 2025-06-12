package org.congocc.templates.core.nodes;

import java.io.IOException;
import java.util.*;
import java.lang.reflect.Array;
import org.congocc.templates.TemplateException;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.variables.EvaluationException;
import org.congocc.templates.core.variables.InvalidReferenceException;
import org.congocc.templates.core.nodes.generated.Expression;
import org.congocc.templates.core.nodes.generated.ParentheticalExpression;
import org.congocc.templates.core.nodes.generated.StringLiteral;
import org.congocc.templates.core.nodes.generated.TemplateElement;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.core.parser.Node;
import org.congocc.templates.core.parser.Token;
import org.congocc.templates.core.variables.ReflectionCode;
import static org.congocc.templates.core.parser.Token.TokenType.*;

@SuppressWarnings("unchecked")
public class AssignmentInstruction extends TemplateNode implements TemplateElement {
    public List<String> getVarNames() {
        List<String> result = new ArrayList<>();
        List<Node> equalsToks = childrenOfType(EQUALS);
        for (Node tok : equalsToks) {
            Node varExp = tok.previousSibling();
            if (varExp instanceof StringLiteral sl) {
                result.add(sl.getAsString());
            }
            else if (varExp.getType() == ID) {
                result.add(varExp.toString());
            }
        }
        return result;
    }

    public List<Expression> getTargetExpressions() {
        return childrenOfType(Expression.class, exp->exp.nextSibling().getType() == EQUALS);
    }

    public Expression getNamespaceExp() {
        Node inToken = firstChildOfType(IN);
        if (inToken != null) {
            return (Expression) inToken.nextSibling();
        }
        return null;
    }

    public void execute(Environment env) throws IOException {
    	Map<String,Object> scope = null;
        Expression namespaceExp = getNamespaceExp();
    	if (namespaceExp != null) {
    		try {
    			scope = (Map<String,Object>) namespaceExp.evaluate(env); 
    		} catch (ClassCastException cce) {
                throw new InvalidReferenceException(getLocation() + "\nInvalid reference to namespace: " + namespaceExp, env);
    		}
    	}
        for (Expression exp : childrenOfType(Expression.class)) {
            if (exp.nextSibling().getType() != EQUALS) continue;
            Expression valueExp = (Expression) exp.nextSibling().nextSibling();
            Object value = valueExp.evaluate(env);
            set(exp, value, env, scope);
        }
    }

    public static void set(Expression lhs, Object value, Environment env, Map scope) {
        while (lhs instanceof ParentheticalExpression pe) {
            lhs = pe.getNested();
        }
        if (lhs instanceof Token) {
            String varName = lhs.toString();
            if (lhs instanceof StringLiteral sl) {
                varName =sl.getAsString();
            }
            if (scope != null) {
                scope.put(varName, value);
            }
            else {
                env.unqualifiedSet(varName, value);
            }
            return;
        }
        Expression targetExp = (Expression) lhs.get(0);
        Expression keyExp = (Expression) lhs.get(2);
        Object target = targetExp.evaluate(env);
        Object key = lhs instanceof DynamicKeyName ? keyExp.evaluate(env) : keyExp.toString();
        if (key instanceof Number nkey && (target instanceof List || target.getClass().isArray())) {
            int index = nkey.intValue();
            if (target instanceof List l) {
                l.set(index, value);
            } else try {
                Array.set(target, index, value);
            } catch (Exception e) {
                throw new EvaluationException(e);
            }
            return;
        }
        if (target instanceof Map m) {
            m.put(key, value);
            return;
        }
        if (key instanceof String && ReflectionCode.setProperty(target, (String) key, value)) {
            return;
        }
        // TODO: check for the beans setter setXXX method
        // TODO: improve error message a bit
        throw new EvaluationException("Could not set " + lhs);
    }

    public String getDescription() {
    	return "assignment instruction";
    }
}
