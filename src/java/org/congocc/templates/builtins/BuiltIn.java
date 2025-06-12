package org.congocc.templates.builtins;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;

public interface BuiltIn {
    Object get(Environment env, BuiltInExpression caller);
}
