package org.congocc.templates.core.reflection;

@FunctionalInterface
public interface QuadFunction<T,U,V,W,R> {
    R apply(T t, U u, V v, W w);
}