package org.congocc.templates.extensions;

import java.io.UnsupportedEncodingException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.function.Function;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.EvaluationException;
import org.congocc.templates.core.nodes.generated.DotExpression;
import org.congocc.templates.core.reflection.JavaMethodCall;
import org.congocc.templates.core.reflection.VarArgsFunction;

import static org.congocc.templates.core.Wrap.*;

/**
 * Implementations of ?substring and other
 * standard functions that operate on strings
 */
public abstract class StringFunctions extends ExpressionEvaluatingExtension {

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
    public Object get(Environment env, DotExpression caller, Object model) {
        String string = asString(model);
        return apply(string, env, caller);
    }

    public abstract Object apply(final String string, final Environment env, final DotExpression callingExpression);

    public static class Matches extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, DotExpression caller) {
            return new MatcherBuilder(string);
        }
    }

    public static class Url extends StringFunctions {
        @Override
        public Object apply(String string, Environment env, DotExpression caller) {
            return new urlBIResult(string, env);
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

    static class RegexMatchModel extends AbstractList<Object> {
        Matcher matcher;
        String input;
        final boolean matches;
        List<String> groups;
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
                groups = new AbstractList<String>() {
                    public int size() {
                        try {
                            return matcher.groupCount() + 1;
                        }
                        catch (Exception e) {
                            throw new EvaluationException(e);
                        }
                    }
                    public String get(int i) {
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
