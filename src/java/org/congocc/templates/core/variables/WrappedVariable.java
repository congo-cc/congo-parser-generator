package org.congocc.templates.core.variables;

/**
 * <p>This is a marker interface that indicates
 * that an object is actually a wrapper around some 
 * other object. This is now really only used in terms
 * of passing variables to Java methods.
 */
public interface WrappedVariable {
    default Object getWrappedObject() {
        return this;
    };
}