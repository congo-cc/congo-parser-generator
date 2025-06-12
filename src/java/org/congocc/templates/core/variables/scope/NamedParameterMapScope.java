package org.congocc.templates.core.variables.scope;

import java.util.Map;

public class NamedParameterMapScope implements Scope {
    private final Map<String, Object> parameters;
    private Scope enclosingScope;
    
    public NamedParameterMapScope(Scope enclosingScope, Map<String, Object> parameters) {
        this.enclosingScope = enclosingScope;
        this.parameters = parameters;
    }

    public Scope getEnclosingScope() {
        return enclosingScope;
    }

    public boolean definesVariable(String name) {
        return parameters.containsKey(name);
    }

    public Object put(String key, Object value) {
        return parameters.put(key, value);
    }

    public Object remove(String key) {
        return parameters.remove(key);
    }

    public int size() {
        return parameters.size();
    }

    public Object get(Object key) {
        return parameters.get(key);
    }
}
