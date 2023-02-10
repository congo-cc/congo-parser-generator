package org.congocc.core;

import org.congocc.parser.Node;
import static org.congocc.parser.Token.TokenType.UNPARSED_CONTENT;
import org.congocc.parser.csharp.CSharpParser;
import org.congocc.parser.csharp.CSharpLexer;
import org.congocc.parser.python.PythonLexer;

public class UnparsedCodeBlock extends EmptyExpansion {

    public enum Type {
        CSHARP_BLOCK,
        CSHARP_EXPRESSION,
        PYTHON_BLOCK,
        PYTHON_EXPRESSION
    }

    private boolean expanded;
    private Type type;

    public void setType(Type type) {this.type = type;}

    private Node parseContent(CharSequence input) {
        return null;
    }

    private void expand() {
    }

    String getContent() {
        Node uc = firstChildOfType(UNPARSED_CONTENT);
        return uc == null ? "" : uc.getSource();
    }

    Node parseCSharpBlock() {
        //CSharpLexer csLexer = new CSharpLexer(getInputSource())
        CSharpParser csParser = new CSharpParser(getInputSource(), getContent());
//        csParser.setStartingPos(getBeginLine(), getBeginColumn()+2);
        csParser.Block();
        return csParser.rootNode();
    }
}