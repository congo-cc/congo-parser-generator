package org.congocc.templates.core;

import java.math.*;
import java.util.HashMap;
import java.util.Map;

import org.congocc.templates.*;

public abstract class ArithmeticEngine {

    /**
     * Arithmetic engine that converts all numbers to {@link BigDecimal} and
     * then operates on them. This is the default arithmetic engine.
     */
    public static final BigDecimalEngine BIGDECIMAL_ENGINE = new BigDecimalEngine();
    /**
     * Arithmetic engine that uses (more-or-less) the widening conversions of
     * Java language to determine the type of result of operation, instead of
     * converting everything to BigDecimal up front.
     */
    public static final ConservativeEngine CONSERVATIVE_ENGINE = new ConservativeEngine();

    public abstract int compareNumbers(Number first, Number second);
    public abstract Number add(Number first, Number second);
    public abstract Number subtract(Number first, Number second);
    public abstract Number multiply(Number first, Number second);
    public abstract Number divide(Number first, Number second);
    public abstract Number modulus(Number first, Number second);
    public abstract Number toNumber(String s);

    protected int minScale = 12;
    protected int maxScale = 12;
    protected RoundingMode roundingMode = RoundingMode.HALF_UP;

    /**
     * Sets the minimal scale to use when dividing BigDecimal numbers. Default
     * value is 12.
     */
    public void setMinScale(int minScale) {
        if(minScale < 0) {
            throw new IllegalArgumentException("minScale < 0");
        }
        this.minScale = minScale;
    }
    
    /**
     * Sets the maximal scale to use when multiplying BigDecimal numbers. 
     * Default value is 100.
     */
    public void setMaxScale(int maxScale) {
        if(maxScale < minScale) {
            throw new IllegalArgumentException("maxScale < minScale");
        }
        this.maxScale = maxScale;
    }

    public void setRoundingPolicy(RoundingMode roundingMode) {
        this.roundingMode  = roundingMode;
    }

    /**
     * This is the default arithmetic engine. It converts every
     * number it receives into {@link BigDecimal}, then operates on these
     * converted {@link BigDecimal}s.
     */
    public static class BigDecimalEngine extends ArithmeticEngine {

        public int compareNumbers(Number first, Number second) {
            BigDecimal left = toBigDecimal(first);
            BigDecimal right = toBigDecimal(second);
            return left.compareTo(right);
        }
    
        public Number add(Number first, Number second) {
            BigDecimal left = toBigDecimal(first);
            BigDecimal right = toBigDecimal(second);
            return left.add(right);
        }
    
        public Number subtract(Number first, Number second) {
            BigDecimal left = toBigDecimal(first);
            BigDecimal right = toBigDecimal(second);
            return left.subtract(right);
        }
    
        public Number multiply(Number first, Number second) {
            BigDecimal left = toBigDecimal(first);
            BigDecimal right = toBigDecimal(second);
            BigDecimal result = left.multiply(right);
            if (result.scale() > maxScale) {
                result = result.setScale(maxScale, roundingMode);
            }
            return result;
        }
    
        public Number divide(Number first, Number second) {
            BigDecimal left = toBigDecimal(first);
            BigDecimal right = toBigDecimal(second);
            return divide(left, right);
        }
    
        public Number modulus(Number first, Number second) {
            long left = first.longValue();
            long right = second.longValue();
            return Long.valueOf(left % right);
        }
    
        public Number toNumber(String s) {
            return new BigDecimal(s);
        }
        
        private BigDecimal divide(BigDecimal left, BigDecimal right) {
            int scale1 = left.scale();
            int scale2 = right.scale();
            int scale = Math.max(scale1, scale2);
            scale = Math.max(minScale, scale);
            return left.divide(right, scale, roundingMode);
        }
    }

    /**
     * An arithmetic engine that conservatively widens the operation arguments
     * to extent that they can hold the result of the operation. Widening 
     * conversions occur in following situations:
     * <ul>
     * <li>byte and short are always widened to int (alike to Java language).</li>
     * <li>To preserve magnitude: when operands are of different types, the 
     * result type is the type of the wider operand.</li>
     * <li>to avoid overflows: if add, subtract, or multiply would overflow on
     * integer types, the result is widened from int to long, or from long to 
     * BigInteger.</li>
     * <li>to preserve fractional part: if a division of integer types would 
     * have a fractional part, int and long are converted to double, and 
     * BigInteger is converted to BigDecimal. An operation on a float and a 
     * long results in a double. An operation on a float or double and a
     * BigInteger results in a BigDecimal.</li>
     * </ul>
     */
    public static class ConservativeEngine extends ArithmeticEngine {
        private enum NumberType {
            INTEGER, LONG, FLOAT, DOUBLE, BIGINTEGER, BIGDECIMAL;
        }
        
        private static final Map<Class<? extends Number>,NumberType> classCodes = createClassCodesMap();
        
        public int compareNumbers(Number first, Number second) {
            return switch(getCommonClassCode(first, second)) {
                case INTEGER -> {
                    int n1 = first.intValue();
                    int n2 = second.intValue();
                    yield n1 < n2 ? -1 : (n1 == n2 ? 0 : 1);
                }
                case LONG -> {
                    long n1 = first.longValue();
                    long n2 = second.longValue();
                    yield  n1 < n2 ? -1 : (n1 == n2 ? 0 : 1);
                }
                case FLOAT -> {
                    float n1 = first.floatValue();
                    float n2 = second.floatValue();
                    yield n1 < n2 ? -1 : (n1 == n2 ? 0 : 1);
                }
                case DOUBLE -> {
                    double n1 = first.doubleValue();
                    double n2 = second.doubleValue();
                    yield n1 < n2 ? -1 : (n1 == n2 ? 0 : 1);
                }
                case BIGINTEGER -> {
                    BigInteger n1 = toBigInteger(first);
                    BigInteger n2 = toBigInteger(second);
                    yield n1.compareTo(n2);
                }
                case BIGDECIMAL -> {
                    BigDecimal n1 = toBigDecimal(first);
                    BigDecimal n2 = toBigDecimal(second);
                    yield n1.compareTo(n2);
                }
            };
        }
    
        public Number add(Number first, Number second) {
            return switch(getCommonClassCode(first, second)) {
                case INTEGER -> {
                    int n1 = first.intValue();
                    int n2 = second.intValue();
                    int n = n1 + n2;
                    yield
                        ((n ^ n1) < 0 && (n ^ n2) < 0) // overflow check
                        ? (Number)Long.valueOf(((long)n1) + n2)
                        : (Number)Integer.valueOf(n);
                }
                case LONG -> {
                    long n1 = first.longValue();
                    long n2 = second.longValue();
                    long n = n1 + n2;
                    yield
                        ((n ^ n1) < 0 && (n ^ n2) < 0) // overflow check
                        ? (Number)toBigInteger(first).add(toBigInteger(second))
                        : (Number)Long.valueOf(n);
                }
                case FLOAT -> first.floatValue() + second.floatValue();
                case DOUBLE -> first.doubleValue() + second.doubleValue();
                case BIGINTEGER -> {
                    BigInteger n1 = toBigInteger(first);
                    BigInteger n2 = toBigInteger(second);
                    yield n1.add(n2);
                }
                case BIGDECIMAL -> {
                    BigDecimal n1 = toBigDecimal(first);
                    BigDecimal n2 = toBigDecimal(second);
                    yield n1.add(n2);
                }
            };
        }
    
        public Number subtract(Number first, Number second) {
            return switch(getCommonClassCode(first, second)) {
                case INTEGER -> {
                    int n1 = first.intValue();
                    int n2 = second.intValue();
                    int n = n1 - n2;
                    yield
                        ((n ^ n1) < 0 && (n ^ ~n2) < 0) // overflow check
                        ? (Number)Long.valueOf(((long)n1) - n2)
                        : (Number)Integer.valueOf(n);
                }
                case LONG -> {
                    long n1 = first.longValue();
                    long n2 = second.longValue();
                    long n = n1 - n2;
                    yield
                        ((n ^ n1) < 0 && (n ^ ~n2) < 0) // overflow check
                        ? (Number)toBigInteger(first).subtract(toBigInteger(second))
                        : (Number)Long.valueOf(n);
                }
                case FLOAT -> {
                    yield first.floatValue() - second.floatValue();
                }
                case DOUBLE -> {
                    yield first.doubleValue() - second.doubleValue();
                }
                case BIGINTEGER -> {
                    BigInteger n1 = toBigInteger(first);
                    BigInteger n2 = toBigInteger(second);
                    yield n1.subtract(n2);
                }
                case BIGDECIMAL -> {
                    BigDecimal n1 = toBigDecimal(first);
                    BigDecimal n2 = toBigDecimal(second);
                    yield n1.subtract(n2);
                }
            };
        }
    
        public Number multiply(Number first, Number second) {
            return switch(getCommonClassCode(first, second)) {
                case INTEGER -> {
                    int n1 = first.intValue();
                    int n2 = second.intValue();
                    int n = n1 * n2;
                    yield
                        n1==0 || n / n1 == n2 // overflow check
                        ? (Number)Integer.valueOf(n)
                        : (Number)Long.valueOf(((long)n1) * n2);
                }
                case LONG -> {
                    long n1 = first.longValue();
                    long n2 = second.longValue();
                    long n = n1 * n2;
                    yield
                        n1==0L || n / n1 == n2 // overflow check
                        ? (Number)Long.valueOf(n)
                        : (Number)toBigInteger(first).multiply(toBigInteger(second));
                }
                case FLOAT -> first.floatValue() * second.floatValue();
                case DOUBLE -> first.doubleValue() * second.doubleValue();
                case BIGINTEGER -> {
                    BigInteger n1 = toBigInteger(first);
                    BigInteger n2 = toBigInteger(second);
                    yield n1.multiply(n2);
                }
                case BIGDECIMAL -> {
                    BigDecimal n1 = toBigDecimal(first);
                    BigDecimal n2 = toBigDecimal(second);
                    BigDecimal r = n1.multiply(n2);
                    yield r.scale() > maxScale ? r.setScale(maxScale, roundingMode) : r;
                }
            };
        }
    
        public Number divide(Number first, Number second) {
            return switch(getCommonClassCode(first, second)) {
                case INTEGER -> {
                    int n1 = first.intValue();
                    int n2 = second.intValue();
                    if (n1 % n2 == 0) {
                        yield Integer.valueOf(n1/n2);
                    }
                    yield ((double)n1)/n2;
                }
                case LONG -> {
                    long n1 = first.longValue();
                    long n2 = second.longValue();
                    if (n1 % n2 == 0) {
                        yield Long.valueOf(n1/n2);
                    }
                    yield ((double)n1)/n2;
                }
                case FLOAT -> first.floatValue() / second.floatValue();
                case DOUBLE ->first.doubleValue() / second.doubleValue();
                case BIGINTEGER -> {
                    BigInteger n1 = toBigInteger(first);
                    BigInteger n2 = toBigInteger(second);
                    BigInteger[] divmod = n1.divideAndRemainder(n2);
                    if(divmod[1].equals(BigInteger.ZERO)) {
                        yield divmod[0];
                    }
                    else {
                        BigDecimal bd1 = new BigDecimal(n1);
                        BigDecimal bd2 = new BigDecimal(n2);
                        yield bd1.divide(bd2, minScale, roundingMode);
                    }
                }
                case BIGDECIMAL -> {
                    BigDecimal n1 = toBigDecimal(first);
                    BigDecimal n2 = toBigDecimal(second);
                    int scale1 = n1.scale();
                    int scale2 = n2.scale();
                    int scale = Math.max(scale1, scale2);
                    scale = Math.max(minScale, scale);
                    yield n1.divide(n2, scale, roundingMode);
                }
            };
        }
    
        public Number modulus(Number first, Number second) {
            return switch(getCommonClassCode(first, second)) {
                case INTEGER -> first.intValue() % second.intValue();
                case LONG -> first.longValue() % second.longValue();
                case FLOAT -> first.floatValue() % second.floatValue();
                case DOUBLE -> first.doubleValue() % second.doubleValue();
                case BIGINTEGER -> {
                    BigInteger n1 = toBigInteger(first);
                    BigInteger n2 = toBigInteger(second);
                    yield n1.mod(n2);
                }
                case BIGDECIMAL -> throw new TemplateException("Can't calculate remainder on BigDecimals", Environment.getCurrentEnvironment());
            };
        }
    
        public Number toNumber(String s) {
            return optimizeNumberRepresentation(new BigDecimal(s));
        }
        
        private static Map<Class<? extends Number>, NumberType> createClassCodesMap() {
            var map = new HashMap<Class<? extends Number>,NumberType>(17);
            map.put(Byte.class, NumberType.INTEGER);
            map.put(Short.class, NumberType.INTEGER);
            map.put(Integer.class, NumberType.INTEGER);
            map.put(Long.class, NumberType.LONG);
            map.put(Float.class, NumberType.FLOAT);
            map.put(Double.class, NumberType.DOUBLE);
            map.put(BigInteger.class, NumberType.BIGINTEGER);
            map.put(BigDecimal.class, NumberType.BIGDECIMAL);
            return map;
        }
        
        private static NumberType getClassCode(Number num) {
            try {
                return classCodes.get(num.getClass());
            }
            catch(NullPointerException e) {
                if(num == null) {
                    throw new TemplateException("Unknown number type null", Environment.getCurrentEnvironment());
                }
                throw new TemplateException("Unknown number type " + num.getClass().getName(), Environment.getCurrentEnvironment());
            }
        }
        
        private static NumberType getCommonClassCode(Number num1, Number num2) {
            NumberType min = getClassCode(num1);
            NumberType max = getClassCode(num2);
            if (min.compareTo(max) > 0) {
                NumberType temp = min;
                min = max;
                max = temp;
            }
            //If Float is combined with Long, the result is a
            // Double instead of Float to preserve the bigger bit width.
            if (min == NumberType.LONG && max == NumberType.FLOAT) {
                return NumberType.DOUBLE;
            }
            // If BigInteger is combined with a Float or Double, the result is a
            // BigDecimal instead of BigInteger in order not to lose the 
            // fractional parts. 
            if (max == NumberType.BIGINTEGER && (min == NumberType.DOUBLE || min == NumberType.FLOAT)) {
                return NumberType.BIGDECIMAL;
            }
            return max;
        }
        
        private static BigInteger toBigInteger(Number num) {
            return num instanceof BigInteger bi ? bi : new BigInteger(num.toString());
        }
    }

    private static BigDecimal toBigDecimal(Number num) {
        return num instanceof BigDecimal bd ? bd : new BigDecimal(num.toString());
    }

    private static final BigInteger INTEGER_MIN = new BigInteger(Integer.toString(Integer.MIN_VALUE));
    private static final BigInteger INTEGER_MAX = new BigInteger(Integer.toString(Integer.MAX_VALUE));
    private static final BigInteger LONG_MIN = new BigInteger(Long.toString(Long.MIN_VALUE));
    private static final BigInteger LONG_MAX = new BigInteger(Long.toString(Long.MAX_VALUE));

    /**
     * This is needed to reverse the extreme conversions in arithmetic
     * operations so that numbers can be meaningfully used with models that
     * don't know what to do with a BigDecimal. Of course, this will make
     * impossible for these models to receive a BigDecimal even if
     * it was originally placed as such in the data model. However, since
     * arithmetic operations aggressively erase the information regarding the
     * original number type, we have no other choice to ensure expected operation
     * in majority of cases.
     */
    private static Number optimizeNumberRepresentation(Number number) {
        if (number instanceof BigDecimal bd) {
            if (bd.scale() == 0) {
                // BigDecimal -> BigInteger
                number = bd.unscaledValue();
            } else {
                double d = bd.doubleValue();
                if (d != Double.POSITIVE_INFINITY && d != Double.NEGATIVE_INFINITY) {
                    // BigDecimal -> Double
                    return d;
                }
            }
        }
        if (number instanceof BigInteger bi) {
            if (bi.compareTo(INTEGER_MAX) <= 0 && bi.compareTo(INTEGER_MIN) >= 0) {
                // BigInteger -> Integer
                return Integer.valueOf(bi.intValue());
            }
            if (bi.compareTo(LONG_MAX) <= 0 && bi.compareTo(LONG_MIN) >= 0) {
                // BigInteger -> Long
                return Long.valueOf(bi.longValue());
            }
        }
        return number;
    }
}
