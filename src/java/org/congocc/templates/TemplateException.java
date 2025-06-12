package org.congocc.templates;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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

    private List<TemplateElement> ftlStack;

    public TemplateException(String message) {
        super(message);
    }

    /**
     * Constructs a TemplateException with the given detail message,
     * but no underlying cause exception.
     *
     * @param description the description of the error that occurred
     */
    public TemplateException(String description, Environment env) {
        this(description, null, env);
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
    public TemplateException(String description, Exception cause, Environment env) {
        super(getDescription(description, cause), cause);
        if(env != null) {
            ftlStack = new ArrayList<>(env.getElementStack());
            Collections.reverse(ftlStack); // We put this in opposite order, as the trace is usually displayed that way.
        }
    }

    public TemplateException(Exception cause, Environment env) {
        this(null, cause, env);
    }

    private static String getDescription(String description, Exception cause)  {
        if(description != null) {
            return description;
        }
        if(cause != null) {
            return cause.getClass().getName() + ": " + cause.getMessage();
        }
        return "No error message";
    }
    
    /**
     * Returns the quote of the problematic FTL instruction and the FTL stack strace.
     * We provide access to the FTL instruction stack
     * so you might prefer to use getFTLStack() and format the items in 
     * list yourself.
     * @see #getFTLStack() 
     */
    public String getFTLInstructionStack() {
    	StringBuilder buf = new StringBuilder("----------\n");
    	if (ftlStack != null) {
        	boolean atFirstElement = true;
    		for (TemplateElement location : ftlStack) {
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
    
    /**
     * @return the FTL call stack (starting with current element)
     */
    public List<TemplateElement> getFTLStack() {
    	if (ftlStack == null) {
    		return Collections.emptyList();
    	}
    	return Collections.unmodifiableList(ftlStack);
    }

    public void printStackTrace(java.io.PrintStream ps) {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(ps), true);
        printStackTrace(pw);
        pw.flush();
    }

    public void printStackTrace(PrintWriter pw) {
        pw.println();
        pw.println(getMessage());
        pw.println(getFTLInstructionStack());
        pw.println("Java backtrace for programmers:");
        pw.println("----------");
        super.printStackTrace(pw);
        
        // Dirty hack to fight with stupid ServletException class whose
        // getCause() method doesn't work properly. Also an aid for pre-J2xE 1.4
        // users.
        try {
            // Reflection is used to prevent dependency on Servlet classes.
            Throwable causeException = getCause();
            Method m = causeException.getClass().getMethod("getRootCause");
            Throwable rootCause = (Throwable) m.invoke(causeException);
            if (rootCause != null) {
                Throwable j14Cause = null;
                if (causeException != null) {
                    m = causeException.getClass().getMethod("getCause");
                    j14Cause = (Throwable) m.invoke(causeException);
                }
                if (j14Cause == null) {
                    pw.println("ServletException root cause: ");
                    rootCause.printStackTrace(pw);
                }
            }
        } catch (Throwable exc) {
            ; // ignore
        }
    }
}