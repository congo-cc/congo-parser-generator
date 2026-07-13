package org.congocc.templates.core.nodes;

import static org.congocc.templates.core.variables.Wrap.asString;
import org.congocc.templates.core.variables.JavaMethodCall;
import org.congocc.templates.builtins.*;
import org.congocc.templates.core.Environment;
import java.util.HashMap;
import org.congocc.templates.core.parser.*;
import org.congocc.templates.core.nodes.generated.*;

public class BuiltInExpression extends TemplateNode implements Expression {

    private static final HashMap<String, BuiltIn> knownBuiltins = new HashMap<String, BuiltIn>();
    {
        knownBuiltins.put("source", (env, caller) -> caller.getTarget().getSource());
        knownBuiltins.put("upper_case", (env, caller) -> asString(caller.getTarget().evaluate(env)).toUpperCase(env.getLocale()));
        knownBuiltins.put("lower_case", (env, caller) -> asString(caller.getTarget().evaluate(env)).toLowerCase(env.getLocale()));
        knownBuiltins.put("length", (env, caller) -> asString(caller.getTarget().evaluate(env)).length());
        knownBuiltins.put("trim", (env, caller) -> asString(caller.getTarget().evaluate(env)).trim());
        knownBuiltins.put("substring", (env, caller) -> new JavaMethodCall(asString(caller.getTarget().evaluate(env)), "substring", caller));
        knownBuiltins.put("instanceof", new instanceofBI());
        knownBuiltins.put("exists", new ExistenceBuiltIn.ExistsBuiltIn());
        knownBuiltins.put("c", new cBI());
        knownBuiltins.put("size", new sizeBI());
        knownBuiltins.put("string", new stringBI());
        knownBuiltins.put("eval", new evalBI());
        NumericalCast numericalCast = new NumericalCast();
        knownBuiltins.put("byte", numericalCast);
        knownBuiltins.put("double", numericalCast);
        knownBuiltins.put("float", numericalCast);
        knownBuiltins.put("int", numericalCast);
        knownBuiltins.put("long", numericalCast);
        knownBuiltins.put("short", numericalCast);
        knownBuiltins.put("floor", numericalCast);
        knownBuiltins.put("ceiling", numericalCast);
        knownBuiltins.put("round", numericalCast);
        knownBuiltins.put("capitalize", new StringTransformations.Capitalize());
        knownBuiltins.put("cap_first", new StringTransformations.CapFirst(true));
        knownBuiltins.put("uncap_first", new StringTransformations.CapFirst(false));
        knownBuiltins.put("j_string", new StringTransformations.Java());
        knownBuiltins.put("js_string", new StringTransformations.JavaScript());
        knownBuiltins.put("chop_linebreak", new StringTransformations.Chomp());
        knownBuiltins.put("html", new StringTransformations.Html());
        knownBuiltins.put("web_safe", knownBuiltins.get("html"));
        knownBuiltins.put("rtf", new StringTransformations.Rtf());
        knownBuiltins.put("xml", new StringTransformations.Xml());
        knownBuiltins.put("xhtml", new StringTransformations.Xhtml());
        knownBuiltins.put("join", new StringFunctions.Join());
        knownBuiltins.put("index_of", new StringFunctions.IndexOf());
        knownBuiltins.put("last_index_of", new StringFunctions.LastIndexOf());
        knownBuiltins.put("contains", new StringFunctions.Contains());
        knownBuiltins.put("number", new numberBI());
        knownBuiltins.put("left_pad", new StringFunctions.LeftPad());
        knownBuiltins.put("right_pad", new StringFunctions.RightPad());
        knownBuiltins.put("replace", new StringFunctions.Replace());
        knownBuiltins.put("groups", new groupsBI());
        knownBuiltins.put("matches", new StringFunctions.Matches());
        knownBuiltins.put("word_list", new StringFunctions.WordList());
        knownBuiltins.put("url", new StringFunctions.Url());
        knownBuiltins.put("first", new SequenceFunctions.First());
        knownBuiltins.put("last", new SequenceFunctions.Last());
        knownBuiltins.put("reverse", new SequenceFunctions.Reverse());
        knownBuiltins.put("sort", new SequenceFunctions.Sort());
        knownBuiltins.put("scope", new MacroBuiltins.Scope());
        knownBuiltins.put("namespace", new MacroBuiltins.Namespace());
        knownBuiltins.put("keys", new HashBuiltin.Keys());
        knownBuiltins.put("values", new HashBuiltin.Values());
        knownBuiltins.put("is_defined", new ExistenceBuiltIn.IsDefinedBuiltIn());
        knownBuiltins.put("default", new ExistenceBuiltIn.DefaultBuiltIn());
        knownBuiltins.put("has_content", new ExistenceBuiltIn.HasContentBuiltIn());
    }

    private String key;
    private BuiltIn bi;

    public void close() {
        key = getKeyTok().toString().intern();
        bi = knownBuiltins.get(key);
        if (bi == null) {
            throw new ParseException("unknown builtin: ?" + key + " at " + getKeyTok().getLocation());
        }
    }

    public Expression getTarget() {
        return (Expression) get(0);
    }

    public Token getKeyTok() {
        return (Token) get(2);
    }

    public BuiltIn getBuiltIn() {
        return bi;
    }

    public Object evaluate(Environment env) {
        return bi.get(env, this);
    }

    public String getName() {
        return key;
    }
}


