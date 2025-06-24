package org.congocc.core;

import org.congocc.parser.ParseException;
import org.congocc.parser.Token;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import org.congocc.parser.csharp.CSParser;
import org.congocc.parser.python.PythonParser;
import org.congocc.parser.python.ast.Module;
import org.congocc.parser.tree.Assertion;
import org.congocc.parser.tree.CodeInjection;
import org.congocc.parser.tree.EmbeddedCode;
import org.congocc.parser.tree.Failure;
import org.congocc.parser.tree.Lookahead;
import static org.congocc.core.RawCode.ContentType.*;

public class RawCode extends EmptyExpansion implements EmbeddedCode {

    public enum ContentType {
        JAVA_BLOCK,
        JAVA_EXPRESSION,
        CSHARP_BLOCK,
        CSHARP_EXPRESSION,
        PYTHON_BLOCK,
        PYTHON_EXPRESSION
    }

    private boolean alreadyParsed;

    private ParseException parseException;

    private ContentType contentType;

    private Node parsedContent;

    // Only used for parsing a Python block
    private int extraIndent;

    public ParseException getParseException() {
        return this.parseException;
    }

    public void parseContent() {
        if (!alreadyParsed) {
            try {
                switch(getAppSettings().getCodeLang()) {
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

    public String toString() {
        if (contentType == ContentType.PYTHON_BLOCK) {
            parseContent();
            return ((Module) parsedContent).toAltFormat();
        }
        return getRawContent().toString();
    }

    public boolean getHitError() {
        return parseException != null;
    }

    public boolean isAppliesInLookahead() {
        return this.size()>3;
    }

    @Override
    public boolean startsWithGlobalCodeAction(boolean stopAtScanLimit) {
        return isAppliesInLookahead() || getContainingProduction().isOnlyForLookahead();
    }

    void parseJava() {
        Token code = getRawContent();
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), code);
        cccParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        Node parent = this.getParent();
        if (parent instanceof Assertion || parent instanceof Failure || parent instanceof Lookahead) {
            cccParser.EmbeddedJavaExpression();
        }
        else if (parent instanceof CodeInjection) {
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
        Node parent = this.getParent();
        if (parent instanceof Assertion || parent instanceof Failure || parent instanceof Lookahead) {
            csParser.EmbeddedCSharpExpression();
        } else {
            csParser.EmbeddedCSharpBlock();
        }

    }

    void parsePython() {
        String code = get(1).toString();
        code = normalizePythonBlock(code);
        PythonParser pyParser = new PythonParser(getInputSource(), code);
        pyParser.setStartingPos(get(1).getBeginLine(), get(1).getBeginColumn());
        pyParser.setExtraIndent(extraIndent);
        Node parent = this.getParent();
        if (parent instanceof Assertion || parent instanceof Failure || parent instanceof Lookahead) {
            pyParser.EmbeddedPythonExpression();
        }
        parsedContent = pyParser.Module();
    }

    void parsePythonBlock() {
        String code = get(1).toString();
        code = normalizePythonBlock(code);
        PythonParser cccParser = new PythonParser(getInputSource(), code);
        cccParser.setStartingPos(get(1).getBeginLine(), get(1).getBeginColumn());
        //cccParser.setExtraIndent(extraIndent);
        System.out.println("Extra indent is: " +extraIndent);
        parsedContent = cccParser.Module();
    }

    void parsePythonExpression() {
        Token code = getRawContent();
        PythonParser pyParser = new PythonParser(getInputSource(), code);
        pyParser.setLineJoining(true);
        pyParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
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