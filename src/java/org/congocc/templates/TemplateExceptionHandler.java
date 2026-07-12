package org.congocc.templates;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.congocc.templates.core.Environment;

/**
 * An API for objects that handle exceptions that are thrown during
 * template rendering.
 */

public interface TemplateExceptionHandler {

   /**
    * handle the exception.
    * @param te the exception that occurred.
    * @param env The environment object that represents the rendering context
    * @param buffer the buffer to output to.
    */
    void handleTemplateException(TemplateException te, Environment env);


  /**
   * This is a TemplateExceptionHandler which simply skips errors. It does nothing
   * to handle the event.
   */
    TemplateExceptionHandler IGNORE_HANDLER = new TemplateExceptionHandler() {
        public void handleTemplateException(TemplateException te, Environment env) {}
    };

    /**
     * This is a TemplateExceptionHandler that simply rethrows the exception.
     * Note that the exception is logged before being rethrown.
     */
    TemplateExceptionHandler RETHROW_HANDLER =new TemplateExceptionHandler() {
	    public void handleTemplateException(TemplateException te, Environment env) {
            throw te;
        }
	};

    /**
     * This is a TemplateExceptionHandler used when you develop the templates. This handler
     * outputs the stack trace information to the client and then rethrows the exception.
     */
	TemplateExceptionHandler DEBUG_HANDLER =new TemplateExceptionHandler() {
		  public void handleTemplateException(TemplateException te, Environment env) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            te.printStackTrace(pw);
            env.append(sw.toString());
            throw te;
		}
	};

        /**
          * This is a TemplateExceptionHandler used when you develop HTML templates. This handler
          * outputs the stack trace information to the client and then rethrows the exception, and
          * surrounds it with tags to make the error message readable with the browser.
          */
    TemplateExceptionHandler HTML_DEBUG_HANDLER =new TemplateExceptionHandler() {
        public void handleTemplateException(TemplateException te, Environment env) {
            env.append("<!-- CONGO TEMPLATE ENGINE ERROR MESSAGE STARTS HERE -->"
                  + "<script language=javascript>//\"></script>"
                  + "<script language=javascript>//\'></script>"
                  + "<script language=javascript>//\"></script>"
                  + "<script language=javascript>//\'></script>"
                  + "</title></xmp></script></noscript></style></object>"
                  + "</head></pre></table>"
                  + "</form></table></table></table></a></u></i></b>"
                  + "<div align=left "
                  + "style='background-color:#FFFF00; color:#FF0000; "
                  + "display:block; border-top:double; padding:2pt; "
                  + "font-size:medium; font-family:Arial,sans-serif; "
                  + "font-style: normal; font-variant: normal; "
                  + "font-weight: normal; text-decoration: none; "
                  + "text-transform: none'>"
                  + "<b style='font-size:medium'>Congo template error!</b>"
                  + "<pre><xmp>\n");
            env.append("</xmp></pre></div></html>\n");
            throw te;
        }
	};
}
