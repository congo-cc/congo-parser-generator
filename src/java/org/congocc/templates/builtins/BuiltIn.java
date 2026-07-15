package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.BuiltInExpression;

@FunctionalInterface
public interface BuiltIn {
    Object get(Environment env, BuiltInExpression caller);
}
