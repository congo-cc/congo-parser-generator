package org.congocc.templates.core.reflection;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.ArrayList;

import org.congocc.templates.TemplateException;
import org.congocc.templates.core.parser.Node;
import org.congocc.templates.core.EvaluationException;

import static org.congocc.templates.core.reflection.ReflectionCode.cacheConstructor;
import static org.congocc.templates.core.reflection.ReflectionCode.getCachedConstructor;
import static org.congocc.templates.core.reflection.ReflectionCode.invokeConstructor;
import static org.congocc.templates.core.reflection.ReflectionCode.isCompatibleMethod;
import static org.congocc.templates.core.reflection.ReflectionCode.isMoreSpecific;
import static org.congocc.templates.core.variables.Wrap.assertIsDefined;
import org.congocc.templates.core.variables.WrappedVariable;

public class JavaConstructorCall implements VarArgsFunction<Object> {

    private Class<?> clazz;
    private List<Constructor<?>> constructors = new ArrayList<>();
    private Node location;

    public JavaConstructorCall(Object target, Node location) {
        assertIsDefined(target, location);
        this.location = location;
        if (target instanceof WrappedVariable wv) {
            Object wrappedObject = wv.getWrappedObject();
            if (wrappedObject != null) {
                target = wrappedObject;
            }
        }
        if (target instanceof Class clazz) {
            this.clazz = clazz;
        }
        else if (target instanceof CharSequence cs) {
            try {
                this.clazz = Class.forName(cs.toString());
            } catch (Exception e) {
                throw new TemplateException(e);
            }
        }
        else {
            throw new TemplateException("Cannot invoke a constructor on target: " + target);
        }
        for (Constructor<?> cons : clazz.getConstructors()) {
            constructors.add(cons);
        }
    }

    public Object apply(Object... args) {
        if (constructors.size() == 1) {
            return invokeConstructor(constructors.get(0), args, location);
        }
        Constructor<?> cons = getCachedConstructor(clazz, args);
        if (cons != null) {
            return invokeConstructor(constructors.get(0), args, location);
        }
        Constructor<?> matchedConstructor = null;
        for (Constructor<?> c : constructors) {
            if (!isCompatibleMethod(c, args)) continue;
            if (matchedConstructor == null || isMoreSpecific(c, matchedConstructor, args)) {
                matchedConstructor = c;
            }
        }
        if (matchedConstructor == null) {
            throw new EvaluationException("Cannot invoke constructor with these arguments.");
        }
        cacheConstructor(matchedConstructor, args);
        return invokeConstructor(matchedConstructor, args, location);
    }
}