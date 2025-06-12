package org.congocc.templates.core.variables;

import org.congocc.templates.core.Environment;
import org.congocc.templates.TemplateException;

/**
 * This exception is thrown if there is some problem 
 * evaluating an expression.
 */
public class EvaluationException extends TemplateException {

    /**
     * Constructs an <tt>EvaluationException</tt> with no
     * specified detail message.
     */
    public EvaluationException() {
        this(null, null);
    }

    /**
     * Constructs an <tt>EvaluationException</tt> with the
     * specified detail message.
     *
     * @param description the detail message.
     */
    public EvaluationException(String description) {
        this(description, null);
    }

    /**
     * Constructs an <tt>EvaluationException</tt> with the given underlying
     * Exception, but no detail message.
     *
     * @param cause the underlying <code>Exception</code> that caused this
     * exception to be raised
     */
    public EvaluationException(Exception cause) {
        this(null, cause);
    }

    /**
     * Constructs an EvaluationException with both a description of the error
     * that occurred and the underlying Exception that caused this exception
     * to be raised.
     *
     * @param description the description of the error that occurred
     * @param cause the underlying <code>Exception</code> that caused this
     * exception to be raised
     */
    public EvaluationException(String description, Exception cause) {
        super( description, cause, Environment.getCurrentEnvironment() );
    }
}
