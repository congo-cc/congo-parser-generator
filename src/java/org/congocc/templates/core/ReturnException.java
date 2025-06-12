package org.congocc.templates.core;

/**
 * An exception used to jump out of a macro or function
 */

public class ReturnException extends RuntimeException {

    public static final ReturnException INSTANCE = new ReturnException();
    
    private ReturnException() {
    }
}
