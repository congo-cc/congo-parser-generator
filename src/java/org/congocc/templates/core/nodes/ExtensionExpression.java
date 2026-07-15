package org.congocc.templates.core.nodes;

import org.congocc.templates.extensions.*;
import org.congocc.templates.core.Environment;
import java.util.HashMap;
import org.congocc.templates.core.nodes.generated.*;

public class ExtensionExpression extends TemplateNode implements Expression {

    private static final HashMap<String, Extension> knownBuiltins = new HashMap<String, Extension>();
    {
        knownBuiltins.put("source", (env, caller) -> caller.getTarget().getSource());
        knownBuiltins.put("instanceof", new instanceofBI());
        knownBuiltins.put("c", new cBI());
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
        knownBuiltins.put("number", new numberBI());
        knownBuiltins.put("left_pad", new StringFunctions.LeftPad());
        knownBuiltins.put("right_pad", new StringFunctions.RightPad());
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
    }

    private String key;
    private Extension bi;

    public void close() {
        key = get(2).toString().intern();
        bi = knownBuiltins.get(key);
    }

    public Expression getTarget() {
        return (Expression) get(0);
    }

    public Extension getExtension() {
        return bi;
    }

    public Object evaluate(Environment env) {
        return bi.get(env, this);
    }

    public String getName() {
        return key;
    }

    public static void registerBuiltin(String key, Extension bi) {
        knownBuiltins.put(key, bi);
    }

    public static Extension getExtension(String name) {
        return knownBuiltins.get(name);
    }
}


