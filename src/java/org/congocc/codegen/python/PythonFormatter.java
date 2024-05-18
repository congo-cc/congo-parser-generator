package org.congocc.codegen.python;

import java.util.List;
import java.util.regex.Pattern;

import org.congocc.parser.Node;
import org.congocc.parser.python.ast.*;
// The following import is necessary to compile 
// against JDK>8, since otherwise, it is ambiguous whether
// one is referring to java.lang.Module
import org.congocc.parser.python.ast.Module;

public class PythonFormatter {
    private final Module module;
    private static final String defaultIndent = "    ";
    private String currentIndent = "";
    private int indentLevel;
    private int lineNumber;
    private StringBuilder buffer;
    private final Pattern newLines = Pattern.compile("\\n{3,10000}");

    public PythonFormatter(Module module) {
        this.module = module;
    }

    protected void outputNode(Node node) {
        List<Node> kids = node.children();
        List<Node> grandKids;
        String s;
        int so, eo;

        if (kids.isEmpty()) {
            // Normally, just the EOF token appears here
            s = node.toString();
            if (!s.isEmpty()) {
                buffer.append(s);
            }
        }
        else {
            for (Node kid : kids) {
                int n = kid.getBeginLine();
                if (n != lineNumber) {
                    assert n > lineNumber;
                    n -= lineNumber;
                    while (n > 0) {
                        buffer.append('\n');
                        --n;
                        ++lineNumber;
                    }
                }
                if (kid instanceof Newline) {
                    buffer.append('\n');
                    ++lineNumber;
                }
                else if (kid instanceof Statement || kid instanceof SimpleStatement) {
                    s = kid.toString();
                    buffer.append(currentIndent).append(s);
                    lineNumber += s.chars().filter(ch -> ch == '\n').count();
                }
                else if (kid instanceof ClassDefinition || kid instanceof FunctionDefinition ||
                         kid instanceof IfStatement || kid instanceof WhileStatement ||
                         kid instanceof ForStatement || kid instanceof TryStatement) {
                    grandKids = kid.children();
                    so = grandKids.get(0).getBeginOffset();
                    eo = grandKids.get(grandKids.size() - 2).getEndOffset();
                    s = kid.getTokenSource().getText(so, eo);
                    buffer.append(currentIndent).append(s);
                    lineNumber += s.chars().filter(ch -> ch == '\n').count();
                    Node block = kid.getLastChild();
                    if (kid instanceof TryStatement) {
                        assert block instanceof Block || block instanceof ExceptBlock || block instanceof FinallyBlock;
                    }
                    else {
                        assert block instanceof Block;
                    }
                    if (!(block instanceof ExceptBlock)) {
                        outputNode(block);
                    }
                    else {
                        grandKids = block.children();
                        so = grandKids.get(0).getBeginOffset();
                        eo = grandKids.get(grandKids.size() - 2).getEndOffset();
                        s = kid.getTokenSource().getText(so, eo);
                        buffer.append(s);
                        lineNumber += s.chars().filter(ch -> ch == '\n').count();
                        outputNode(block.getLastChild());
                    }
                }
                else if (kid instanceof IndentToken) {
                    indentLevel++;
                    currentIndent += defaultIndent;
                }
                else if (kid instanceof DedentToken) {
                    assert indentLevel > 0;
                    --indentLevel;
                    currentIndent = currentIndent.substring(defaultIndent.length());
                }
                else if (kid instanceof Keyword || kid instanceof Delimiter) {
                    s = kid.toString();
                    buffer.append(s);
                }
                else {
                    outputNode(kid);
                }
            }
        }
    }

    public String format() {
        buffer = new StringBuilder();
        indentLevel = 0;
        lineNumber = 1;
        currentIndent = "";
        outputNode(module);
        String result = buffer.toString();
        result = newLines.matcher(result).replaceAll("\n\n");
        return result;
    }
}