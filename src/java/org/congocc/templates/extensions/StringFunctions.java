package org.congocc.templates.extensions;

import java.io.UnsupportedEncodingException;
import java.util.function.Function;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.nodes.generated.DotExpression;

import static org.congocc.templates.core.Wrap.*;

/**
 * Implementations of ?substring and other
 * standard functions that operate on strings
 */
public abstract class StringFunctions extends ExpressionEvaluatingExtension {

    @Override
    public Object get(Environment env, DotExpression caller, Object model) {
        String string = asString(model);
        return apply(string, env, caller);
    }

    public abstract Object apply(final String string, final Environment env, final DotExpression callingExpression);

    public static class Url extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, DotExpression caller) {
            return new urlBIResult(string, env);
        }
    }


    static class urlBIResult implements Function<String,String> {

        private final String target;
        //private final Environment env;
        private String cachedResult;

        private urlBIResult(String target, Environment env) {
            this.target = target;
          //  this.env = env;
        }

        public String toString() {
            if (cachedResult == null) {
                try {
                    // Do we ever use anything other than UTF-8? REVISIT.
                    cachedResult = StringUtil.URLEnc(target, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new EvaluationException(
                            "Failed to execute URL encoding.", e);
                }
            }
            return cachedResult;
        }

        public String apply(String arg) {
            try {
                return StringUtil.URLEnc(target, arg);
            } catch (UnsupportedEncodingException e) {
                throw new EvaluationException(
                        "Failed to execute URL encoding.", e);
            }
        }
    }
}
