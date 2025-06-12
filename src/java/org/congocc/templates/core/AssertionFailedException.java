package org.congocc.templates.core;

import java.io.PrintWriter;
import java.io.PrintStream;

import org.congocc.templates.TemplateException;

/**
 * This exception is thrown when a &lt;stop&gt;
 * directive is encountered. 
 */

public class AssertionFailedException extends TemplateException
{
    public AssertionFailedException(Environment env, String s) {
        super(s, env);
    }

    public void printStackTrace(PrintWriter pw) {
        String msg = this.getMessage();
        pw.print("Encountered stop instruction");
        if (msg != null && !msg.equals("")) {
            pw.println("\nCause given: " + msg);
        } else pw.println();
        super.printStackTrace(pw);
    }

    public void printStackTrace(PrintStream ps) {
        String msg = this.getMessage();
        ps.print("Encountered stop instruction");
        if (msg != null && !msg.equals("")) {
            ps.println("\nCause given: " + msg);
        } else ps.println();
        super.printStackTrace(ps);
    }
}


