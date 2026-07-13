package org.congocc.templates.core;

import java.util.*;

import org.congocc.templates.*;
import org.congocc.templates.utility.StringUtil;

/**
 * This is a common superclass of {@link org.congocc.templates.TemplateFactory},
 * {@link org.congocc.templates.Template}, and {@link Environment} classes.
 * It provides settings that are common to each of them. We
 * use a three-level setting hierarchy - the return value of every setting
 * getter method on <code>Configurable</code> objects inherits its value from
 * its fallback
 * <code>Configurable</code> object, unless explicitly overridden by a call to a
 * corresponding setter method on the object itself. The fallback of an
 * <code>Environment</code> object is a <code>Template</code> object, the
 * fallback of a <code>Template</code> object is a <code>Configuration</code>
 * object.
 */
public class Settings {
    public static final String LOCALE_KEY = "locale";
    public static final String NUMBER_FORMAT_KEY = "number_format";
    public static final String TEMPLATE_EXCEPTION_HANDLER_KEY = "template_exception_handler";
    public static final String ARITHMETIC_ENGINE_KEY = "arithmetic_engine";
    public static final String BOOLEAN_FORMAT_KEY = "boolean_format";
    public static final String OUTPUT_ENCODING_KEY = "output_encoding";
    public static final String URL_ESCAPING_CHARSET_KEY = "url_escaping_charset";

    private Settings fallback;
    private Properties properties;

    private Locale locale;
    private String numberFormat;
    private TemplateExceptionHandler templateExceptionHandler;
    private ArithmeticEngine arithmeticEngine;
    private String outputEncoding;
    private boolean outputEncodingSet;
    private String urlEscapingCharset;
    private boolean urlEscapingCharsetSet;

    public Settings() {
        fallback = null;
        locale = Locale.getDefault();
        numberFormat = "number";
        templateExceptionHandler = TemplateExceptionHandler.DEBUG_HANDLER;
        arithmeticEngine = ArithmeticEngine.BIGDECIMAL_ENGINE;

        properties = new Properties();
        properties.setProperty(LOCALE_KEY, locale.toString());
        properties.setProperty(NUMBER_FORMAT_KEY, numberFormat);
        properties.setProperty(TEMPLATE_EXCEPTION_HANDLER_KEY, templateExceptionHandler.getClass().getName());
        properties.setProperty(ARITHMETIC_ENGINE_KEY, arithmeticEngine.getClass().getName());
        properties.setProperty(BOOLEAN_FORMAT_KEY, "true,false");
    }

    protected Settings(Settings fallback) {
        this.fallback = fallback;
        locale = null;
        numberFormat = null;
        templateExceptionHandler = null;
        properties = new Properties(fallback.properties);
    }

    /**
     * Returns the fallback <tt>Configurable</tt> object of this object.
     * The fallback stores the default values for this configurable. For example,
     * the fallback of the {@link org.congocc.templates.Template} object is the
     * {@link org.congocc.templates.TemplateFactory} object, so setting values not
     * specfied on template level are specified by the confuration object.
     *
     * @return the fallback <tt>Configurable</tt> object, or null, if this is
     *         the root <tt>Configurable</tt> object.
     */
    public final Settings getFallback() {
        return fallback;
    }

    /**
     * Refallbacking support. This is used by Environment when it includes a
     * template - the included template becomes the fallback configurable during
     * its evaluation.
     */
    public void setFallback(Settings fallback) {
        this.fallback = fallback;
    }

    /**
     * Sets the locale to assume when searching for template files with no
     * explicit requested locale.
     */
    public void setLocale(Locale locale) {
        if (locale == null)
            throw new IllegalArgumentException("Setting \"locale\" can't be null");
        this.locale = locale;
        properties.setProperty(LOCALE_KEY, locale.toString());
    }

    /**
     * Returns the assumed locale when searching for template files with no
     * explicit requested locale. Defaults to system locale.
     */
    public Locale getLocale() {
        return locale != null ? locale : fallback.getLocale();
    }

    /**
     * Sets the number format used to convert numbers to strings.
     */
    public void setNumberFormat(String numberFormat) {
        if (numberFormat == null)
            throw new IllegalArgumentException("Setting \"number_format\" can't be null");
        this.numberFormat = numberFormat;
        properties.setProperty(NUMBER_FORMAT_KEY, numberFormat);
    }

    /**
     * Returns the default number format used to convert numbers to strings.
     * Defaults to <tt>"number"</tt>
     */
    public String getNumberFormat() {
        return numberFormat != null ? numberFormat : fallback.getNumberFormat();
    }

    /**
     * Sets the exception handler used to handle template exceptions.
     *
     * @param templateExceptionHandler the template exception handler to use for
     *                                 handling {@link TemplateException}s. By
     *                                 default,
     *                                 {@link TemplateExceptionHandler#HTML_DEBUG_HANDLER}
     *                                 is used.
     */
    public void setTemplateExceptionHandler(TemplateExceptionHandler templateExceptionHandler) {
        if (templateExceptionHandler == null)
            throw new IllegalArgumentException("Setting \"template_exception_handler\" can't be null");
        this.templateExceptionHandler = templateExceptionHandler;
        properties.setProperty(TEMPLATE_EXCEPTION_HANDLER_KEY, templateExceptionHandler.getClass().getName());
    }

    /**
     * Retrieves the exception handler used to handle template exceptions.
     */
    public TemplateExceptionHandler getTemplateExceptionHandler() {
        return templateExceptionHandler != null
                ? templateExceptionHandler
                : fallback.getTemplateExceptionHandler();
    }

    /**
     * Sets the arithmetic engine used to perform arithmetic operations.
     *
     * @param arithmeticEngine the arithmetic engine used to perform arithmetic
     *                         operations.By default,
     *                         {@link ArithmeticEngine#BIGDECIMAL_ENGINE} is
     *                         used.
     */
    public void setArithmeticEngine(ArithmeticEngine arithmeticEngine) {
        if (arithmeticEngine == null)
            throw new IllegalArgumentException("Setting \"arithmetic_engine\" can't be null");
        this.arithmeticEngine = arithmeticEngine;
        properties.setProperty(ARITHMETIC_ENGINE_KEY, arithmeticEngine.getClass().getName());
    }

    /**
     * Retrieves the arithmetic engine used to perform arithmetic operations.
     */
    public ArithmeticEngine getArithmeticEngine() {
        return arithmeticEngine != null
                ? arithmeticEngine
                : fallback.getArithmeticEngine();
    }

    /**
     * Sets the output encoding. Allows <code>null</code>, which means that the
     * output encoding is not known.
     */
    public void setOutputEncoding(String outputEncoding) {
        this.outputEncoding = outputEncoding;
        // java.util.Properties doesn't allow null value!
        if (outputEncoding != null) {
            properties.setProperty(OUTPUT_ENCODING_KEY, outputEncoding);
        } else {
            properties.remove(OUTPUT_ENCODING_KEY);
        }
        outputEncodingSet = true;
    }

    public String getOutputEncoding() {
        return outputEncodingSet
                ? outputEncoding
                : (fallback != null ? fallback.getOutputEncoding() : null);
    }

    /**
     * Sets the URL escaping charset. Allows <code>null</code>, which means that the
     * output encoding will be used for URL escaping.
     */
    public void setURLEscapingCharset(String urlEscapingCharset) {
        this.urlEscapingCharset = urlEscapingCharset;
        // java.util.Properties doesn't allow null value!
        if (urlEscapingCharset != null) {
            properties.setProperty(URL_ESCAPING_CHARSET_KEY, urlEscapingCharset);
        } else {
            properties.remove(URL_ESCAPING_CHARSET_KEY);
        }
        urlEscapingCharsetSet = true;
    }

    public String getURLEscapingCharset() {
        return urlEscapingCharsetSet
                ? urlEscapingCharset
                : (fallback != null ? fallback.getURLEscapingCharset() : null);
    }

    protected TemplateException invalidSettingValueException(String name, String value) {
        return new TemplateException("Invalid value for setting " + name + ": " + value);
    }
}
