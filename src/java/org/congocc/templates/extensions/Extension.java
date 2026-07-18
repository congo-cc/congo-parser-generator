package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.nodes.generated.DotExpression;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;


/**
 * An Extension is essentially what was
 * previously called a "builtin" in legacy FreeMarker
 * In Congo Templates, you can "register" your own
 * extensions.
 */
@FunctionalInterface
public interface Extension {
    Object get(DotExpression caller, Environment env);

    /**
     * @return the Extension of the given name
     */
    static Extension find(String name) {
        return Inner.knownExtensions.get(name);
    }

    /**
     * Register your own extension.
     * Note that the method that takes a
     * Function as a parameter may be easier
     * to use in most cases.
     */
    static void register(String name, Extension ext) {
        Inner.knownExtensions.put(name, ext);
    }

    /**
     * Register an extension function. If the name of the
     * extension is "foobar", then lhs.foobar will return
     * func.apply(lhs)
     */
    static void register(String name, Function<Object,?> func) {
        register(name, (exp,env)->func.apply(exp.lhs().evaluate(env)));
    }

    static void remove(String name) {
        Inner.knownExtensions.remove(name);
    }

    static boolean isExtension(String name) {
        return Inner.knownExtensions.get(name) != null;
    }

    static void alias(String altName, String existingName) {
        Extension ext = find(existingName);
        if (ext == null) {
            throw new IllegalArgumentException("No extension " + existingName + " found.");
        }
        register(altName, ext);
    }

    // Using inner class because you can't put a static initializer
    // in an interface
    static class Inner {
        private static Map<String, Extension> knownExtensions = new ConcurrentHashMap<>();
        static
        {
            register("instanceof", new instanceofBI());
            register("C", new cBI());
            register("Eval", new evalBI());
            NumericalCast numericalCast = new NumericalCast();
            register("Floor", numericalCast);
            register("Ceiling", numericalCast);
            register("Round", numericalCast);
            register("Capitalize", new StringTransformations.Capitalize());
            register("CapFirst", new StringTransformations.CapFirst(true));
            register("UncapFirst", new StringTransformations.CapFirst(false));
            register("JavaStringEncode", new StringTransformations.Java());
            register("JavaScriptStringEncode", new StringTransformations.JavaScript());
            register("Chomp", new StringTransformations.Chomp());
            register("HTML", new StringTransformations.Html());
            register("RTF", new StringTransformations.Rtf());
            register("XHTML", new StringTransformations.Xhtml());
            register("Join", new StringFunctions.Join());
            register("Number", new numberBI());
            register("LeftPad", new StringFunctions.LeftPad());
            register("RightPad", new StringFunctions.RightPad());
            register("Groups", new groupsBI());
            register("Matches", new StringFunctions.Matches());
            register("URL", new StringFunctions.Url());
            register("Scope", new MacroBuiltins.Scope());
            register("Namespace", new MacroBuiltins.Namespace());
            register("Source", (caller, env) -> caller.lhs().getSource());
            register("Reverse", Inner::reverse);
            register("First", Inner::first);
            register("Last", Inner::last);
            register("Size", Inner::size);
            register("Keys", Inner::values);
            register("Values", Inner::values);
            register("byte", Inner::byteCast);
            register("double", Inner::doubleCast);
            register("float", Inner::floatCast);
            register("int", Inner::intCast);
            register("long", Inner::longCast);
            register("short", Inner::shortCast);
            register("WordList", Inner::WordList);
            register("XML", Inner::XMLEncode);
            alias("Websafe", "HTML");
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

        private static int size(Object arg) {
            if (arg instanceof Collection c) {
                return c.size();
            }
            if (arg instanceof Map m) {
                return m.size();
            }
            if (arg.getClass().isArray()) {
                return Array.getLength(arg);
            }
            throw new EvaluationException("Expecting collection array");
        }

        private static List<Object> keys(Object arg) {
            if (arg instanceof Map m) {
                return new ArrayList<Object>(m.keySet());
            }
            throw new EvaluationException("Expecting map");
        }

        private static List<Object> values(Object arg) {
            if (arg instanceof Map m) {
                return new ArrayList<Object>(m.values());
            }
            throw new EvaluationException("Expecting map");
        }

        private static int intCast(Object arg) {
            if (arg instanceof Number n) {
                return n.intValue();
            }
            throw new EvaluationException("Expecting number");
        }

        private static long longCast(Object arg) {
            if (arg instanceof Number n) {
                return n.longValue();
            }
            throw new EvaluationException("Expecting number");
        }

        private static float floatCast(Object arg) {
            if (arg instanceof Number n) {
                return n.floatValue();
            }
            throw new EvaluationException("Expecting number");
        }

        private static double doubleCast(Object arg) {
            if (arg instanceof Number n) {
                return n.doubleValue();
            }
            throw new EvaluationException("Expecting number");
        }

        private static byte byteCast(Object arg) {
            if (arg instanceof Number n) {
                return n.byteValue();
            }
            throw new EvaluationException("Expecting number");
        }

        private static short shortCast(Object arg) {
            if (arg instanceof Number n) {
                return n.shortValue();
            }
            throw new EvaluationException("Expecting number");
        }

        private static List<String> WordList(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                StringTokenizer st = new StringTokenizer(s);
                List<String> result = new ArrayList<>();
                while (st.hasMoreTokens()) {
                    result.add(st.nextToken());
                }
                return result;
            }
            throw new EvaluationException("Expecting a string");
        }

        private static String XMLEncode(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.XMLEnc(s);
            }
            throw new EvaluationException("Expecting a string");
        }
    }
}
