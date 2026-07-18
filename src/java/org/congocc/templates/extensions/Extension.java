package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.nodes.generated.DotExpression;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@FunctionalInterface
public interface Extension {
    Object get(DotExpression caller, Environment env);

    static Extension find(String name) {
        return Inner.knownExtensions.get(name);
    }

    static void register(String name, Extension ext) {
        Inner.knownExtensions.put(name, ext);
    }

    static void register(String name, Function<Object,?> func) {
        register(name, (exp,env)->func.apply(exp.lhs().evaluate(env)));
    }

    static void remove(String name) {
        Inner.knownExtensions.remove(name);
    }

    static boolean isExtension(String name) {
        return Inner.knownExtensions.get(name) != null;
    }

    // Using inner class because you can't put a static initializer
    // in an interface
    static class Inner {
        private static Map<String, Extension> knownExtensions = new ConcurrentHashMap<>();
        static
        {
            knownExtensions.put("Source", (caller, env) -> caller.lhs().getSource());
            knownExtensions.put("instanceof", new instanceofBI());
            knownExtensions.put("C", new cBI());
            knownExtensions.put("Eval", new evalBI());
            NumericalCast numericalCast = new NumericalCast();
            knownExtensions.put("byte", numericalCast);
            knownExtensions.put("double", numericalCast);
            knownExtensions.put("float", numericalCast);
            knownExtensions.put("int", numericalCast);
            knownExtensions.put("long", numericalCast);
            knownExtensions.put("short", numericalCast);
            knownExtensions.put("Floor", numericalCast);
            knownExtensions.put("Ceiling", numericalCast);
            knownExtensions.put("Round", numericalCast);
            knownExtensions.put("Capitalize", new StringTransformations.Capitalize());
            knownExtensions.put("CapFirst", new StringTransformations.CapFirst(true));
            knownExtensions.put("UncapFirst", new StringTransformations.CapFirst(false));
            knownExtensions.put("JavaStringEncode", new StringTransformations.Java());
            knownExtensions.put("JavaScriptStringEncode", new StringTransformations.JavaScript());
            knownExtensions.put("ChopLinebreak", new StringTransformations.Chomp());
            knownExtensions.put("HTML", new StringTransformations.Html());
            knownExtensions.put("WebSafe", knownExtensions.get("HTML"));
            knownExtensions.put("RTF", new StringTransformations.Rtf());
            knownExtensions.put("XML", new StringTransformations.Xml());
            knownExtensions.put("XHTML", new StringTransformations.Xhtml());
            knownExtensions.put("Join", new StringFunctions.Join());
            knownExtensions.put("Number", new numberBI());
            knownExtensions.put("LeftPad", new StringFunctions.LeftPad());
            knownExtensions.put("RightPad", new StringFunctions.RightPad());
            knownExtensions.put("Groups", new groupsBI());
            knownExtensions.put("Matches", new StringFunctions.Matches());
            knownExtensions.put("WordList", new StringFunctions.WordList());
            knownExtensions.put("URL", new StringFunctions.Url());
            knownExtensions.put("Scope", new MacroBuiltins.Scope());
            knownExtensions.put("Namespace", new MacroBuiltins.Namespace());
            knownExtensions.put("Keys", new HashBuiltin.Keys());
            knownExtensions.put("Values", new HashBuiltin.Values());
            register("Reverse", Inner::reverse);
            register("First", Inner::first);
            register("Last", Inner::last);
        }

        private static List<Object> reverse(Object arg) {
            if (arg instanceof List l) {
                List<Object> result = new ArrayList<Object>(l);
                Collections.reverse(result);
                return result;
            }
            if (arg.getClass().isArray()) {
                List<?> l = Arrays.asList(arg);
                List<Object> result = new ArrayList<>(l);
                Collections.reverse(result);
                return result;
            }
            throw new EvaluationException("Expecting list or array");
        }

        private static Object first(Object arg) {
            if (arg instanceof List l) {
                if (l.size() == 0) return null;
                return l.get(0);
            }
            if (arg.getClass().isArray()) {
                if (Array.getLength(arg)==0) return null;
                return Array.get(arg, 0);
            }
            throw new EvaluationException("Expecting list or array");
        }

        private static Object last(Object arg) {
            if (arg instanceof List l) {
                if (l.size() == 0) return null;
                return l.get(l.size()-1);
            }
            if (arg.getClass().isArray()) {
                if (Array.getLength(arg)==0) return null;
                return Array.get(arg, 0);
            }
            throw new EvaluationException("Expecting list or array");
        }
    }
}
