package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.core.InvalidReferenceException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Implementation of byte, int, double, float,
 * short and long extensions
 */
public class NumericalCast extends ExpressionEvaluatingExtension {
    private static final BigDecimal BD_HALF = new BigDecimal("0.5");
    private static final MathContext MATH_CONTEXT = new MathContext(0, RoundingMode.FLOOR);

    @Override
    public Object get(Environment env, DotExpression caller, Object model)
    {
        try {
            return getNumber((Number)model, caller.get(2).toString());
        } catch (ClassCastException cce) {
            throw TemplateNode.invalidTypeException(model, caller.lhs(), "number");
        } catch (NullPointerException npe) {
            throw new InvalidReferenceException("Undefined number");
        }
    }

    private Number getNumber(Number num, String extensionName) {
        return switch(extensionName) {
            case "Floor" -> BigDecimal.valueOf(num.doubleValue()).divide(BigDecimal.ONE, 0, RoundingMode.FLOOR);
            case "Ceiling" -> BigDecimal.valueOf(num.doubleValue()).divide(BigDecimal.ONE, 0, RoundingMode.CEILING);
            case "Round" -> BigDecimal.valueOf(num.doubleValue()).add(BD_HALF, MATH_CONTEXT).divide(BigDecimal.ONE, 0, RoundingMode.FLOOR);
            default -> throw new InternalError("The only numerical cast built-ins available are Floor, Ceiling, and Round.");
        };
    }
}
