package org.congocc.templates.builtins;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.function.Function;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.BuiltInExpression;
import org.congocc.templates.core.variables.*;
import org.congocc.templates.TemplateBoolean;
import org.congocc.templates.TemplateSequence;
import org.congocc.templates.utility.StringUtil;

import static org.congocc.templates.core.variables.Wrap.*;

/**
 * Implementations of ?substring and other 
 * standard functions that operate on strings
 */
public abstract class StringFunctions extends ExpressionEvaluatingBuiltIn {

    static private HashMap<String, Pattern> patternLookup = new HashMap<String, Pattern>();
    static private LinkedList<String> patterns = new LinkedList<String>();
    static private final int PATTERN_CACHE_SIZE=100;

    static Pattern getPattern(String patternString, String flagString) {
        int flags = 0;
        String patternKey = patternString + (char) 0 + flagString;
        Pattern result = patternLookup.get(patternKey);
        if (result != null) {
            return result;
        }
        if (flagString == null || flagString.length() == 0) {
            try {
                result = Pattern.compile(patternString);
            } catch (PatternSyntaxException e) {
                throw new EvaluationException(e);
            }
        }
        else {
            if (flagString.indexOf('i') >=0) {
                flags = flags | Pattern.CASE_INSENSITIVE;
            }
            if (flagString.indexOf('m') >=0) {
                flags = flags | Pattern.MULTILINE;
            }
            if (flagString.indexOf('c') >=0) {
                flags = flags | Pattern.COMMENTS;
            }
            if (flagString.indexOf('s') >=0) {
                flags = flags | Pattern.DOTALL;
            }
            try {
                result = Pattern.compile(patternString, flags);
            } catch (PatternSyntaxException e) {
                throw new EvaluationException(e);
            }
        }
        synchronized (patterns) {
            patterns.add(patternKey);
            patternLookup.put(patternKey, result);
            if (patterns.size() > PATTERN_CACHE_SIZE) {
                Object first = patterns.removeFirst();
                patterns.remove(first);
            }
        }
        return result;
    }

    @Override 
    public Object get(Environment env, BuiltInExpression caller, Object model) {
        String string = asString(model);
        return apply(string, env, caller);
    }
    
    public abstract Object apply(final String string, final Environment env, final BuiltInExpression callingExpression);
    
    public static class Substring extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new JavaMethodCall(string, "substring");
        }
    }

    public static class Replace extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new ReplaceMethod(string);
        }
    }

    public static class Join extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new JavaMethodCall(string, "join");
        }
    }

    public static class Split extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new JavaMethodCall(string, "split");
        }
    }

    public static class StartsWith extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new JavaMethodCall(string, "startsWith");
        }
    }

    public static class EndsWith extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new JavaMethodCall(string, "endsWith");
        }
    }

    public static class Matches extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new MatcherBuilder(string);
        }
    }

    public static class IndexOf extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new IndexOfMethod(string, false);
        }
    }

    public static class LastIndexOf extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new IndexOfMethod(string, true);
        }
    }

    public static class Contains extends StringFunctions {
        @Override
        public Function<String,Boolean> apply(String string, Environment env, BuiltInExpression caller) {
            return s->string.indexOf(s) >=0;
        }
    }

    public static class LeftPad extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new LeftPadMethod(string);
        }
    }

    public static class RightPad extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new RightPadMethod(string);
        }
    }

    public static class WordList extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            StringTokenizer st = new StringTokenizer(string);
            List<String> result = new ArrayList<>();
            while (st.hasMoreTokens()) {
                result.add(st.nextToken());
            }
            return result;
        }
    }

    public static class Url extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, BuiltInExpression caller) {
            return new urlBIResult(string, env);
        }
    }

    static class ReplaceMethod implements VarArgsFunction<String> {
        String string;

        ReplaceMethod(String string) {
            this.string = string;
        }

        public String apply(Object... args) {
            if (args.length < 2 || args.length >3 ) {
                throw new EvaluationException(
                "?replace(...) needs 2 or 3 arguments.");
            }
            String first = (String) args[0];
            String second = (String) args[1];
            String flags = args.length == 3 ? (String) args[2] : "";
            boolean caseInsensitive = flags.indexOf('i') >=0;
            boolean useRegexp = flags.indexOf('r') >=0;
            boolean firstOnly = flags.indexOf('f') >=0;
            String result = null;
            if (!useRegexp) {
                result = StringUtil.replace(string, first, second, caseInsensitive, firstOnly);
            } else {
                Pattern pattern = getPattern(first, flags);
                Matcher matcher = pattern.matcher(string);
                result = firstOnly ? matcher.replaceFirst(second) : matcher.replaceAll(second);
            } 
            return result;
        }
    }

    static class MatcherBuilder implements VarArgsFunction<Object> {

        private String matchString;

        MatcherBuilder(String matchString) {
            this.matchString = matchString;
        }

        public Object apply(Object... args) {
            int numArgs = args.length;
            if (numArgs == 0) {
                throw new EvaluationException("Expecting at least one argument");
            }
            if (numArgs > 2) {
                throw new EvaluationException("Expecting at most two argumnets");
            }
            String patternString = (String) args[0];
            String flagString = (numArgs >1) ? (String) args[1] : "";
            Pattern pattern = getPattern(patternString, flagString);
            Matcher matcher = pattern.matcher(matchString);
            return new RegexMatchModel(matcher, matchString);
        }
    }


    static class RegexMatchModel 
    implements TemplateBoolean, TemplateSequence {
        Matcher matcher;
        String input;
        final boolean matches;
        TemplateSequence groups;
        private ArrayList<Object> data;

        RegexMatchModel(Matcher matcher, String input) {
            this.matcher = matcher;
            this.input = input;
            this.matches = matcher.matches();
        }

        public boolean getAsBoolean() {
            return matches;
        }

        public Object get(int i) {
            if (data == null) initSequence();
            return data.get(i);
        }

        public int size() {
            if (data == null) initSequence();
            return data.size();
        }

        private void initSequence() {
            data = new ArrayList<Object>();
            Iterator<Object> it = iterator();
            while (it.hasNext()) {
                data.add(it.next());
            }
        }

        public Object getGroups() {
            if (groups == null) {
                groups = new TemplateSequence() {
                    public int size() {
                        try {
                            return matcher.groupCount() + 1;
                        }
                        catch (Exception e) {
                            throw new EvaluationException(e);
                        }
                    }
                    public Object get(int i) {
                        try {
                            return matcher.group(i);
                        }
                        catch (Exception e) {
                            throw new EvaluationException(e);
                        }
                    }
                };
            }
            return groups;
        }

        public Iterator<Object> iterator() {
            matcher.reset();
            return new Iterator<Object>() {
                boolean hasFindInfo = matcher.find();

                public boolean hasNext() {
                    return hasFindInfo;
                }

                public Object next() {
                    if (!hasNext()) throw new EvaluationException("No more matches");
                    Match result = new Match();
                    hasFindInfo = matcher.find();
                    return result;
                }
            };
        }

        class Match {
            String match;
            List<String> subs = new ArrayList<>();

            Match() {
                match = input.substring(matcher.start(), matcher.end());
                for (int i=0; i< matcher.groupCount() + 1; i++) {
                    subs.add(matcher.group(i));
                }
            }
            public String toString() {
                return match;
            }
        }
    }

    static class LeftPadMethod implements VarArgsFunction<String> {
        private String string;

        LeftPadMethod(String s) {
            this.string = s;
        }

        public String apply(Object... args) {
            int ln  = args.length;
            if (ln == 0) {
                throw new EvaluationException(
                "?left_pad(...) expects at least 1 argument.");
            }
            if (ln > 2) {
                throw new EvaluationException(
                "?left_pad(...) expects at most 2 arguments.");
            }
            Object obj = args[0];
            if (!(obj instanceof Number)) {
                throw new EvaluationException(
                        "?left_pad(...) expects a number as "
                        + "its 1st argument.");
            }
            int width = ((Number)obj).intValue();

            if (ln > 1) {
                obj = args[1];
                if (!(obj instanceof CharSequence)) {
                    throw new EvaluationException(
                            "?left_pad(...) expects a string as "
                            + "its 2nd argument.");
                }
                String filling = asString(obj);
                try {
                    return StringUtil.leftPad(string, width, filling);
                } catch (IllegalArgumentException e) {
                    if (filling.length() == 0) {
                        throw new EvaluationException(
                                "The 2nd argument of ?left_pad(...) "
                                + "can't be a 0 length string.");
                    } else {
                        throw new EvaluationException(
                                "Error while executing the ?left_pad(...) "
                                + "built-in.", e);
                    }
                }
            } else {
                return StringUtil.leftPad(string, width);
            }
        }        
    }

    static class RightPadMethod implements VarArgsFunction<String> {
        private String string;

        private RightPadMethod(String string) {
            this.string = string;
        }

        public String apply(Object... args) {
            int ln  = args.length;
            if (ln == 0) {
                throw new EvaluationException(
                "?right_pad(...) expects at least 1 argument.");
            }
            if (ln > 2) {
                throw new EvaluationException(
                "?right_pad(...) expects at most 2 arguments.");
            }
            Object obj = args[0];
            if (!(obj instanceof Number)) {
                throw new EvaluationException(
                        "?right_pad(...) expects a number as "
                        + "its 1st argument.");
            }
            int width = ((Number)obj).intValue();
            if (ln > 1) {
                obj = args[1];
                if (!(obj instanceof CharSequence)) {
                    throw new EvaluationException(
                            "?right_pad(...) expects a string as "
                            + "its 2nd argument.");
                }
                String filling = asString(obj);
                try {
                    return StringUtil.rightPad(string, width, filling);
                } catch (IllegalArgumentException e) {
                    if (filling.length() == 0) {
                        throw new EvaluationException(
                                "The 2nd argument of ?right_pad(...) "
                                + "can't be a 0 length string.");
                    } else {
                        throw new EvaluationException(
                                "Error while executing the ?right_pad(...) "
                                + "built-in.", e);
                    }
                }
            } else {
                return StringUtil.rightPad(string, width);
            }
        }

    }

    static class urlBIResult implements Function<String,String> {

        private final String target;
        private final Environment env;
        private String cachedResult;

        private urlBIResult(String target, Environment env) {
            this.target = target;
            this.env = env;
        }

        public String toString() {
            if (cachedResult == null) {
                String cs = env.getEffectiveURLEscapingCharset();
                if (cs == null) {
                    throw new EvaluationException(
                            "To do URL encoding, the framework that encloses "
                            + "the template engine must specify the output encoding "
                            + "or the URL encoding charset, so ask the "
                            + "programmers to fix it. Or, as a last chance, "
                            + "you can set the url_encoding_charset setting in "
                            + "the template, e.g. "
                            + "<#setting url_escaping_charset='ISO-8859-1'>, or "
                            + "give the charset explicitly to the buit-in, e.g. "
                            + "foo?url('ISO-8859-1').");
                }
                try {
                    cachedResult = StringUtil.URLEnc(target, cs);
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

    static class IndexOfMethod implements VarArgsFunction<Integer> {
        private final String s;
        private final boolean reverse;

        IndexOfMethod(String s, boolean reverse) {
            this.s = s;
            this.reverse = reverse;
        }

        private String getName() {
            return "?" + (reverse ? "last_" : "") + "index_of";
        }
        
        public Integer apply(Object... args) {
            Object obj;
            String sub;
            int fidx;

            int ln  = args.length;
            if (ln == 0) {
                throw new EvaluationException(getName() + "(...) expects at least one argument.");
            }
            if (ln > 2) {
                throw new EvaluationException(getName() + "(...) expects at most two arguments.");
            }

            obj = args[0];       
            if (!(obj instanceof CharSequence)) {
                throw new EvaluationException(getName() + "(...) expects a string as its first argument.");
            }
            sub = asString(obj);

            if (ln > 1) {
                obj = args[1];
                if (!(obj instanceof Number)) {
                    throw new EvaluationException(getName() + "(...) expects a number as "
                            + "its second argument.");
                }
                fidx = ((Number)obj).intValue();
            } else {
                fidx = 0;
            }
            int index;
            if (reverse) {
                if (ln >1)
                    index = s.lastIndexOf(sub, fidx); 
                else 
                    index = s.lastIndexOf(sub);
            } else {
                index = s.indexOf(sub, fidx);
            }
            return index;
        }
    }
}
