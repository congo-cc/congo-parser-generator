package org.congocc.templates.core.variables.scope;

import org.congocc.templates.Template;
import org.congocc.templates.core.Environment;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Represents a variable resolution context in FTL. This 
 * may be the local variables in a macro, the context of a loop
 * or a template namespace 
 */
public interface Scope extends Map<String,Object> {

    default Object resolveVariable(String key) {
    	Object result = get(key);
    	if (result == null && getEnclosingScope() != null) {
    		return getEnclosingScope().resolveVariable(key);
    	}
    	return result;
    }

    /**
     * @return whether the variable is defined in
     * this specific scope. (It could be defined in a 
     * fallback scope and this method will return false.)
     */
    boolean definesVariable(String name);

    /**
     * @return the fallback Scope for variable resolution
     */
    Scope getEnclosingScope();

    default boolean isTemplateNamespace() {
        return false;
    }

    default Template getTemplate() {
        return getEnclosingScope().getTemplate();
    }

    default Environment getEnvironment() {
        return getEnclosingScope().getEnvironment();
    }

    // All of the following 10 default methods exist so
    // that it is not too onerous to implement this 
    // interface.
    default boolean isEmpty() {
        return size() == 0;
    }

    default Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    default boolean containsKey(Object value) {
        throw new UnsupportedOperationException();
    }

    default int size() {
        throw new UnsupportedOperationException();
    }

    default boolean containsValue(Object value) {
        throw new UnsupportedOperationException();
    }

    default Set<Map.Entry<String,Object>> entrySet() {
        throw new UnsupportedOperationException();
    }

    default Set<String> keySet() {
        throw new UnsupportedOperationException();
    }

    default Collection<Object> values() {
        throw new UnsupportedOperationException();
    }

    default void putAll(Map<? extends String,? extends Object> m) {
        throw new UnsupportedOperationException();
    }
    
    default void clear() {
        throw new UnsupportedOperationException();
    }

}
