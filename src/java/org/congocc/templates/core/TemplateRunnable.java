package org.congocc.templates.core;

import java.io.IOException;

public  interface TemplateRunnable<T> {
    public T run() throws IOException;
}
