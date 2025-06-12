package org.congocc.templates.core.variables;

import org.congocc.templates.core.Environment;
import org.congocc.templates.TemplateException;

/**
 * A subclass of TemplateException that says there
 * is no value associated with a given expression.
 * @author <a href="mailto:jon@revusky.com">Jonathan Revusky</a>
 */
public class InvalidReferenceException extends TemplateException {

    public InvalidReferenceException(Environment env) {
        super("invalid reference", env);
    }

    public InvalidReferenceException(String description, Environment env) {
        super(description, env);
    }
}
