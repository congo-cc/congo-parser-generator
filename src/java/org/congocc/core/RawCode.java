package org.congocc.core;

import org.congocc.parser.ParseException;
import org.congocc.parser.Token;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import org.congocc.parser.csharp.CSParser;
import org.congocc.parser.python.PythonParser;
import org.congocc.parser.python.ast.EmbeddedPythonBlock;
import org.congocc.parser.python.ast.Module;
import org.congocc.parser.tree.Assertion;
import org.congocc.parser.tree.Failure;
import org.congocc.parser.tree.Lookahead;
import static org.congocc.core.RawCode.ContentType.*;
import static org.congocc.parser.Token.TokenType.HASH;

public class RawCode extends EmptyExpansion {

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

    public ParseException getParseException() {
        return this.parseException;
    }

    public ContentType getContentType() {
        if (contentType != null) return contentType;
        String lang = getAppSettings().getCodeLang();
        boolean isExpression = getParent() instanceof Assertion || getParent() instanceof Lookahead || getParent() instanceof Failure;
        return contentType = switch (lang) {
            case "java" -> isExpression ? JAVA_EXPRESSION : JAVA_BLOCK;
            case "csharp" -> isExpression ? CSHARP_EXPRESSION : CSHARP_BLOCK;
            case "python" -> isExpression ? PYTHON_BLOCK : PYTHON_BLOCK;
            default -> throw new IllegalArgumentException("Expecting one of java, csharp, or python");
        };
    }

    public void setContentType(ContentType type) {
        this.contentType = type;
    }

    public void parseContent() {
        if (!alreadyParsed) {
            try {
                switch(getContentType()) {
                    case JAVA_BLOCK -> parseJavaBlock();
                    case JAVA_EXPRESSION -> parseJavaExpression();
                    case CSHARP_BLOCK -> parseCSharpBlock();
                    case CSHARP_EXPRESSION -> parseCSharpExpression();
                    case PYTHON_BLOCK -> parsePythonBlock();
                    case PYTHON_EXPRESSION -> parsePythonExpression();
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

    void parseJavaBlock() {
        Token code = getRawContent();
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), code);
        cccParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        cccParser.EmbeddedJavaBlock();
    }

    void parseJavaExpression() {
        Token code = getRawContent();
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), code);
        cccParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        cccParser.EmbeddedJavaExpression();
    }

    void parseCSharpBlock() {
        Token code = getRawContent();
        CSParser csParser = new CSParser(getInputSource(), code);
        csParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        csParser.EmbeddedCSharpBlock();
    }

    void parseCSharpExpression() {
        Token code = getRawContent();
        CSParser csParser = new CSParser(getInputSource(), code);
        csParser.setStartingPos(getBeginLine(), code.getBeginColumn());
        csParser.EmbeddedCSharpExpression();
    }

    void parsePythonBlock() {
        Token code = getRawContent();
        PythonParser cccParser = new PythonParser(getInputSource(), code);
        cccParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
        parsedContent = cccParser.Module();
        parsedContent.dump();
    }

    void parsePythonExpression() {
        Token code = getRawContent();
        PythonParser pyParser = new PythonParser(getInputSource(), code);
        pyParser.setLineJoining(true);
        pyParser.setStartingPos(code.getBeginLine(), code.getBeginColumn());
    }

}