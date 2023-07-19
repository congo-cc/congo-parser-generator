package org.congocc.codegen.csharp;

import org.congocc.parser.*;
import org.congocc.parser.csharp.CSToken;

/**
 * The beginnings of a Node.Visitor subclass for pretty-printing C# source code.
 * It does nothing right now, except echo the text.
 * @author revusky
 */
public class CSharpFormatter extends Node.Visitor {

    private StringBuilder buf = new StringBuilder();
    private CSToken precedingToken;

    void visit(CSToken tok) {
        if (precedingToken != null) {
            int schmooLeft = precedingToken.getEndOffset();
            int schmooRight = tok.getBeginOffset();
            String schmoo = tok.getTokenSource().getText(schmooLeft, schmooRight);
            //Append any unparsed text since the preceding token.
            buf.append(schmoo);
        }
        buf.append(tok.toString());
        precedingToken = tok;
    }

    public String getText() {
        if (buf.charAt(buf.length()-1) != '\n') buf.append('\n');
        return buf.toString();
    }
}
