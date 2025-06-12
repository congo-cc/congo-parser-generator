package org.congocc.templates;

/**
 * 
 * You can implement this interface if you want to precisely control 
 * an object's "truthiness", i.e. whether it is interpreted as a 
 * true or a false in the appropriate context. This interface is 
 * really a vestige of FreeMarker
 * which had utterly strict semantics on something being "truthy"
 * or "falsy". For the most part, it is not really necessary, since, 
 * in Congo Templates, the "truthiness" of most objects is already implicit 
 * via some rules of thumb, like an empty container is taken to be false. 
 * All strings and numbers are true, except for the special case of a zero-length
 * string, which is false. A Java null, i.e. that something is undefined,
 * is also false. These rules of thumb are not really 100% correct on purist 
 * grounds, but provide quite a bit of notational convenience, which is 
 * nothing to scoff at!
 */
public interface TemplateBoolean {

    /**
     * @return whether to interpret this object as true or false in a boolean context
     */
    boolean getAsBoolean();
}
