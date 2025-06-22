package org.congocc.core;

import org.congocc.parser.Node;
import org.congocc.parser.ParseException;
import static org.congocc.parser.Token.TokenType.UNPARSED_CONTENT;

import javax.net.ssl.CertPathTrustManagerParameters;

import org.congocc.parser.CongoCCParser;
import org.congocc.parser.csharp.CSParser;
import org.congocc.parser.python.PythonParser;
import org.congocc.parser.tree.Assertion;
import org.congocc.parser.tree.Lookahead;

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

    public ParseException getParseException() {
        return this.parseException;
    }

    public ContentType getContentType() {
        if (contentType == null) {
            String lang = getAppSettings().getCodeLang();
            switch (lang) {
                case "java" -> {
                    if (getParent() instanceof Assertion || getParent() instanceof Lookahead) {
                        contentType = ContentType.JAVA_EXPRESSION;
                    } else {
                        contentType = ContentType.JAVA_BLOCK;
                    }
                }
                case "csharp" -> {
                    if (getParent() instanceof Assertion || getParent() instanceof Lookahead) {
                        contentType = ContentType.CSHARP_EXPRESSION;
                    } else {
                        contentType = ContentType.CSHARP_BLOCK;
                    }
                }
                case "python" -> {
                    if (getParent() instanceof Assertion || getParent() instanceof Lookahead) {
                        contentType = ContentType.PYTHON_EXPRESSION;
                    } else {
                        contentType = ContentType.PYTHON_BLOCK;
                    }
                }
            }
        }
        return contentType;
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

    public String getRawContent() {
        return get(1).toString();
    }

    public boolean getHasError() {
        return parseException != null;
    }

    void parseJavaBlock() {
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), (CharSequence) get(1));
        cccParser.setStartingPos(getBeginLine(), getBeginColumn()+1);
        cccParser.EmbeddedJavaBlock();
    }

    void parseJavaExpression() {
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), (CharSequence) get(1));
        cccParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        cccParser.EmbeddedJavaExpression();
    }

    void parseCSharpBlock() {
        CSParser csParser = new CSParser(getInputSource(), (CharSequence) get(1));
        csParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        csParser.EmbeddedCSharpBlock();
    }

    void parseCSharpExpression() {
        CSParser csParser = new CSParser(getInputSource(), (CharSequence) get(1));
        csParser.setStartingPos(getBeginLine(), getBeginColumn() + 2);
        csParser.EmbeddedCSharpExpression();
    }

    void parsePythonBlock() {
        PythonParser cccParser = new PythonParser(getInputSource(), getRawContent());
        cccParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        cccParser.EmbeddedPythonBlock();
    }

    void parsePythonExpression() {
        String content = getSource();
        content = content.substring(2, content.length()-2);
        PythonParser pyParser = new PythonParser(getInputSource(), content);
        pyParser.setLineJoining(true);
        pyParser.setStartingPos(getBeginLine(), getBeginColumn() + 2);
        pyParser.EmbeddedPythonExpression();
    }

}