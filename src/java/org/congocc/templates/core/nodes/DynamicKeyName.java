package org.congocc.templates.core.nodes;

import static org.congocc.templates.core.Wrap.*;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.reflection.ReflectionCode;
import org.congocc.templates.core.nodes.generated.Expression;
import org.congocc.templates.core.nodes.generated.RangeExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.TemplateException;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class DynamicKeyName extends TemplateNode implements Expression {

    public Expression getNameExpression() {
        return (Expression) get(2);
    }

    public Expression lhs() {
        return (Expression) get(0);
    }

    public Object evaluate(Environment env) {
        Object leftSide = lhs().evaluate(env);
        assertNonNull(leftSide, lhs());
        if (leftSide == LOOSE_NULL) {
            return JAVA_NULL;
        }
        if (getNameExpression() instanceof RangeExpression re) {
            return dealWithRangeKey(leftSide, re, env);
        }
        Object key = getNameExpression().evaluate(env);
        if (key == null) {
            assertNonNull(key, getNameExpression());
        }
        if (key instanceof Number n) {
            return dealWithNumericalKey(leftSide, n.intValue(), env);
        }
        if (key instanceof CharSequence) {
            return dealWithStringKey(leftSide, key.toString(), env);
        }
        if (leftSide instanceof Map m) {
            return m.get(unwrap(key));
        }
        throw invalidTypeException(key, getNameExpression(), "number, range, or string");
    }

    private Object dealWithNumericalKey(Object target, int index, Environment env) {
        if (isList(target)) {
            try {
                return wrap(asList(target).get(index));
            } catch (IndexOutOfBoundsException ae) {
                return JAVA_NULL;
            }
        }
        String s = lhs().getStringValue(env);
        try {
            return s.substring(index, index + 1);
        } catch (RuntimeException re) {
            throw new TemplateException(re);
        }
    }

    private Object dealWithStringKey(Object lhs, String key, Environment env) {
        if (lhs instanceof Map m) {
            Object obj = m.get(key);
            if (obj == null){
                return m.containsKey(key) ? JAVA_NULL : null;
            }
            return wrap(obj);
        }
        return ReflectionCode.getProperty(lhs, key);
    }

    private Object dealWithRangeKey(Object target, RangeExpression range, Environment env) {
        int start = getNumber(range.getLeft(), env).intValue();
        int end = 0;
        boolean hasRhs = range.hasRhs();
        if (hasRhs) {
            end = getNumber(range.getRight(), env).intValue();
        }
        if (isList(target)) {
            List<?> list = asList(target);
            if (!hasRhs) end = list.size() - 1;
            if (start < 0) {
                String msg = range.getRight().getLocation() + "\nNegative starting index for range, is " + range;
                throw new TemplateException(msg);
            }
            if (end < 0) {
                String msg = range.getLeft().getLocation() + "\nNegative ending index for range, is " + range;
                throw new TemplateException(msg);
            }
            if (start >= list.size()) {
                String msg = range.getLeft().getLocation() + "\nLeft side index of range out of bounds, is " + start + ", but the sequence has only " + list.size() + " element(s) " + "(note that indices are 0 based, and ranges are inclusive).";
                throw new TemplateException(msg);
            }
            if (end >= list.size()) {
                String msg = range.getRight().getLocation() + "\nRight side index of range out of bounds, is " + end + ", but the sequence has only " + list.size() + " element(s)." + "(note that indices are 0 based, and ranges are inclusive).";
                throw new TemplateException(msg);
            }
            ArrayList<Object> result = new ArrayList<>();
            if (start > end) {
                for (int i = start; i >= end; i--) {
                    result.add(list.get(i));
                }
            } else {
                for (int i = start; i <= end; i++) {
                    result.add(list.get(i));
                }
            }
            return result;
        }
        String s = lhs().getStringValue(env);
        if (!hasRhs) end = s.length() - 1;
        if (start < 0) {
            String msg = range.getLeft().getLocation() + "\nNegative starting index for range " + range + " : " + start;
            throw new TemplateException(msg);
        }
        if (end < 0) {
            String msg = range.getLeft().getLocation() + "\nNegative ending index for range " + range + " : " + end;
            throw new TemplateException(msg);
        }
        if (start > s.length()) {
            String msg = range.getLeft().getLocation() + "\nLeft side of range out of bounds, is: " + start + "\nbut string " + target + " has " + s.length() + " elements.";
            throw new TemplateException(msg);
        }
        if (end > s.length()) {
            String msg = range.getRight().getLocation() + "\nRight side of range out of bounds, is: " + end + "\nbut string " + target + " is only " + s.length() + " characters.";
            throw new TemplateException(msg);
        }
        try {
            return s.substring(start, end + 1);
        } catch (RuntimeException re) {
            String msg = "Error " + getLocation();
            throw new TemplateException(msg, re);
        }
    }

    public boolean isAssignableTo() {
        return true;
    }
}


