package org.congocc.templates.core.scopes;

import org.congocc.templates.TemplateException;

/**
 * This exception is thrown when a set directive in the template
 * tries to set a variable which is not declared in that scope or
 * an enclosing one.
 */

public class UndeclaredVariableException extends TemplateException {

	public UndeclaredVariableException(String message) {
		super(message);
	}
}
