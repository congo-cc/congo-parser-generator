package org.congocc.codegen.python;

import static org.congocc.parser.Token.TokenType.SEMICOLON;

import org.congocc.parser.*;
import org.congocc.parser.python.PythonParser;
import org.congocc.parser.tree.*;

public class JToAltPyTranslator extends Node.Visitor {

    private StringBuilder buf;

    public String translate(Node n) {
        buf = new StringBuilder();
        buf.append("\n# explicitdedent:on\n");
        visit(n);
        buf.append("\n# explicitdedent:restore\n");
        return buf.toString();
    }

    void visit(Token tok) {
        if (tok.getType() != SEMICOLON) {
            buf.append(tok.toString());
        }
    }

    void visit(IfStatement ifs) {
        var condition = ifs.get(2);
        var block = ifs.get(4);
        boolean hasElse = ifs.size() > 5;
        buf.append("if (");
        visit(condition);
        buf.append("):");
        visit(block);
        if (hasElse) {
            buf.append("else:");
            visit(ifs.get(6));
        }
    }

    void visit(WhileStatement ws) {
        var condition = ws.get(2);
        var statement = ws.get(4);
        buf.append("while (");
        visit(condition);
        buf.append("):");
        visit(statement);
    }

    void visit(BreakStatement br) {
        buf.append("break");
    }

    void visit(ContinueStatement cs) {
        buf.append("continue");
    }

    void visit(EnhancedForStatement efs) {
        buf.append("for (");
        visit(efs.get(2));
        buf.append(" in ");
        visit(efs.get(4));
        buf.append(":");
        visit(efs.get(6));
    }

    void visit(CodeBlock block) {
        for (int i = 1; i< block.size()-1;i++) {
            buf.append("\n");
            visit(block.get(i));
        }
        buf.append("\n<-\n");
    }

    static public void main(String[] args) {
        System.out.println("Reading in Java code\n--");
        String snippet = """
                     while (foo()) {
                        if (bar) {
                          foobar();
                        } else {
                          break;
                        }
                     }
                """;
        System.out.println(snippet);
        CongoCCParser parser = new CongoCCParser(snippet);
        parser.Statement();
        var node = parser.rootNode();
        String py = new JToAltPyTranslator().translate(node);
        System.out.println("Here is the intermediate altPy representation\n--\n");
        System.out.println(py);
        PythonParser pyParser = new PythonParser(py);
        pyParser.Module();
        node = pyParser.rootNode();
        System.out.println("Now outputting in regular indented python\n--\n");
        System.out.println(new PyFormatter().format(node, false));
    }
}