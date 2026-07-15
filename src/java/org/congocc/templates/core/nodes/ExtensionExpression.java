package org.congocc.templates.core.nodes;

import org.congocc.templates.extensions.*;
import org.congocc.templates.core.Environment;
import java.util.HashMap;
import org.congocc.templates.core.nodes.generated.*;

public class ExtensionExpression extends TemplateNode implements Expression {

    private static final HashMap<String, Extension> knownBuiltins = new HashMap<String, Extension>();
    {
        knownBuiltins.put("_source", (caller, env) -> caller.lhs().getSource());
        knownBuiltins.put("instanceof", new instanceofBI());
        knownBuiltins.put("_c", new cBI());
        knownBuiltins.put("_string", new stringBI());
        knownBuiltins.put("_eval", new evalBI());
        NumericalCast numericalCast = new NumericalCast();
        knownBuiltins.put("byte", numericalCast);
        knownBuiltins.put("double", numericalCast);
        knownBuiltins.put("float", numericalCast);
        knownBuiltins.put("int", numericalCast);
        knownBuiltins.put("long", numericalCast);
        knownBuiltins.put("short", numericalCast);
        knownBuiltins.put("_floor", numericalCast);
        knownBuiltins.put("_ceiling", numericalCast);
        knownBuiltins.put("_round", numericalCast);
        knownBuiltins.put("_capitalize", new StringTransformations.Capitalize());
        knownBuiltins.put("cap_first", new StringTransformations.CapFirst(true));
        knownBuiltins.put("uncap_first", new StringTransformations.CapFirst(false));
        knownBuiltins.put("j_string", new StringTransformations.Java());
        knownBuiltins.put("js_string", new StringTransformations.JavaScript());
        knownBuiltins.put("chop_linebreak", new StringTransformations.Chomp());
        knownBuiltins.put("_html", new StringTransformations.Html());
        knownBuiltins.put("web_safe", knownBuiltins.get("_html"));
        knownBuiltins.put("_rtf", new StringTransformations.Rtf());
        knownBuiltins.put("_xml", new StringTransformations.Xml());
        knownBuiltins.put("_xhtml", new StringTransformations.Xhtml());
        knownBuiltins.put("join", new StringFunctions.Join());
        knownBuiltins.put("_number", new numberBI());
        knownBuiltins.put("left_pad", new StringFunctions.LeftPad());
        knownBuiltins.put("right_pad", new StringFunctions.RightPad());
        knownBuiltins.put("_groups", new groupsBI());
        knownBuiltins.put("_matches", new StringFunctions.Matches());
        knownBuiltins.put("word_list", new StringFunctions.WordList());
        knownBuiltins.put("_url", new StringFunctions.Url());
        knownBuiltins.put("_first", new SequenceFunctions.First());
        knownBuiltins.put("_last", new SequenceFunctions.Last());
        knownBuiltins.put("_reverse", new SequenceFunctions.Reverse());
        knownBuiltins.put("_scope", new MacroBuiltins.Scope());
        knownBuiltins.put("_namespace", new MacroBuiltins.Namespace());
        knownBuiltins.put("_keys", new HashBuiltin.Keys());
        knownBuiltins.put("_values", new HashBuiltin.Values());
    }

    private String key;
    private Extension bi;

    public void close() {
        key = get(2).toString().intern();
        bi = knownBuiltins.get(key);
    }

    public Expression lhs() {
        return (Expression) get(0);
    }

    public Extension getExtension() {
        return bi;
    }

    public Object evaluate(Environment env) {
        return bi.get(this, env);
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


