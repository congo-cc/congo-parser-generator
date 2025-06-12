package org.congocc.templates.core.variables;

import java.lang.reflect.Method;
import java.util.List;

import java.util.ArrayList;
import static org.congocc.templates.core.variables.ReflectionCode.*;

public class JavaMethodCall implements VarArgsFunction<Object> {

    private String methodName;
    private Object target;
    private List<Method> possibleMethods;

    public JavaMethodCall(Object target, String methodName) {
        if (target instanceof WrappedVariable wv) {
            Object wrappedObject = wv.getWrappedObject();
            if (wrappedObject != null) {
                target = wrappedObject;
            }
        }
        this.target = target;
        this.methodName = methodName;
        findPossibleMethods();
    }

    public String getMethodName() {
        return methodName;
    }

    public Object getTarget() {
        return target;
    }

    private void findPossibleMethods() {
        Class<?> clazz = target.getClass();
        Method[] methods = clazz.getMethods();
        possibleMethods = new ArrayList<>();
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                possibleMethods.add(method);
            }
        }
        if (possibleMethods.size() == 1) {
            possibleMethods.get(0).setAccessible(true);
        }
    }

    public boolean isMethodOverloaded() {
        return possibleMethods.size() > 1;
    }

    public boolean isInvalidMethodName() {
        return possibleMethods.size() == 0;
    }


    /**
     * Call the apropriate Java method using the params
     * passed in
     */
    public Object apply(Object... params) {
        if (isInvalidMethodName()) throw new EvaluationException("No such method " + methodName + " in class: " + target.getClass());
        if (!isMethodOverloaded())  {
            // If there is only one method of this name, just try to
            // call it and that's that! This is the percentage case, after all.
            return invokeMethod(target, possibleMethods.get(0), params);
        }
        Method method = getCachedMethod(target, methodName, params);
        if (method != null) {
            // If we have already figured out which method
            // to call and cached it, then we use that! 
            return invokeMethod(target, method, params);
        }
        Method matchedMethod = null;
        for (Method m : possibleMethods) {
            if (!isCompatibleMethod(m, params)) continue;
            if (matchedMethod == null || isMoreSpecific(m, matchedMethod, params)) {
                matchedMethod = m;
            }
        }
        if (matchedMethod == null) {
            throw new EvaluationException("Cannot invoke method " + methodName + " here.");
        }
        cacheMethod(matchedMethod, target, params);
        return invokeMethod(target, matchedMethod, params);
    }    
}