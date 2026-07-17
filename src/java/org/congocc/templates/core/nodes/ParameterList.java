package org.congocc.templates.core.nodes;

import org.congocc.templates.TemplateException;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.*;
import java.util.*;

public class ParameterList extends TemplateNode {
    private List<String> params = new ArrayList<String>();
    private Map<String, Expression> defaults;
    private String catchall;

    public List<String> getParams() {
        return params;
    }

    public List<String> getParamNames() {
        List<String> result = new ArrayList<>(params);
        if (catchall != null) result.add(catchall);
        return result;
    }

    boolean containsParam(String name) {
        return params.contains(name);
    }

    public void addParam(String paramName) {
        params.add(paramName);
    }

    public void addParamWithDefaultValue(String paramName, Expression defaultExp) {
        if (defaults == null) defaults = new HashMap<String, Expression>();
        defaults.put(paramName, defaultExp);
        addParam(paramName);
    }

    public void setCatchAll(String varname) {
        this.catchall = varname;
    }

    public String getCatchAll() {
        return catchall;
    }

    private boolean hasDefaultParams() {
        return defaults != null && !defaults.isEmpty();
    }

    public Expression getDefaultExpression(String paramName) {
        return defaults == null ? null : defaults.get(paramName);
    }

    private int numRequiredParams() {
        int result = getParams().size();
        if (defaults != null) result -= defaults.size();
        if (catchall != null) result--;
        return result;
    }

    /**
    * Given a positional args list, creates a map of key-value pairs based
    * on the named parameter info encapsulated in this object.
    */
    public Map<String, Object> getParameterMap(final PositionalArgsList args, final Environment env, boolean ignoreExtraParams) {
        int numArgs = args.numArgs();
        if (numArgs < numRequiredParams()) {
            String message = "Expecting exactly " + numRequiredParams() + " arguments, received " + numArgs + ".";
            if (catchall != null || hasDefaultParams()) {
                message = message.replace("exactly", "at least");
            }
            throw new TemplateException(message);
        }
        if (!ignoreExtraParams && catchall == null && numArgs > params.size()) {
            String message = "Expecting exactly " + params.size() + " arguments, received " + args.numArgs() + ".";
            if (defaults != null && !defaults.isEmpty()) {
                message = message.replace("exactly", "at most");
            }
            throw new TemplateException(message);
        }
        Map<String,Object> result = new HashMap<>();
        int i = 0;
        boolean dealWithCatchall = false;
        while (i < params.size() && i<numArgs) {
            String param = params.get(i);
            if (param.equals(catchall)) {
                dealWithCatchall = true;
                break;
            }
            result.put(param, args.getValueAt(i++, env));
        }
        if (dealWithCatchall) {
            List<Object> catchAllArgs = new ArrayList<>();
            while (i++ < numArgs) {
                catchAllArgs.add(args.getValueAt(i,env));
            }
            result.put(catchall, catchAllArgs);
        }
        if (hasDefaultParams()) {
            for (String param : defaults.keySet()) {
                if (!result.containsKey(param)) {
                    Expression exp = defaults.get(param);
                    Object value = exp.evaluate(env);
                    result.put(param, value);
                }
            }
        }
        return result;
    }

    public Map<String, Object> getParameterMap(NamedArgsList args, Environment env) {
        Map<String,Object> result = args.getParameterMap(null, env);
        if (defaults != null) {
            for (String param : defaults.keySet()) {
                if (!result.containsKey(param)) {
                    Expression exp = defaults.get(param);
                    Object value = exp.evaluate(env);
                    result.put(param, value);
                }
            }
        }
        int  minParameters = params.size();
        if (defaults != null) minParameters -= defaults.size();
        if (result.size() < minParameters) {
                String message = "Expecting exactly " + minParameters + " arguments, received " + result.size() + ".";
            if (defaults != null && !defaults.isEmpty()) {
                message = message.replace("exactly", "at least");
            }
            throw new TemplateException(message);
        }
        return result;
    }

    public Map<String, Object> getParameterMap(ArgsList args, Environment env) {
        if (args instanceof NamedArgsList nargs) {
            return getParameterMap(nargs, env);
        }
        return getParameterMap((PositionalArgsList) args, env, false);
    }
}
