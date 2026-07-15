package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.ExtensionExpression;

@FunctionalInterface
public interface Extension {
    Object get(Environment env, ExtensionExpression caller);
}
