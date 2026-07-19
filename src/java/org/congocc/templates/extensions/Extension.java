package org.congocc.templates.extensions;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.parser.CTLLexer;
import org.congocc.templates.core.parser.CTLParser;

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
    static void register(String name, Function<Object, ?> func) {
        register(name, (exp, env) -> func.apply(exp.lhs().evaluate(env)));
    }

    /**
     * Remove (or unregister, if you will) a given
     * extension. So, if you think that some standard
     * extension is dangerous or undesirable, just remove it.
     *
     * @param name the name of the extension to be removed.
     */
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
        static {
            NumericalCast numericalCast = new NumericalCast();
            register("Floor", numericalCast);
            register("Ceiling", numericalCast);
            register("Round", numericalCast);
            register("Number", new numberBI());
            register("Groups", new groupsBI());
            register("Matches", new StringFunctions.Matches());
            register("URL", new StringFunctions.Url());
            register("Scope", new MacroBuiltins.Scope());
            register("Namespace", new MacroBuiltins.Namespace());
            register("Source", (caller, env) -> caller.lhs().getSource());
            register("CapFirst", Inner::CapFirst);
            register("UncapFirst", Inner::UncapFirst);
            register("Reverse", Inner::Reverse);
            register("First", Inner::First);
            register("Last", Inner::Last);
            register("Size", Inner::Size);
            register("Keys", Inner::Keys);
            register("Values", Inner::Values);
            register("Capitalize", Inner::Capitalize);
            register("Chomp", Inner::Chomp);
            register("WordList", Inner::WordList);
            register("JavaStringEncode", Inner::JavaStringEncode);
            register("JavaScriptStringEncode", Inner::JavaScriptStringEncode);
            register("HTML", Inner::HTMLEncode);
            register("XML", Inner::XMLEncode);
            register("XHTML", Inner::XHTMLEncode);
            register("RTF", Inner::RTFEncode);
            register("Eval", Inner::Eval);
            register("C", Inner::C);
            register("byte", Inner::byteCast);
            register("double", Inner::doubleCast);
            register("float", Inner::floatCast);
            register("int", Inner::intCast);
            register("long", Inner::longCast);
            register("short", Inner::shortCast);
            register("instanceof", Inner::IsInstance);
            alias("Websafe", "HTML");
        }

        private static List<Object> Reverse(Object arg) {
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

        private static Object First(Object arg) {
            if (arg instanceof List l) {
                if (l.size() == 0)
                    return null;
                return l.get(0);
            }
            if (arg.getClass().isArray()) {
                if (Array.getLength(arg) == 0)
                    return null;
                return Array.get(arg, 0);
            }
            throw new EvaluationException("Expecting list or array");
        }

        private static Object Last(Object arg) {
            if (arg instanceof List l) {
                if (l.size() == 0)
                    return null;
                return l.get(l.size() - 1);
            }
            if (arg.getClass().isArray()) {
                if (Array.getLength(arg) == 0)
                    return null;
                return Array.get(arg, 0);
            }
            throw new EvaluationException("Expecting list or array");
        }

        private static int Size(Object arg) {
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

        private static List<Object> Keys(Object arg) {
            if (arg instanceof Map m) {
                return new ArrayList<Object>(m.keySet());
            }
            throw new EvaluationException("Expecting map");
        }

        private static List<Object> Values(Object arg) {
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

        private static String Capitalize(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.capitalize(s);
            }
            throw new EvaluationException("Expecting a string");
        }

        private static String Chomp(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.chomp(s);
            }
            throw new EvaluationException("Expecting a string");
        }

        private static String CapFirst(Object arg) {
            return capUncapFirst(arg, true);
        }

        private static String UncapFirst(Object arg) {
            return capUncapFirst(arg, false);
        }

        private static String capUncapFirst(Object arg, boolean cap) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                boolean justcopy = false;
                StringBuilder buf = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    if (justcopy) {
                        buf.append(ch);
                        continue;
                    }
                    if (!Character.isWhitespace(ch)) {
                        if (cap) ch = Character.toUpperCase(ch);
                        else ch = Character.toLowerCase(ch);
                        justcopy = true;
                    }
                    buf.append(ch);
                }
                return buf.toString();
            }
            throw new EvaluationException("Expecting a string");
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

        private static String HTMLEncode(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.HTMLEnc(s);
            }
            throw new EvaluationException("Expecting a string");
        }

        private static String XHTMLEncode(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.XHTMLEnc(s);
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

        private static String JavaStringEncode(Object arg) {
            return StringUtil.javaStringEncode(arg.toString());
            /*
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.javaStringEncode(s);
            }
            throw new EvaluationException("Expecting a string");*/
        }

        private static String JavaScriptStringEncode(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.javaScriptStringEnc(s);
            }
            throw new EvaluationException("Expecting a string");
        }

        private static String RTFEncode(Object arg) {
            if (arg instanceof CharSequence) {
                String s = arg.toString();
                return StringUtil.RTFEnc(s);
            }
            throw new EvaluationException("Expecting a string");
        }

        public static Object Eval(DotExpression caller, Environment env) {
            String input = "(" + caller.lhs().evaluate(env) + ")";
            CTLLexer tokenSource = new CTLLexer("input", input, CTLLexer.LexicalState.EXPRESSION, caller.getBeginLine(),
                    caller.getBeginColumn());
            CTLParser parser = new CTLParser(tokenSource);
            parser.setTemplate(caller.getTemplate());
            var exp = parser.Expression();
            return exp.evaluate(env);
        }

        public static Function<Object,Boolean> IsInstance(DotExpression caller, Environment env) {
            Object object = caller.lhs().evaluate(env);
            return arg -> {
                Class<?> clazz = null;
                if (arg instanceof Class) {
                    clazz = (Class<?>) arg;
                }
                else if (arg instanceof CharSequence cs) {
                    try {
                        clazz = Class.forName(cs.toString());
                    } catch (Exception e) {
                        throw new EvaluationException(e);
                    }
                }
                else {
                    throw new EvaluationException("Expecting a class or the name of a class");
                }
                return clazz.isInstance(object);
            };
        }

        private static Object C(DotExpression caller, Environment env) {
            Object arg = caller.lhs().evaluate(env);
            if (arg instanceof Number num) {
                if (num instanceof Integer) {
                    // We accelerate this fairly common case
                    return num.toString();
                } else {
                    return (env == null ? Environment.getNewCNumberFormat() : env.getCNumberFormat()).format(num);
                }
            }
            else {
                throw new EvaluationException("Expecting a number on the left side of ?c");
            }
        }


    }
}
