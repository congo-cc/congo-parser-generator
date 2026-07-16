package org.congocc.core;

import org.congocc.parser.ParseException;
import org.congocc.parser.Token;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import static org.congocc.parser.Node.CodeLang.*;
import org.congocc.parser.csharp.CSharpParser;
import org.congocc.parser.python.PythonParser;
import org.congocc.parser.python.ast.Module;
import org.congocc.parser.rust.RustParser;
import org.congocc.parser.tree.Assertion;
import org.congocc.parser.tree.JavaCodeInjection1;
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

    public void parseContent() {
        if (!alreadyParsed && !isWrongLanguageIgnore()) {
            try {
                switch(getCodeLang()) {
                    case JAVA -> parseJava();
                    case CSHARP -> parseCSharp();
                    case PYTHON -> parsePython();
                    case RUST -> parseRust();
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

    public String getRawContent() {
        String fullContent = get(0).toString();
        int startIndex = fullContent.indexOf('%')+1;
        if (fullContent.charAt(startIndex) == '%') startIndex++;
        int endIndex = fullContent.lastIndexOf('%');
        if (fullContent.charAt(endIndex-1) == '%') endIndex--;
        return fullContent.substring(startIndex, endIndex);
    }

    public boolean isExpression() {
        Node parent = getParent();
        return  parent instanceof Assertion || parent instanceof Failure
                || parent instanceof Lookahead;
    }

    public boolean useAltPythonFormat() {
        return getCodeLang() == PYTHON && !isExpression();
    }

    public boolean isWrongLanguageIgnore() {
        return getSpecifiedLang() != null && getSpecifiedLang() != getCodeLang();
    }

    public CodeLang getSpecifiedLang() {
        char langChar = ((Token) get(0)).charAt(1);
        if (langChar == '{') langChar = ((Token) get(0)).charAt(2);
        return switch (langChar) {
            case 'P' -> PYTHON;
            case 'C' -> CSHARP;
            case 'J' -> JAVA;
            case 'R' -> RUST;
            default -> null;
        };
    }

    public String toString() {
        if (isWrongLanguageIgnore()) {
            // If this raw code is in the wrong output
            // language, it should just get ignored.
            return "";
        }
        if (useAltPythonFormat()) {
            parseContent();
            return ((Module) parsedContent).toAltFormat();
        }
        return getRawContent();
    }

    public boolean getHitError() {
        return parseException != null;
    }

    public boolean isAppliesInLookahead() {
        Token content = (Token) get(0);
        return content.charAt(content.length()-1) == '#'
               || getContainingProduction() != null
                  && getContainingProduction().isOnlyForLookahead();
    }

    @Override
    public boolean startsWithGlobalCodeAction(boolean stopAtScanLimit) {
        return isAppliesInLookahead();
    }

    void parseRust() {
        String code = getRawContent();
        RustParser rustParser = new RustParser(getInputSource(), code);
        rustParser.setStartingPos(getBeginLine(), getContentBeginColumn());
        rustParser.BlockNoDelimiters();
    }

    private int getContentBeginColumn() {
        return getBeginColumn() + get(0).toString().indexOf('%') + 1;
    }

    void parseJava() {
        String code = getRawContent();
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), code);
        cccParser.setStartingPos(getBeginLine(), getContentBeginColumn());
        if (isExpression()) {
            cccParser.EmbeddedJavaExpression();
        }
        else if (getParent() instanceof JavaCodeInjection1) {
            cccParser.EmbeddedJavaClassOrInterfaceBody();
        }
        else {
           cccParser.EmbeddedJavaBlock();
        }
    }

    void parseCSharp() {
        String code = getRawContent();
        CSharpParser csParser = new CSharpParser(getInputSource(), code);
        csParser.setStartingPos(getBeginLine(), getContentBeginColumn());
        if (isExpression()) {
            csParser.EmbeddedCSharpExpression();
        } else {
            csParser.EmbeddedCSharpBlock();
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
        String code = getRawContent();
        code = normalizePythonBlock(code);
        PythonParser pyParser = new PythonParser(getInputSource(), code);
        pyParser.setStartingPos(getBeginLine(), getContentBeginColumn());
        pyParser.setExtraIndent(extraIndent);
        parsedContent = pyParser.Module();
    }

    void parsePythonExpression() {
        String code = getRawContent();
        PythonParser pyParser = new PythonParser(getInputSource(), code);
        pyParser.setLineJoining(true);
        pyParser.setStartingPos(getBeginLine(), getContentBeginColumn());
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