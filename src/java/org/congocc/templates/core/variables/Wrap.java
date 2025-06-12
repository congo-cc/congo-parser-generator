package org.congocc.templates.core.variables;

import java.util.*;
import java.lang.reflect.Array;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.Expression;
import org.congocc.templates.TemplateBoolean;
import org.congocc.templates.TemplateException;
import org.congocc.templates.TemplateSequence;

public class Wrap {
    /**
     * A sort of general-purpose magic object that can be used 
     * to simulate typical loose scripting language sorts of 
     * behaviors in your templates. In the appropriate 
     * context it is a string, a list, a map. As a string, it 
     * is simply zero-output. It behaves like an empty list or 
     * empty map. It is also a function that always returns null!
     */
    public static final Object LOOSE_NULL = new Object();

    /**
     * A singleton value used to represent a java null
     * which comes from a wrapped Java API, for example, i.e.
     * is intentional. A null that comes from a generic container
     * like a map is assumed to be unintentional and a 
     * result of programming error.
     */
    public static final Object JAVA_NULL = new JavaNull(); 
    
    static private class JavaNull implements WrappedVariable {
        public Object getWrappedObject() {
            return null;
        }
    }

    private static final Class<?> RECORD_CLASS;

    static {
        Class<?> clazz = null;
        try {
            clazz = Class.forName("java.lang.Record");
        } catch (Exception e) {
        }
        finally {
            RECORD_CLASS = clazz;
        }
    }

    private Wrap() {}

    public static boolean isRecord(Object obj) {
        return RECORD_CLASS != null && RECORD_CLASS.isInstance(obj);
    }

    public static boolean isMap(Object obj) {
        if (obj instanceof WrappedVariable) {
            obj = ((WrappedVariable) obj).getWrappedObject();
        }
        return obj instanceof Map;
    }

    public static boolean isList(Object obj) {
        if (obj instanceof TemplateSequence) {
            return true;
        }
        if (obj.getClass().isArray()) {
            return true;
        }
        return obj instanceof List;
    }

    public static List<?> asList(Object obj) {
        if (obj instanceof TemplateSequence) {
            TemplateSequence tsm = (TemplateSequence) obj;
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < tsm.size(); i++)
                result.add(tsm.get(i));
            return result;
        }
        if (obj.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(obj); i++) {
                result.add(Array.get(obj, i));
            }
            return result;
        }
        return (List<?>) obj;
    }

    public static String asString(Object obj) {
        return obj.toString();
    }

    public static boolean isBoolean(Object obj) {
        if (obj instanceof TemplateBoolean) {
            return true;
        }
        if (obj instanceof WrappedVariable) {
            obj = ((WrappedVariable) obj).getWrappedObject();
        }
        return obj instanceof Boolean;
    }

    public static boolean asBoolean(Object obj) {
        if (obj instanceof TemplateBoolean) {
            return ((TemplateBoolean) obj).getAsBoolean();
        }
        return (Boolean) obj;
    }

    public static boolean isIterable(Object obj) {
        return obj instanceof Iterable 
              || obj instanceof Iterator 
              || obj.getClass().isArray();
    }

    public static Iterator<?> asIterator(Object obj) {
        if (obj instanceof Iterator) {
            return (Iterator<?>) obj;
        }
        if (obj.getClass().isArray()) {
            final Object arr = obj;
            return new Iterator<Object>() {
                int index = 0;

                public boolean hasNext() {
                    return index < Array.getLength(arr);
                }

                public Object next() {
                    return Array.get(arr, index++);
                }
            };
        }
        return ((Iterable<?>) obj).iterator();
    }

    public static Object wrap(Object object) {
        if (object == null) {
            return JAVA_NULL;
        }
        return object;
    }

    public static Object unwrap(Object object) {
        if (object == null) {
            throw new EvaluationException("invalid reference");
        }
        if (object == JAVA_NULL) {
            return null;
        }
        if (object instanceof WrappedVariable) {
            Object unwrapped = ((WrappedVariable) object).getWrappedObject();
            if (unwrapped !=null) {
                return unwrapped;
            }
        }
        return object;
    }

    static public Number getNumber(Object object, Expression expr, Environment env)
    {
        if(object instanceof Number) {
            return (Number) object;
        }
        else if(object == null) {
            throw new InvalidReferenceException(expr + " is undefined.", env);
        }
        else if(object == JAVA_NULL) {
            throw new InvalidReferenceException(expr + " is null.", env);
        }
        else {
            throw new TemplateException(expr + " is not a number, it is " + object.getClass().getName(), env);
        }
    }

    static public Number getNumber(Expression expr, Environment env)
    {
        Object value = expr.evaluate(env);
        return getNumber(value, expr, env);
    }
}