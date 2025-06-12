package org.congocc.templates.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import org.congocc.templates.*;
import org.congocc.templates.utility.StringUtil;

/**
 * This is a common superclass of {@link org.congocc.templates.Configuration},
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
abstract public class Configurable {
    public static final String LOCALE_KEY = "locale";
    public static final String NUMBER_FORMAT_KEY = "number_format";
    public static final String TIME_FORMAT_KEY = "time_format";
    public static final String DATE_FORMAT_KEY = "date_format";
    public static final String DATETIME_FORMAT_KEY = "datetime_format";
    public static final String TIME_ZONE_KEY = "time_zone";
    public static final String TEMPLATE_EXCEPTION_HANDLER_KEY = "template_exception_handler";
    public static final String ARITHMETIC_ENGINE_KEY = "arithmetic_engine";
    public static final String BOOLEAN_FORMAT_KEY = "boolean_format";
    public static final String OUTPUT_ENCODING_KEY = "output_encoding";
    public static final String URL_ESCAPING_CHARSET_KEY = "url_escaping_charset";

    private static final char COMMA = ',';

    private Configurable fallback;
    private Properties properties;

    private Locale locale;
    private String numberFormat;
    private String timeFormat;
    private String dateFormat;
    private String dateTimeFormat;
    private TimeZone timeZone;
    private String trueFormat;
    private String falseFormat;
    private TemplateExceptionHandler templateExceptionHandler;
    private ArithmeticEngine arithmeticEngine;
    private String outputEncoding;
    private boolean outputEncodingSet;
    private String urlEscapingCharset;
    private boolean urlEscapingCharsetSet;

    public Configurable() {
        fallback = null;
        locale = Locale.getDefault();
        timeZone = TimeZone.getDefault();
        numberFormat = "number";
        timeFormat = "";
        dateFormat = "";
        dateTimeFormat = "";
        trueFormat = "true";
        falseFormat = "false";
        templateExceptionHandler = TemplateExceptionHandler.DEBUG_HANDLER;
        arithmeticEngine = ArithmeticEngine.BIGDECIMAL_ENGINE;

        properties = new Properties();
        properties.setProperty(LOCALE_KEY, locale.toString());
        properties.setProperty(TIME_FORMAT_KEY, timeFormat);
        properties.setProperty(DATE_FORMAT_KEY, dateFormat);
        properties.setProperty(DATETIME_FORMAT_KEY, dateTimeFormat);
        properties.setProperty(TIME_ZONE_KEY, timeZone.getID());
        properties.setProperty(NUMBER_FORMAT_KEY, numberFormat);
        properties.setProperty(TEMPLATE_EXCEPTION_HANDLER_KEY, templateExceptionHandler.getClass().getName());
        properties.setProperty(ARITHMETIC_ENGINE_KEY, arithmeticEngine.getClass().getName());
        properties.setProperty(BOOLEAN_FORMAT_KEY, "true,false");
    }

    /**
     * Creates a new instance. Normally you do not need to use this constructor,
     * as you don't use <code>Configurable</code> directly, but its subclasses.
     */
    public Configurable(Configurable fallback) {
        this.fallback = fallback;
        locale = null;
        numberFormat = null;
        trueFormat = null;
        falseFormat = null;
        templateExceptionHandler = null;
        properties = new Properties(fallback.properties);
    }

    /**
     * Returns the fallback <tt>Configurable</tt> object of this object.
     * The fallback stores the default values for this configurable. For example,
     * the fallback of the {@link org.congocc.templates.Template} object is the
     * {@link org.congocc.templates.Configuration} object, so setting values not
     * specfied on template level are specified by the confuration object.
     *
     * @return the fallback <tt>Configurable</tt> object, or null, if this is
     *         the root <tt>Configurable</tt> object.
     */
    public final Configurable getFallback() {
        return fallback;
    }

    /**
     * Refallbacking support. This is used by Environment when it includes a
     * template - the included template becomes the fallback configurable during
     * its evaluation.
     */
    public void setFallback(Configurable fallback) {
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
     * Returns the time zone to use when formatting time values. Defaults to
     * system time zone.
     */
    public TimeZone getTimeZone() {
        return timeZone != null ? timeZone : fallback.getTimeZone();
    }

    /**
     * Sets the time zone to use when formatting time values.
     */
    public void setTimeZone(TimeZone timeZone) {
        if (timeZone == null)
            throw new IllegalArgumentException("Setting \"time_zone\" can't be null");
        this.timeZone = timeZone;
        properties.setProperty(TIME_ZONE_KEY, timeZone.getID());
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

    public void setBooleanFormat(String booleanFormat) {
        if (booleanFormat == null) {
            throw new IllegalArgumentException("Setting \"boolean_format\" can't be null");
        }
        int comma = booleanFormat.indexOf(COMMA);
        if (comma == -1) {
            throw new IllegalArgumentException(
                    "Setting \"boolean_format\" must consist of two comma-separated values for true and false respectively");
        }
        trueFormat = booleanFormat.substring(0, comma);
        falseFormat = booleanFormat.substring(comma + 1);
        properties.setProperty(BOOLEAN_FORMAT_KEY, booleanFormat);
    }

    public String getBooleanFormat() {
        if (trueFormat == null) {
            return fallback.getBooleanFormat();
        }
        return trueFormat + COMMA + falseFormat;
    }

    public String getBooleanFormat(boolean value) {
        return value ? getTrueFormat() : getFalseFormat();
    }

    private String getTrueFormat() {
        return trueFormat != null ? trueFormat : fallback.getTrueFormat();
    }

    private String getFalseFormat() {
        return falseFormat != null ? falseFormat : fallback.getFalseFormat();
    }

    /**
     * Sets the date format used to convert date models representing time-only
     * values to strings.
     */
    public void setTimeFormat(String timeFormat) {
        if (timeFormat == null)
            throw new IllegalArgumentException("Setting \"time_format\" can't be null");
        this.timeFormat = timeFormat;
        properties.setProperty(TIME_FORMAT_KEY, timeFormat);
    }

    /**
     * Returns the date format used to convert date models representing
     * time-only dates to strings.
     * Defaults to <tt>"time"</tt>
     */
    public String getTimeFormat() {
        return timeFormat != null ? timeFormat : fallback.getTimeFormat();
    }

    /**
     * Sets the date format used to convert date models representing date-only
     * dates to strings.
     */
    public void setDateFormat(String dateFormat) {
        if (dateFormat == null)
            throw new IllegalArgumentException("Setting \"date_format\" can't be null");
        this.dateFormat = dateFormat;
        properties.setProperty(DATE_FORMAT_KEY, dateFormat);
    }

    /**
     * Returns the date format used to convert date models representing
     * date-only dates to strings.
     * Defaults to <tt>"date"</tt>
     */
    public String getDateFormat() {
        return dateFormat != null ? dateFormat : fallback.getDateFormat();
    }

    /**
     * Sets the date format used to convert date models representing datetime
     * dates to strings.
     */
    public void setDateTimeFormat(String dateTimeFormat) {
        if (dateTimeFormat == null)
            throw new IllegalArgumentException("Setting \"datetime_format\" can't be null");
        this.dateTimeFormat = dateTimeFormat;
        properties.setProperty(DATETIME_FORMAT_KEY, dateTimeFormat);
    }

    /**
     * Returns the date format used to convert date models representing datetime
     * dates to strings.
     * Defaults to <tt>"datetime"</tt>
     */
    public String getDateTimeFormat() {
        return dateTimeFormat != null ? dateTimeFormat : fallback.getDateTimeFormat();
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

    /**
     * Sets a setting by a name and string value.
     * 
     * <p>
     * List of supported names and their valid values:
     * <ul>
     * <li><code>"locale"</code>: local codes with the usual format, such as
     * <code>"en_US"</code>.
     * <li><code>"template_exception_handler"</code>: If the value contains dot,
     * then it is
     * interpreted as class name, and the object will be created with
     * its parameterless constructor. If the value does not contain dot,
     * then it must be one of these special values:
     * <code>"rethrow"</code>, <code>"debug"</code>,
     * <code>"html_debug"</code>, <code>"ignore"</code> (case insensitive).
     * <li><code>"arithmetic_engine"</code>: If the value contains dot, then it is
     * interpreted as class name, and the object will be created with
     * its parameterless constructor. If the value does not contain dot,
     * then it must be one of these special values:
     * <code>"bigdecimal"</code>, <code>"conservative"</code> (case insensitive).
     * <li><code>"object_wrapper"</code>: If the value contains dot, then it is
     * interpreted as class name, and the object will be created with
     * its parameterless constructor. If the value does not contain dot,
     * then it must be one of these special values:
     * <code>"simple"</code>, <code>"beans"</code>.
     * <li><code>"number_format"</code>: pattern as
     * <code>java.text.DecimalFormat</code> defines.
     * <li><code>"boolean_format"</code>: the textual value for boolean true and
     * false,
     * separated with comma. For example <code>"yes,no"</code>.
     * <li><code>"date_format", "time_format", "datetime_format"</code>: patterns as
     * <code>java.text.SimpleDateFormat</code> defines.
     * <li><code>"time_zone"</code>: time zone, with the format as
     * <code>java.util.TimeZone.getTimeZone</code> defines. For example
     * <code>"GMT-8:00"</code> or
     * <code>"America/Los_Angeles"</code>
     * <li><code>"output_encoding"</code>: Informs about the charset
     * used for the output. As the template engine outputs character stream (not
     * byte stream), it is not aware of the output charset unless the
     * software that encloses it tells it explicitly with this setting.
     * Some templates may use features that require this.</code>
     * <li><code>"url_escaping_charset"</code>: If this setting is set, then it
     * overrides the value of the <code>"output_encoding"</code> setting when
     * there is URL encoding.
     * </ul>
     * 
     * @param key   the name of the setting.
     * @param value the string that describes the new value of the setting.
     * 
     * @throws UnknownSettingException if the key is wrong.
     * @throws TemplateException       if the new value of the setting can't be set
     *                                 for any other reasons.
     */
    public void setSetting(String key, String value) {
        try {
            if (LOCALE_KEY.equals(key)) {
                setLocale(StringUtil.deduceLocale(value));
            } else if (NUMBER_FORMAT_KEY.equals(key)) {
                setNumberFormat(value);
            } else if (TIME_FORMAT_KEY.equals(key)) {
                setTimeFormat(value);
            } else if (DATE_FORMAT_KEY.equals(key)) {
                setDateFormat(value);
            } else if (DATETIME_FORMAT_KEY.equals(key)) {
                setDateTimeFormat(value);
            } else if (TIME_ZONE_KEY.equals(key)) {
                setTimeZone(TimeZone.getTimeZone(value));
            } else if (TEMPLATE_EXCEPTION_HANDLER_KEY.equals(key)) {
                if (value.indexOf('.') == -1) {
                    if ("debug".equalsIgnoreCase(value)) {
                        setTemplateExceptionHandler(
                                TemplateExceptionHandler.DEBUG_HANDLER);
                    } else if ("html_debug".equalsIgnoreCase(value)) {
                        setTemplateExceptionHandler(
                                TemplateExceptionHandler.HTML_DEBUG_HANDLER);
                    } else if ("ignore".equalsIgnoreCase(value)) {
                        setTemplateExceptionHandler(
                                TemplateExceptionHandler.IGNORE_HANDLER);
                    } else if ("rethrow".equalsIgnoreCase(value)) {
                        setTemplateExceptionHandler(
                                TemplateExceptionHandler.RETHROW_HANDLER);
                    } else {
                        throw invalidSettingValueException(key, value);
                    }
                } else {
                    setTemplateExceptionHandler(
                            (TemplateExceptionHandler) Class.forName(value).getConstructor().newInstance());
                }
            } else if (ARITHMETIC_ENGINE_KEY.equals(key)) {
                if (value.indexOf('.') == -1) {
                    if ("bigdecimal".equalsIgnoreCase(value)) {
                        setArithmeticEngine(ArithmeticEngine.BIGDECIMAL_ENGINE);
                    } else if ("conservative".equalsIgnoreCase(value)) {
                        setArithmeticEngine(ArithmeticEngine.CONSERVATIVE_ENGINE);
                    } else {
                        throw invalidSettingValueException(key, value);
                    }
                } else {
                    setArithmeticEngine(
                            (ArithmeticEngine) Class.forName(value).getConstructor().newInstance());
                }
            } else if (BOOLEAN_FORMAT_KEY.equals(key)) {
                setBooleanFormat(value);
            } else if (OUTPUT_ENCODING_KEY.equals(key)) {
                setOutputEncoding(value);
            } else if (URL_ESCAPING_CHARSET_KEY.equals(key)) {
                setURLEscapingCharset(value);
            } else {
                throw unknownSettingException(key);
            }
        } catch (TemplateException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateException(
                    "Failed to set setting " + key + " to value " + value,
                    e, getEnvironment());
        }
    }

    public Environment getEnvironment() {
        return Environment.getCurrentEnvironment();
    }

    protected TemplateException unknownSettingException(String name) {
        return new UnknownSettingException(name, getEnvironment());
    }

    protected TemplateException invalidSettingValueException(String name, String value) {
        return new TemplateException("Invalid value for setting " + name + ": " + value, getEnvironment());
    }

    public static class UnknownSettingException extends TemplateException {

        private UnknownSettingException(String name, Environment env) {
            super("Unknown setting: " + name, env);
        }
    }

    /**
     * Set the settings stored in a <code>Properties</code> object.
     * 
     * @throws TemplateException if the <code>Properties</code> object contains
     *                           invalid keys, or invalid setting values, or any
     *                           other error occurs
     *                           while changing the settings.
     */
    public void setSettings(Properties props) {
        Iterator<Object> it = props.keySet().iterator();
        while (it.hasNext()) {
            String key = (String) it.next();
            setSetting(key, props.getProperty(key).trim());
        }
    }

    /**
     * Reads a setting list (key and element pairs) from the input stream.
     * The stream has to follow the usual <code>.properties</code> format.
     *
     * @throws TemplateException if the stream contains
     *                           invalid keys, or invalid setting values, or any
     *                           other error occurs
     *                           while changing the settings.
     * @throws IOException       if an error occurred when reading from the input
     *                           stream.
     */
    public void setSettings(InputStream propsIn) throws IOException {
        Properties p = new Properties();
        p.load(propsIn);
        setSettings(p);
    }

    protected void doAutoImportsAndIncludes(Environment env) throws IOException {
        if (fallback != null)
            fallback.doAutoImportsAndIncludes(env);
    }
}
