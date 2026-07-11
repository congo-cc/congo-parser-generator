package org.congocc.templates.core.nodes;

import static org.congocc.templates.core.variables.Wrap.JAVA_NULL;
import static org.congocc.templates.core.variables.Wrap.LOOSE_NULL;
import static org.congocc.templates.core.variables.Wrap.unwrap;
import static org.congocc.templates.core.variables.Wrap.wrap;
import org.congocc.templates.core.variables.*;
import org.congocc.templates.core.Environment;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.List;
import java.util.ArrayList;
import org.congocc.templates.core.parser.*;
import org.congocc.templates.core.nodes.generated.*;
import java.util.*;

public class MethodCall extends TemplateNode implements Expression {

    public ArgsList getArgs() {
        return firstChildOfType(ArgsList.class);
    }

    public Expression getTarget() {
        return (Expression) get(0);
    }

    @SuppressWarnings( {
        "unchecked", "rawtypes"
    }
    )
    public Object evaluate(Environment env) {
        Object value = getTarget().evaluate(env);
        if (value == LOOSE_NULL) return JAVA_NULL;
        if (value instanceof VarArgsFunction<?> targetMethod) {
            ArgsList args = getArgs();
            List<Object> argumentList;
            if (args != null) {
                argumentList = args.getParameterSequence(targetMethod, env);
            } else {
                argumentList = new ArrayList<>();
            }
            Object result = targetMethod.apply(argumentList.toArray(new Object[argumentList.size()]));
            return wrap(result);
        } else if (value instanceof Supplier<?> supplier) {
            ArgsList argsList = getArgs();
            if (argsList != null && argsList.firstChildOfType(Expression.class) != null) {
                throw new EvaluationException("The method " + getTarget() + " takes no arguments.");
            }
            return wrap(supplier.get());
        } else if (value instanceof Function func) {
            ArgsList args = getArgs();
            if (args == null || args.childrenOfType(Expression.class).size() != 1) {
                throw new EvaluationException("The method " + getTarget() + " takes exactly one argument.");
            }
            Expression argExp = (Expression) getArgs().get(0);
            Object arg = unwrap(argExp.evaluate(env));
            return wrap(func.apply(arg));
        } else if (value instanceof BiFunction bif) {
            ArgsList args = getArgs();
            List<Expression> argExpressions = args == null ? new ArrayList<>() : args.childrenOfType(Expression.class);
            if (argExpressions.size() != 2) {
                throw new EvaluationException("The method " + getTarget() + " takes exactly two arguments.");
            }
            Object firstArg = argExpressions.get(0).evaluate(env);
            Object secondArg = argExpressions.get(1).evaluate(env);
            return wrap(bif.apply(firstArg, secondArg));
        } else if (value instanceof TriFunction trif) {
            ArgsList args = getArgs();
            List<Expression> argExpressions = args == null ? new ArrayList<>() : args.childrenOfType(Expression.class);
            if (argExpressions.size() != 3) {
                throw new EvaluationException("The method " + getTarget() + " takes exactly three arguments.");
            }
            Object firstArg = argExpressions.get(0).evaluate(env);
            Object secondArg = argExpressions.get(1).evaluate(env);
            Object thirdArg = argExpressions.get(2).evaluate(env);
            return wrap(trif.apply(firstArg, secondArg, thirdArg));
        } else if (value instanceof QuadFunction quadf) {
            ArgsList args = getArgs();
            List<Expression> argExpressions = args == null ? new ArrayList<>() : args.childrenOfType(Expression.class);
            if (argExpressions.size() != 4) {
                throw new EvaluationException("The method " + getTarget() + " takes exactly four arguments.");
            }
            Object firstArg = argExpressions.get(0).evaluate(env);
            Object secondArg = argExpressions.get(1).evaluate(env);
            Object thirdArg = argExpressions.get(2).evaluate(env);
            Object fourthArg = argExpressions.get(3).evaluate(env);
            return wrap(quadf.apply(firstArg, secondArg, thirdArg, fourthArg));
        } else if (value instanceof Macro func) {
            env.setLastReturnValue(null);
            StringBuilder prevBuffer = env.getBuffer();
            StringBuilder newBuffer = new StringBuilder();
            try {
                env.setBuffer(newBuffer);
                env.render(func, getArgs(), null, null);
            } finally {
                env.setBuffer(prevBuffer);
            }
            return func.isFunction() ? env.getLastReturnValue() : newBuffer.toString();
        }
        throw invalidTypeException(value, getTarget(), "method");
    }
}


