package org.congocc.templates.builtins;

import java.text.NumberFormat;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.congocc.templates.annotations.Parameters;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.TemplateHash;

import static org.congocc.templates.core.variables.Wrap.*;

/**
 * Implementation of ?string built-in 
 */

public class stringBI extends ExpressionEvaluatingBuiltIn {
	
    @Override
    public Object get(Environment env, BuiltInExpression caller,
        Object model) 
    {
        if (model instanceof Number n) {
            return new NumberFormatter(n, env);
        }
        if (isBoolean(model)) {
            return new BooleanFormatter(model, env);
        }
        return model.toString();
    }
	
	
    static class BooleanFormatter implements BiFunction<Object,Object,String>  {
        private final Object bool;
        private final Environment env;
        
        BooleanFormatter(Object bool, Environment env) {
            this.bool = bool;
            this.env = env;
        }

        public String toString() {
            if (bool instanceof CharSequence) {
                return bool.toString();
            } else {
                return env.getBooleanFormat(asBoolean(bool));
            }
        }

        public String apply(Object left, Object right) {
            return asString(asBoolean(bool) ? left : right);
        }
    }
    
    
    static class NumberFormatter implements TemplateHash, Function<String,Object> {
        private final Number number;
        private final Environment env;
        private final NumberFormat defaultFormat;
        private String cachedValue;

        NumberFormatter(Number number, Environment env) {
            this.number = number;
            this.env = env;
            defaultFormat = env.getNumberFormatObject(env.getNumberFormat());
        }

        public String toString() {
            if(cachedValue == null) {
                cachedValue = defaultFormat.format(number);
            }
            return cachedValue;
        }

        public Object get(String key) {
            return env.getNumberFormatObject(key).format(number);
        }
        
        @Parameters("format")
        public Object apply(String arg) {
            return get(arg);
        }
    }
}

	
