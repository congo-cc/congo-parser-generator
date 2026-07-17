package org.congocc.templates.core;

import org.congocc.templates.TemplateException;

/**
 * A subclass of TemplateException that says there
 * is no value associated with a given expression.
 */
public class InvalidReferenceException extends TemplateException {

    public InvalidReferenceException(String description) {
        super(description);
    }
}
