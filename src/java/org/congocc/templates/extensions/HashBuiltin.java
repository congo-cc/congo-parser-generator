package org.congocc.templates.extensions;

import org.congocc.templates.TemplateException;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.nodes.generated.TemplateNode;

import static org.congocc.templates.core.Wrap.*;

import java.util.ArrayList;
import java.util.Map;

/**
 * Implementation of ?resolve built-in
 */

public abstract class HashBuiltin extends ExpressionEvaluatingExtension {

    @Override
    public Object get(Environment env, DotExpression caller, Object lhs)
    {
        if (!isMap(lhs)) {
            throw TemplateNode.invalidTypeException(lhs, caller.lhs(), "map");
        }
        return apply(unwrap(lhs));
    }

    public abstract Iterable apply(Object hash);

    public static class Keys extends HashBuiltin {
        @Override
        public Iterable apply(Object arg) {
            if (arg instanceof Map m) {
                return new ArrayList(m.keySet());
            }
            throw new TemplateException("Expecting a map here.");
        }
    }

    public static class Values extends HashBuiltin {
        @Override
        public Iterable apply(Object hash)
        {
            return new ArrayList(((Map) hash).values());
        }
    }
}
