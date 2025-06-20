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

    private Node parsedContent;

    private boolean parsed;

    private ParseException parseException;

    private ContentType contentType = ContentType.JAVA_BLOCK;

    public ParseException getParseException() {
        return this.parseException;
    }

    public ContentType getContentType() {
        if (contentType != null) {
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
        try {
            this.parsedContent = switch(contentType) {
                case JAVA_BLOCK -> parseJavaBlock();
                case JAVA_EXPRESSION -> parseJavaExpression();
                case CSHARP_BLOCK -> parseCSharpBlock();
                case CSHARP_EXPRESSION -> parseCSharpExpression();
                case PYTHON_BLOCK -> parsePythonBlock();
                case PYTHON_EXPRESSION -> parsePythonExpression();
            };
        } catch(ParseException pe) {
            this.parseException = pe;
        }
        this.parsed = true;
    }

    public boolean isProcessed() {
        return parseException != null || parsedContent != null;
    }

    public Node getParsedContent() {
        return parsedContent;
    }

    public String toString() {
        if (!parsed) parseContent();
        return parsedContent.toString();
    }

    public boolean hasError() {
        return parseException != null;
    }

    String getContent() {
        Node uc = firstChildOfType(UNPARSED_CONTENT);
        return uc == null ? "" : uc.getSource();
    }

    Node parseJavaBlock() {
        String content = getSource();
        content = content.substring(1,content.length()-1);
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), content);
        cccParser.setStartingPos(getBeginLine(), getBeginColumn()+1);
        cccParser.Block();
        return cccParser.rootNode();
    }

    Node parseJavaExpression() {
        String content = getSource();
        content = content.substring(2, content.length()-2);
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), getContent());
        cccParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        return cccParser.Expression();
    }
    
    Node parseCSharpBlock() {
        String content = getSource();
        content = content.substring(1,content.length()-1);
        CSParser csParser = new CSParser(getInputSource(), content);
        csParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        try {
           return csParser.InjectionBody();
        } catch (ParseException pe) {
            this.parseException = pe;
        }
        return null;
    }

    Node parseCSharpExpression() {
        String content = getSource();
        content = content.substring(2, content.length()-2);
        CSParser csParser = new CSParser(getInputSource(), content);
        csParser.setStartingPos(getBeginLine(), getBeginColumn() + 2);
        csParser.Expression();
        return csParser.peekNode();
    }

    Node parsePythonBlock() {
        String content = getSource();
        content = content.substring(2,content.length()-2);
        CongoCCParser cccParser = new CongoCCParser(getInputSource(), content);
        cccParser.setStartingPos(getBeginLine(), getBeginColumn()+1);
        cccParser.Block();
        return cccParser.rootNode();
    }

    Node parsePythonExpression() {
        String content = getSource();
        content = content.substring(2, content.length()-2);
        PythonParser pyParser = new PythonParser(getInputSource(), content);
        pyParser.setLineJoining(true);
        pyParser.setStartingPos(getBeginLine(), getBeginColumn() + 2);
        pyParser.Expression();
        return pyParser.peekNode();
    }

}