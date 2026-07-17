package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;

@FunctionalInterface
public interface Extension {
    Object get(DotExpression caller, Environment env);
}
