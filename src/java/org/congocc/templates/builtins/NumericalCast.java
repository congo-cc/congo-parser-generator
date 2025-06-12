package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.core.variables.InvalidReferenceException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Implementation of ?byte, ?int, ?double, ?float,
 * ?short and ?long built-ins 
 */
public class NumericalCast extends ExpressionEvaluatingBuiltIn {
    private static final BigDecimal half = new BigDecimal("0.5");
    private static final MathContext mc = new MathContext(0, RoundingMode.FLOOR);

    @Override
    public Object get(Environment env, BuiltInExpression caller, Object model) 
    {
        try {
            return getNumber((Number)model, caller.getName());
        } catch (ClassCastException cce) {
            throw TemplateNode.invalidTypeException(model, caller.getTarget(), env, "number");
        } catch (NullPointerException npe) {
            throw new InvalidReferenceException("Undefined number", env);
        }
    }

    private Number getNumber(Number num, String builtInName) {
        return switch(builtInName) {
            case "int" -> num.intValue();
            case "double" -> num.doubleValue();
            case "long" -> num.longValue();
            case "float" -> num.floatValue();
            case "byte" -> num.byteValue();
            case "short" -> num.shortValue();
            case "floor" -> BigDecimal.valueOf(num.doubleValue()).divide(BigDecimal.ONE, 0, RoundingMode.FLOOR);
            case "ceiling" -> BigDecimal.valueOf(num.doubleValue()).divide(BigDecimal.ONE, 0, RoundingMode.CEILING);
            case "round" -> BigDecimal.valueOf(num.doubleValue()).add(half, mc).divide(BigDecimal.ONE, 0, RoundingMode.FLOOR);
            default -> throw new InternalError("The only numerical cast built-ins available are ?int, ?long, ?short, ?byte, ?float, ?double, ?floor, ?ceiling, and ?round.");
        };
    }
}
