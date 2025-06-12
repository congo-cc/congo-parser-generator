package org.congocc.templates.core.nodes;

import static org.congocc.templates.core.variables.Wrap.*;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.variables.*;
import org.congocc.templates.core.nodes.generated.Expression;
import org.congocc.templates.core.nodes.generated.RangeExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;
import org.congocc.templates.TemplateException;
import org.congocc.templates.TemplateHash;
import org.congocc.templates.TemplateSequence;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class DynamicKeyName extends TemplateNode implements Expression {

    public Expression getNameExpression() {
        return (Expression) get(2);
    }

    public Expression getTarget() {
        return (Expression) get(0);
    }

    public Object evaluate(Environment env) {
        Object lhs = getTarget().evaluate(env);
        getTarget().assertNonNull(lhs, env);
        if (lhs == LOOSE_NULL) {
            return JAVA_NULL;
        }
        if (getNameExpression() instanceof RangeExpression re) {
            return dealWithRangeKey(lhs, re, env);
        }
        Object key = getNameExpression().evaluate(env);
        if (key == null) {
            getNameExpression().assertNonNull(key, env);
        }
        if (key instanceof Number n) {
            return dealWithNumericalKey(lhs, n.intValue(), env);
        }
        if (key instanceof CharSequence) {
            return dealWithStringKey(lhs, key.toString(), env);
        }
        if (isMap(lhs)) {
            return ((Map<?,?>) unwrap(lhs)).get(unwrap(key));
        }
        throw invalidTypeException(key, getNameExpression(), env, "number, range, or string");
    }

    private Object dealWithNumericalKey(Object target, int index, Environment env) {
        if (target instanceof TemplateSequence ts) {
            int size = Integer.MAX_VALUE;
            try {
                size = ts.size();
            } catch (Exception e) {
            }
            return index < size ? ts.get(index) : JAVA_NULL;
        }
        if (isList(target)) {
            try {
                return wrap(asList(target).get(index));
            } catch (IndexOutOfBoundsException ae) {
                return JAVA_NULL;
            }
        }
        String s = getTarget().getStringValue(env);
        try {
            return s.substring(index, index + 1);
        } catch (RuntimeException re) {
            throw new TemplateException("", re, env);
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
        if (lhs instanceof TemplateHash th) {
            return wrap(th.get(key));
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
                throw new TemplateException(msg, env);
            }
            if (end < 0) {
                String msg = range.getLeft().getLocation() + "\nNegative ending index for range, is " + range;
                throw new TemplateException(msg, env);
            }
            if (start >= list.size()) {
                String msg = range.getLeft().getLocation() + "\nLeft side index of range out of bounds, is " + start + ", but the sequence has only " + list.size() + " element(s) " + "(note that indices are 0 based, and ranges are inclusive).";
                throw new TemplateException(msg, env);
            }
            if (end >= list.size()) {
                String msg = range.getRight().getLocation() + "\nRight side index of range out of bounds, is " + end + ", but the sequence has only " + list.size() + " element(s)." + "(note that indices are 0 based, and ranges are inclusive).";
                throw new TemplateException(msg, env);
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
        String s = getTarget().getStringValue(env);
        if (!hasRhs) end = s.length() - 1;
        if (start < 0) {
            String msg = range.getLeft().getLocation() + "\nNegative starting index for range " + range + " : " + start;
            throw new TemplateException(msg, env);
        }
        if (end < 0) {
            String msg = range.getLeft().getLocation() + "\nNegative ending index for range " + range + " : " + end;
            throw new TemplateException(msg, env);
        }
        if (start > s.length()) {
            String msg = range.getLeft().getLocation() + "\nLeft side of range out of bounds, is: " + start + "\nbut string " + target + " has " + s.length() + " elements.";
            throw new TemplateException(msg, env);
        }
        if (end > s.length()) {
            String msg = range.getRight().getLocation() + "\nRight side of range out of bounds, is: " + end + "\nbut string " + target + " is only " + s.length() + " characters.";
            throw new TemplateException(msg, env);
        }
        try {
            return s.substring(start, end + 1);
        } catch (RuntimeException re) {
            String msg = "Error " + getLocation();
            throw new TemplateException(msg, re, env);
        }
    }

    public boolean isAssignableTo() {
        return true;
    }
}


