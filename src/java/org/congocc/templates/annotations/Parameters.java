package org.congocc.templates.annotations;

import java.lang.annotation.*;

/**
 * An annotation that indicates what the parameters
 * an FTL transform or method can take. The annotation 
 * uses the same syntax as FTL.
 */

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})

public @interface Parameters {
   String value();
}
