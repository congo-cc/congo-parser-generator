package org.congocc.core;

import org.congocc.parser.Node;
import org.congocc.parser.ParseException;
import static org.congocc.parser.Token.TokenType.UNPARSED_CONTENT;
import org.congocc.parser.csharp.CSParser;
import org.congocc.parser.python.PythonParser;

public class UnparsedCodeBlock extends EmptyExpansion {

    public enum Type {
        CSHARP_BLOCK,
        CSHARP_EXPRESSION,
        PYTHON_BLOCK,
        PYTHON_EXPRESSION
    }

    private boolean expanded;
    // private Type type;
    private ParseException parseException;

    // public void setType(Type type) {this.type = type;}

    private Node parseContent(CharSequence input) {
        return null;
    }

    private void expand() {
    }

    public boolean hasError() {
        return parseException != null;
    }

    String getContent() {
        Node uc = firstChildOfType(UNPARSED_CONTENT);
        return uc == null ? "" : uc.getSource();
    }

    Node parsePythonExpression() {
        PythonParser pyParser = new PythonParser(getInputSource(), getContent());
        pyParser.setStartingPos(getBeginLine(), getBeginColumn() + 2);
        pyParser.Expression();
        return pyParser.peekNode();
    }

    Node parseCSharpBlock() {
        CSParser csParser = new CSParser(getInputSource(), getContent());
        csParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        try {
           return csParser.InjectionBody();
        } catch (ParseException pe) {
            this.parseException = pe;
        }
        return null;
    }
}