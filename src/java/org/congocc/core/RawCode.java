package org.congocc.core;

import org.congocc.parser.ParseException;
import org.congocc.parser.Token;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import static org.congocc.parser.Node.CodeLang.*;
import org.congocc.parser.csharp.CSParser;
import org.congocc.parser.python.PythonParser;
import org.congocc.parser.python.ast.Module;
import org.congocc.parser.tree.Assertion;
import org.congocc.parser.tree.CodeInjection;
import org.congocc.parser.tree.EmbeddedCode;
import org.congocc.parser.tree.Failure;
import org.congocc.parser.tree.Lookahead;

public class RawCode extends EmptyExpansion implements EmbeddedCode {

    private boolean alreadyParsed;

    private ParseException parseException;

    private Node parsedContent;

    // Only used for parsing a Python block
    private int extraIndent;

    public ParseException getParseException() {
        return this.parseException;
    }

    public Node getParsedContent() {
        return parsedContent;
    }

    public void parseContent() {
        if (!alreadyParsed && !wrongLanguageIgnore()) {
            try {
                switch(getCodeLang()) {
                    case JAVA -> parseJava();
                    case CSHARP -> parseCSharp();
                    case PYTHON -> parsePython();
                }
            } catch(ParseException pe) {
                this.parseException = pe;
            }
        }
        alreadyParsed = true;
    }

    public boolean isAlreadyParsed() {
        return alreadyParsed;
    }

    public Token getRawContent() {
        return (Token) get(1);
    }

    public boolean isExpression() {
        Node parent = getParent();
        return  parent instanceof Assertion || parent instanceof Failure
                || parent instanceof Lookahead;
    }

    public boolean useAltPythonFormat() {
        return getCodeLang() == PYTHON && !isExpression();
    }

    public boolean wrongLanguageIgnore() {
        return specifiedLanguage() != null && specifiedLanguage() != getCodeLang();
    }

    public CodeLang specifiedLanguage() {
        char initialChar = ((Token) get(1)).charAt(0);
        return switch (initialChar) {
            case 'P' -> PYTHON;
            case 'C' -> CSHARP;
            case 'J' -> JAVA;
            default -> null;
        };
    }

    public String toString() {
        if (wrongLanguageIgnore()) {
            return switch(specifiedLanguage()) {
                case PYTHON -> "\n# No output. This is Python code.";
                case JAVA -> "// No output. This is Java code.";
                case CSHARP -> "// No output. This is CSharp code.";
            };
        }
        if (useAltPythonFormat()) {
            parseContent();
            return ((Module) parsedContent).toAltFormat();
        }
        return getRawContent().toString();
    }

    public boolean getHitError() {
        return parseException != null;
    }

    public boolean isAppliesInLookahead() {
        return this.size()>3 || getContainingProduction() != null && getContainingProduction().isOnlyForLookahead();
    }

    @Override
    public boolean startsWithGlobalCodeAction(boolean stopAtScanLimit) {
        return isAppliesInLookahead();
    }

    void parseJava() {
        Token code = getRawContent();
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), code);
        cccParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        if (isExpression()) {
            cccParser.EmbeddedJavaExpression();
        }
        else if (getParent() instanceof CodeInjection) {
            cccParser.EmbeddedJavaClassOrInterfaceBody();
        }
        else {
           cccParser.EmbeddedJavaBlock();
        }
    }

    void parseCSharp() {
        Token code = getRawContent();
        CSParser csParser = new CSParser(getInputSource(), code);
        csParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        if (isExpression()) {
            parsedContent = csParser.EmbeddedCSharpExpression();
        } else {
            parsedContent = csParser.EmbeddedCSharpBlock();
        }
    }

    void parsePython() {
        Node parent = this.getParent();
        if (parent instanceof Assertion || parent instanceof Failure || parent instanceof Lookahead) {
             parsePythonExpression();
        } else {
            parsePythonBlock();
        }
    }

    void parsePythonBlock() {
        String code = get(1).toString();
        code = normalizePythonBlock(code);
        PythonParser cccParser = new PythonParser(getInputSource(), code);
        cccParser.setStartingPos(get(1).getBeginLine(), get(1).getBeginColumn());
        System.out.println("Extra indent is: " +extraIndent);
        parsedContent = cccParser.Module();
    }

    void parsePythonExpression() {
        Token code = getRawContent();
        PythonParser pyParser = new PythonParser(getInputSource(), code);
        pyParser.setLineJoining(true);
        pyParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        parsedContent = pyParser.EmbeddedPythonExpression();
    }

    private String normalizePythonBlock(String input) {
        var minimalIndent = input.lines().mapToInt(s->indentLevel(s)).min();
        if (minimalIndent.isEmpty()) return input;
        extraIndent = minimalIndent.getAsInt();
        return input.indent(-extraIndent);
    }

    private int indentLevel(String s) {
        for (int i=0; i<s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                if (c == '#') return Integer.MAX_VALUE;
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}