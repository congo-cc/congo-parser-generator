package org.congocc.templates.core.variables;

/**
 * You can use this interface to expose Java methods to
 * the template layer. Note that this is a functional interface,
 * so it can actually be used in conjunction with lambdas and
 * method references. For example:
 * Foo myMethod(Object... args) {...}
 * dataModel.put("myMethod", (VarArgsFunction<Foo>) this::myMethod);
 * You can also use a few of the functional interfaces defined 
 * in the core Java class libraries, notably: 
 * java.util.function.Function,
 * java.util.function.BiFunction,
 * and java.util.function.Supplier
 */
@FunctionalInterface
public interface VarArgsFunction<R> {
   R apply(Object... args);
}