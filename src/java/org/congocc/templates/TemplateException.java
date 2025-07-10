package org.congocc.templates;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.UnifiedCall;
import org.congocc.templates.core.nodes.generated.IncludeInstruction;
import org.congocc.templates.core.nodes.generated.TemplateElement;

/**
 * The template classes usually use this exception and its descendants to
 * signal template engine specific exceptions.
 */
public class TemplateException extends RuntimeException {

    private List<TemplateElement> ctlStack=new ArrayList<>();

    public TemplateException(String message) {
        this(message,null);
    }

    /**
     * Constructs a TemplateException with both a description of the error
     * that occurred and the underlying Exception that caused this exception
     * to be raised.
     *
     * @param description the description of the error that occurred
     * @param cause the underlying <code>Exception</code> that caused this
     * exception to be raised
     */
    public TemplateException(String description, Exception cause) {
        super(description, cause);
        Environment env = Environment.getCurrentEnvironment();
        if(env != null) {
            ctlStack = env.getElementStack();
        }
    }

    public TemplateException(Exception cause) {
        this(null,cause);
    }

    /**
     * Returns the quote of the problematic CTL instruction and the CTL stack strace.
     * We provide access to the CTL instruction stack
     * so you might prefer to use Environment#getElementStack() and format the items in
     * list yourself.
     */
    public String getCTLInstructionStack() {
    	StringBuilder buf = new StringBuilder("----------\n");
    	if (ctlStack != null) {
        	boolean atFirstElement = true;
    		for (TemplateElement location : ctlStack) {
    			if (atFirstElement) {
    				atFirstElement = false;
    	            buf.append("==> ");
    	            buf.append(location.getDescription());
    	            buf.append(" [");
    	            buf.append(location.getLocation());
    	            buf.append("]\n");
    			} else if (location instanceof UnifiedCall || location instanceof IncludeInstruction){ // We only show macro calls and includes
                    String line = location.getDescription() + " ["
                    + location.getLocation() + "]";
                    if (line != null && line.length() > 0) {
                    	buf.append(" in ");
                    	buf.append(line);
                    	buf.append("\n");
                    }
    			}
    		}
        	buf.append("----------\n");
    	}
    	return buf.toString();
    }

    public List<TemplateElement> getCTLStack() {
        return ctlStack;
    }

    public void printStackTrace(java.io.PrintStream ps) {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(ps), true);
        printStackTrace(pw);
        pw.flush();
    }

    public void printStackTrace(PrintWriter pw) {
        pw.println();
        pw.println(getMessage());
        pw.println(getCTLInstructionStack());
        pw.println("Java backtrace for programmers:");
        pw.println("----------");
        super.printStackTrace(pw);
        Throwable cause = getCause();
        if (cause != null) {
            cause.printStackTrace(pw);
        }
    }
}