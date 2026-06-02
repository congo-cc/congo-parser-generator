package org.congocc.codegen.python;

import org.congocc.codegen.AbstractCodeFormatter;
import org.congocc.parser.*;
import org.congocc.parser.python.PythonToken;
import org.congocc.parser.python.ast.*;

public class PyFormatter extends AbstractCodeFormatter {
    private int bracketNesting, parenthesesNesting, braceNesting;
    private boolean altFormat;

    public String format(Node node, boolean altFormat) {
        this.altFormat = altFormat;
        if (altFormat) {
            buffer.append("# explicitdedent:on\n");
        }
        visit(node);
        return getText();
    }

    public String getText() {
        String result = super.getText();
        if (altFormat) result+="# explicitdedent:restore\n";
        return result;
    }

    private boolean lineJoining() {
        assert bracketNesting >=0;
        assert parenthesesNesting >=0;
        assert braceNesting >=0;
        return bracketNesting>0 || parenthesesNesting>0 || braceNesting>0;
    }

    void visit(PythonToken tok) {
        switch (tok.getType()) {
            case LBRACKET -> ++bracketNesting;
            case RBRACKET -> --bracketNesting;
            case LPAREN -> ++parenthesesNesting;
            case RPAREN -> --parenthesesNesting;
            case LBRACE -> ++braceNesting;
            case RBRACE -> --braceNesting;
            default -> {}
        }
        if (!lineJoining() && tok.startsLine()) {
            appendIndentation();;
        }
        defaultTokenOutput(tok);
    }

    void visit(Comment tok) {
        if (tok.toString()
           .substring(1)
           .trim()
           .toLowerCase().startsWith("explicitdedent:")) {
            return;
        }
        if (tok.startsLine()) {
            appendIndentation();
        }
        buffer.append(tok);
        buffer.append(eol);
    }

    void visit(IndentToken tok) {
        currentIndentation += indentAmount;
        assert currentIndentation >=0;
    }

    void visit(DedentToken tok) {
        currentIndentation -= indentAmount;
        assert currentIndentation >=0;
        if (altFormat) {
            buffer.append(eol);
            appendIndentation();
            buffer.append("<-");
        }
    }

    void visit(Newline tok) {
        if (lineJoining() || !tok.isUnparsed()) {
            buffer.append(eol);
        }
    }

    void visit(ClassDefinition cd) {
        buffer.append(eol);
        recurse(cd);
    }

    void visit(FunctionDefinition fd) {
        recurse(fd);
        buffer.append(eol);
    }
}
