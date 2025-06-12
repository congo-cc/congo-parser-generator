package org.congocc.templates.core.variables.scope;

import java.util.List;

public class NamedParameterListScope implements Scope {
    private final List<String> paramNames;
    private final List<Object> paramValues;
    private final boolean readOnly;
    private Scope enclosingScope;
    
    public NamedParameterListScope(Scope enclosingScope, 
                                   List<String> paramNames, 
                                   List<Object> paramValues, 
                                   boolean readOnly) 
    {
        this.enclosingScope = enclosingScope;
        this.paramNames = paramNames;
        this.paramValues = paramValues;
        this.readOnly = readOnly;
    }

    public Scope getEnclosingScope() {
        return enclosingScope;
    }

    public boolean definesVariable(String name) {
        return paramNames.contains(name);
    }

    public Object put(String key, Object value) {
        if(readOnly) {
            throw new UnsupportedOperationException();
        }
        int i = paramNames.indexOf(key);
        if(i == -1) {
            throw new IllegalArgumentException("key " + key + " not found");
        }
        while(i >= paramValues.size()) {
            paramValues.add(null);
        }
        return paramValues.set(i, value);
    }

    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    public int size() {
        int nonNullCount = 0;
        int size = Math.min(paramNames.size(), paramValues.size());
        for(int i = 0; i < size; ++i) {
            if(paramValues.get(i) != null) {
                ++nonNullCount;
            }
        }
        return nonNullCount;
    }

    public Object get(Object key) {
        int i = paramNames.indexOf(key);
        return i != -1 && i < paramValues.size() ? paramValues.get(i) : null;
    }
}
