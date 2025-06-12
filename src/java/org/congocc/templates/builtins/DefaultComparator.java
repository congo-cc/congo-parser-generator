package org.congocc.templates.builtins;

import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.Environment;

import static org.congocc.templates.core.variables.Wrap.unwrap;
import static org.congocc.templates.core.variables.Wrap.JAVA_NULL;
import static org.congocc.templates.core.variables.Wrap.LOOSE_NULL;;

public class DefaultComparator
{
    private final ArithmeticEngine arithmeticEngine;
    
    public DefaultComparator(Environment env) {
        arithmeticEngine = env.getArithmeticEngine();
    }
    
    public boolean areEqual(Object left, Object right)
    {
        if (left == JAVA_NULL || left == LOOSE_NULL) {
            return right == JAVA_NULL || right == LOOSE_NULL;
        }
        left = unwrap(left);
        right = unwrap(right);
        if(left instanceof Number n1 && right instanceof Number n2) {
            return arithmeticEngine.compareNumbers(n1, n2) == 0;
        }
        return left.equals(right);
    }
}