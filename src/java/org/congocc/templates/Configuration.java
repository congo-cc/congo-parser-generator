package org.congocc.templates;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.net.URL;
import java.net.URLConnection;

import org.congocc.templates.core.Configurable;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.variables.WrappedVariable;
import org.congocc.templates.core.parser.ParseException;
import org.congocc.templates.core.parser.ParsingProblemImpl;
import org.congocc.templates.utility.StringUtil;

import static org.congocc.templates.core.variables.Wrap.*;

/**
 * Main entry point into the Congo Templates API, this class encapsulates the 
 * various configuration parameters with which the template engine is run, as well
 * as serves as a central template loading and caching point. Note that
 * this class uses a default strategy for loading 
 * and caching templates. 
 */

public class Configuration extends Configurable {

    private static Configuration defaultConfig = new Configuration();
    private boolean localizedLookup = true;
    private HashMap<String, Object> variables = new HashMap<String, Object>();
    private HashMap<String, String> encodingMap = new HashMap<String, String>();
    private Map<String, String> autoImportMap = new HashMap<String, String>();
    private ArrayList<String> autoImports = new ArrayList<String>();
    private ArrayList<String> autoIncludes = new ArrayList<String>();
    private String defaultEncoding = "UTF-8";
    private boolean tolerateParsingProblems = false;

    private Class<?> classForTemplateLoading;
    private String pathPrefix = "";
    private Path directoryForTemplateLoading = Paths.get(".");

    public Configuration() {
        loadBuiltInSharedVariables();
    }

    private void loadBuiltInSharedVariables() {
        //None here at the moment!
    }

    /**
     * 
     * @return the {@link Configuration} object that is being used
     * in this template processing thread.
     */
    static public Configuration getCurrentConfiguration() {
    	Environment env = Environment.getCurrentEnvironment();
    	return env != null ? env.getConfiguration() : defaultConfig;
    }
  
    /**
     * Set the explicit directory from which to load templates.
     */
    public void setDirectoryForTemplateLoading(String dir) throws IOException {
        directoryForTemplateLoading = FileSystems.getDefault().getPath(dir);
        if (!Files.isDirectory(directoryForTemplateLoading)) {
            directoryForTemplateLoading = null;
            throw new IllegalArgumentException("Directory " + dir + " is not a directory.");
        }
    }

    /**
     * Sets a class relative to which we do the 
     * Class.getResource() call to load templates.
     */
    public void setClassForTemplateLoading(Class<?> clazz, String pathPrefix) {
        this.classForTemplateLoading = clazz;
        this.pathPrefix = pathPrefix;
    }

    /**
     * Equivalent to <tt>getTemplate(name, thisCfg.getLocale(), thisCfg.getEncoding(thisCfg.getLocale()), true)</tt>.
     */
    public Template getTemplate(String name) throws IOException {
        Locale loc = getLocale();
        return getTemplate(name, loc, getEncoding(loc), true);
    }

    /**
     * Equivalent to <tt>getTemplate(name, locale, thisCfg.getEncoding(locale), true)</tt>.
     */
    public Template getTemplate(String name, Locale locale) throws IOException {
        return getTemplate(name, locale, getEncoding(locale), true);
    }

    /**
     * Equivalent to <tt>getTemplate(name, thisCfg.getLocale(), encoding, true)</tt>.
     */
    public Template getTemplate(String name, String encoding) throws IOException {
        return getTemplate(name, getLocale(), encoding, true);
    }

    /**
     * Equivalent to <tt>getTemplate(name, locale, encoding, true)</tt>.
     */
    public Template getTemplate(String name, Locale locale, String encoding) throws IOException {
        return getTemplate(name, locale, encoding, true);
    }

    /**
     * Retrieves a template specified by a name and locale, interpreted using
     * the specified character encoding, either parsed or unparsed. 
     * @return the requested template.
     * @throws FileNotFoundException if the template could not be found.
     * @throws IOException if there was a problem loading the template.
     * @throws ParseException if the template is syntactically bad.
     */
    public Template getTemplate(String name, Locale locale, String encoding, boolean parse) throws IOException {
        Template result = null;
        URL url = null;
        URLConnection connection = null;
        InputStream rawStream = null;
        if (directoryForTemplateLoading != null) {
            Path path = directoryForTemplateLoading.resolve(name);
            if (Files.exists(path)) {
                url = path.toUri().toURL();
                connection = url.openConnection();
                if (connection != null) {
                    rawStream = connection.getInputStream();
                }
            }
        } 
        if (rawStream == null && classForTemplateLoading !=null) {
            url = classForTemplateLoading.getResource(pathPrefix + "/" + name);
            if (url != null) {
                connection = url.openConnection();
                if (connection!= null) {
                    rawStream = connection.getInputStream();
                }
            }
        }
        if (rawStream == null) {
            throw new FileNotFoundException("Template " + name + " not found.");
        }
        byte[] bb = rawStream.readAllBytes();
        rawStream.close();
        String content = new String(bb, defaultEncoding);
        result = new Template(name, content, this, defaultEncoding);
        if (result.hasParsingProblems()) {
            for (ParsingProblemImpl pp : result.getParsingProblems()) {
                System.err.println(pp.getMessage());
            }
            if (!tolerateParsingProblems) {
                throw new ParseException(result.getParsingProblems());
            }
        }
        return result;
    }

    /**
     * Sets the default encoding for converting bytes to characters when
     * reading template files in a locale for which no explicit encoding
     * was specified. Defaults to default system encoding.
     */
    public void setDefaultEncoding(String encoding) {
        defaultEncoding = encoding;
    }

    /**
     * Gets the default encoding for converting bytes to characters when
     * reading template files in a locale for which no explicit encoding
     * was specified. Defaults to default system encoding.
     */
    public String getDefaultEncoding() {
        return defaultEncoding;
    }

    /**
     * Gets the preferred character encoding for the given locale, or the 
     * default encoding if no encoding is set explicitly for the specified
     * locale. You can associate encodings with locales using 
     * {@link #setEncoding(Locale, String)} or {@link #loadBuiltInEncodingMap()}.
     * @param loc the locale
     * @return the preferred character encoding for the locale.
     */
    public String getEncoding(Locale loc) {
        // Try for a full name match (may include country and variant)
        String charset = encodingMap.get(loc.toString());
        if (charset == null) {
            if (loc.getVariant().length() > 0) {
                @SuppressWarnings("deprecation")
                Locale l = new Locale(loc.getLanguage(), loc.getCountry());
                charset = encodingMap.get(l.toString());
                if (charset != null) {
                    encodingMap.put(loc.toString(), charset);
                }
            } 
            charset = encodingMap.get(loc.getLanguage());
            if (charset != null) {
                encodingMap.put(loc.toString(), charset);
            }
        }
        return charset != null ? charset : defaultEncoding;
    }

    /**
     * Sets the character set encoding to use for templates of
     * a given locale. If there is no explicit encoding set for some
     * locale, then the default encoding will be used, what you can
     * set with {@link #setDefaultEncoding}.
     *
     * @see #clearEncodingMap
     * @see #loadBuiltInEncodingMap
     */
    public void setEncoding(Locale locale, String encoding) {
        encodingMap.put(locale.toString(), encoding);
    }

    /**
     * Adds a shared variable to the configuration.
     * Shared variables are variables that are visible
     * as top-level variables for all templates which use this
     * configuration, if the data model does not contain a
     * variable with the same name.
     *
     * <p>Never use <tt>WrappedVariable</tt> implementation that is not thread-safe for shared variables,
     * if the configuration is used by multiple threads! It is the typical situation for Servlet based Web sites.
     *
     * @param name the name used to access the data object from your template.
     *     If a shared variable with this name already exists, it will replace
     *     that.
     * @see #setSharedVariable(String,Object)
     * @see #setAllSharedVariables
     */
    public void setSharedVariable(String name, Object tm) {
        variables.put(name, wrap(tm));
    }

    public void put(String key, Object obj) {
        variables.put(key, wrap(obj));
    }

    /**
     * Returns the set containing the names of all defined shared variables.
     * The method returns a new Set object on each call that is completely
     * disconnected from the Configuration. That is, modifying the set will have
     * no effect on the Configuration object.
     */
    public Set<String> getSharedVariableNames() {
        return new HashSet<String>(variables.keySet());
    }
    
    /**
     * Gets a shared variable. Shared variables are variables that are 
     * available to all templates. When a template is processed, and an identifier
     * is undefined in the data model, a shared variable object with the same identifier
     * is then looked up in the configuration. There are several predefined variables
     * that are always available through this method, see the manual
     * for a comprehensive list of them.
     *
     * @see #setSharedVariable(String,Object)
     * @see #setSharedVariable(String,WrappedVariable)
     * @see #setAllSharedVariables
     */
    public Object getSharedVariable(String name) {
        return variables.get(name);
    }
    
    /**
     * Removes all shared variables, except the predefined ones (compress, html_escape, etc.).
     */
    public void clearSharedVariables() {
        variables.clear();
        loadBuiltInSharedVariables();
    }
    
    /**
     * Removes all entries from the template cache, thus forcing reloading of templates
     * on subsequent <code>getTemplate</code> calls.
     * This method is thread-safe and can be called while the engine works.
     */
    public void clearTemplateCache() {
        // TODO
    }
    
    /**
     * Returns if localized template lookup is enabled or not.
     */
    public boolean getLocalizedLookup() {
        return this.localizedLookup;
    }
    
    /**
     * Enables/disables localized template lookup. Enabled by default.
     */
    public void setLocalizedLookup(boolean localizedLookup) {
        this.localizedLookup = localizedLookup;
    }
    
    /**
     * Sets a setting by name and string value.
     *
     * In additional to the settings understood by
     * {@link Configurable#setSetting the super method}, it understands these:
     * <ul>
     *   <li><code>"auto_import"</code>: Sets the list of auto-imports. Example of valid value:
     *       <br><code>/lib/form.ftl as f, /lib/widget as w, "/lib/evil name.ftl" as odd</code>
     *       See: {@link #setAutoImports}
     *   <li><code>"auto_include"</code>: Sets the list of auto-includes. Example of valid value:
     *       <br><code>/include/common.ftl, "/include/evil name.ftl"</code>
     *       See: {@link #setAutoIncludes}
     *   <li><code>"default_encoding"</code>: The name of the charset, such as <code>"UTF-8"</code>.
     *       See: {@link #setDefaultEncoding}
     *   <li><code>"localized_lookup"</code>:
     *       <code>"true"</code>, <code>"false"</code>, <code>"yes"</code>, <code>"no"</code>,
     *       <code>"t"</code>, <code>"f"</code>, <code>"y"</code>, <code>"n"</code>.
     *       Case insensitive.
     *      See: {@link #setLocalizedLookup}
     *   <li><code>"strict_vars"</code>: <code>"true"</code>, <code>"false"</code>, etc.
     *       See: {@link #setStrictVariableDefinition}
     *   <li><code>"cache_storage"</code>: If the value contains dot, then it is
     *       interpreted as class name, and the object will be created with
     *       its parameterless constructor. If the value does not contain dot,
     *       then a {@link org.congocc.templates.cache.MruCacheStorage} will be used with the
     *       maximum strong and soft sizes specified with the setting value. Examples
     *       of valid setting values:
     *       <table border=1 cellpadding=4>
     *         <tr><th>Setting value<th>max. strong size<th>max. soft size
     *         <tr><td><code>"strong:50, soft:500"</code><td>50<td>500
     *         <tr><td><code>"strong:100, soft"</code><td>100<td><code>Integer.MAX_VALUE</code>
     *         <tr><td><code>"strong:100"</code><td>100<td>0
     *         <tr><td><code>"soft:100"</code><td>0<td>100
     *         <tr><td><code>"strong"</code><td><code>Integer.MAX_VALUE</code><td>0
     *         <tr><td><code>"soft"</code><td>0<td><code>Integer.MAX_VALUE</code>
     *       </table>
     *       The value is not case sensitive. The order of <tt>soft</tt> and <tt>strong</tt>
     *       entries is not significant.
     *       See also: {@link #setCacheStorage}
     *   <li><code>"template_update_delay"</code>: Valid positive integer, the
     *       update delay measured in seconds.
     *       See: {@link #setTemplateUpdateDelay}
     * </ul>
     *
     * @param key the name of the setting.
     * @param value the string that describes the new value of the setting.
     *
     * @throws UnknownSettingException if the key is wrong.
     * @throws TemplateException if the new value of the setting can't be set
     *     for any other reasons.
     */
    public void setSetting(String key, String value) {
        if ("TemplateUpdateInterval".equalsIgnoreCase(key)) {
            key = "template_update_delay";
        } else if ("DefaultEncoding".equalsIgnoreCase(key)) {
            key = "default_encoding";
        }
        try {
            if ("default_encoding".equalsIgnoreCase(key)) {
                setDefaultEncoding(value);
            } else if ("localized_lookup".equalsIgnoreCase(key)) {
                setLocalizedLookup(StringUtil.getYesNo(value));
            } else if ("auto_include".equalsIgnoreCase(key)) {
                setAutoIncludes(new SettingStringParser(value).parseAsList());
            } else if ("auto_import".equalsIgnoreCase(key)) {
                setAutoImports(new SettingStringParser(value).parseAsImportList());
            } else {
                super.setSetting(key, value);
            }
        } catch(TemplateException e) {
            throw e;
        } catch(Exception e) {
            throw new TemplateException(
                    "Failed to set setting " + key + " to value " + value,
                    e, getEnvironment());
        }
    }
    
    
    /**
     * Add an auto-imported template.
     * The importing will happen at the top of any template that
     * is vended by this Configuration object.
     * @param namespace the name of the namespace into which the template is imported
     * @param template the name of the template
     */
    public synchronized void addAutoImport(String namespace, String template) {
        autoImports.remove(namespace);
        autoImports.add(namespace);
        autoImportMap.put(namespace, template);
    }
    
    /**
     * Remove an auto-imported template
     * @param namespace the name of the namespace into which the template was imported
     */
    
    public synchronized void removeAutoImport(String namespace) {
        autoImports.remove(namespace);
        autoImportMap.remove(namespace);
    }
    
    /**
     * set a map of namespace names to templates for auto-importing 
     * a set of templates. Note that all previous auto-imports are removed.
     */
    
    public synchronized void setAutoImports(Map<String, String> map) {
        autoImports = new ArrayList<String>(map.keySet());
       	autoImportMap = new HashMap<String, String>(map);
    }
    
    @Override
    protected void doAutoImportsAndIncludes(Environment env) throws IOException {
    	for (String namespace : autoImports) {
            String templateName = autoImportMap.get(namespace);
            env.importLib(templateName, namespace);
        }
    	for(String templateName: autoIncludes) {
            env.include(getTemplate(templateName, env.getLocale()), false);
        }
    }
    
    /**
     * add a template to be automatically included at the top of any template that
     * is vended by this Configuration object.
     * @param templateName the lookup name of the template.
     */
     
    public synchronized void addAutoInclude(String templateName) {
        autoIncludes.remove(templateName);
        autoIncludes.add(templateName);
    }

    /**
     * set the list of automatically included templates.
     * Note that all previous auto-includes are removed.
     */
    public synchronized void setAutoIncludes(List<String> templateNames) {
        autoIncludes.clear();
        autoIncludes.addAll(templateNames);
    }
    
    /**
     * remove a template from the auto-include list.
     * @param templateName the lookup name of the template in question.
     */
     
    public synchronized void removeAutoInclude(String templateName) {
        autoIncludes.remove(templateName);
    }
    
    /**
     * Returns version number.
     */
    public static String getVersionNumber() {
    	return "3.0 Preview";
    }
    
    /**
     * Set whether the getTemplate() methods throw exceptions
     * when there is a (recoverable) parsing problem in the template.
     * This would only be set true by certain tools such as FTL-aware
     * editors that work with FTL code that contains syntactical errors. 
     * @param tolerateParsingProblems
     */
    
    public void setTolerateParsingProblems(boolean tolerateParsingProblems) {
    	this.tolerateParsingProblems = tolerateParsingProblems;
    }
}
