package org.congocc.templates;

import org.congocc.templates.core.variables.EvaluationException;

/**
 * An interface used to indicate show that the object represents a set of 
 * key-value mappings. In Congo Templates, one will mostly just use Java API objects
 * that implement java.util.Map. 
 */
public interface TemplateHash {
    
    /**
     * Gets a variable from the hash.
     *
     * @param key the name by which the value
     * is identified in the template.
     * @return the value referred to by the key,
     * or null if not found.
     */
    Object get(String key);

    default boolean isEmpty() {
        return false;
    }

   /**
     * @return the number of key/value mappings in the hash.
     */
    default int size() {
        throw new EvaluationException("Unsupported method size()");
    }

    /**
     * @return a collection containing the keys in the hash. 
     */
    default Iterable<?> keys() {
        throw new EvaluationException("Unsupported method keys()");
    }

    /**
     * @return a collection containing the values in the hash.
     */
    default Iterable<?> values() {
        throw new EvaluationException("Unsupported method values()");
    }
}
