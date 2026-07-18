package org.congocc.templates;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.net.URL;
import java.net.URLConnection;

import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.parser.ParseException;
import org.congocc.templates.core.parser.ParsingProblemImpl;
import org.congocc.templates.extensions.*;

import static org.congocc.templates.core.Wrap.*;
import static org.congocc.templates.core.parser.TokenSource.stringFromBytes;

/**
 * Main entry point into the Congo Templates API, this class encapsulates the
 * various configuration parameters with which the template engine is run, as well
 * as serves as a central template loading and caching point. Note that
 * this class uses a default strategy for loadingand caching templates.
 */
public class TemplateFactory {

    private static TemplateFactory defaultFactory = new TemplateFactory();
    private boolean localizedLookup = true;
    private HashMap<String, Object> variables = new HashMap<String, Object>();
    private Map<String, String> autoImportMap = new HashMap<String, String>();
    private ArrayList<String> autoImports = new ArrayList<String>();
    private ArrayList<String> autoIncludes = new ArrayList<String>();
    private Map<String,Template> templateCache = Collections.synchronizedMap(new HashMap<>());
    private ArithmeticEngine arithmeticEngine = ArithmeticEngine.BIGDECIMAL_ENGINE;
    private String numberFormat = "number";
    private TemplateExceptionHandler templateExceptionHandler = TemplateExceptionHandler.DEBUG_HANDLER;

    private Class<?> classForTemplateLoading;
    private String pathPrefix = "";
    private Path directoryForTemplateLoading = Paths.get(".").toAbsolutePath().normalize();
    private Locale defaultLocale = Locale.getDefault();
    private Function<String,String> outputEscape = Function.identity();

    private Map<String, Extension> knownExtensions = new HashMap<>();
    {
        knownExtensions.put("Source", (caller, env) -> caller.lhs().getSource());
        knownExtensions.put("instanceof", new instanceofBI());
        knownExtensions.put("C", new cBI());
        knownExtensions.put("Eval", new evalBI());
        NumericalCast numericalCast = new NumericalCast();
        knownExtensions.put("byte", numericalCast);
        knownExtensions.put("double", numericalCast);
        knownExtensions.put("float", numericalCast);
        knownExtensions.put("int", numericalCast);
        knownExtensions.put("long", numericalCast);
        knownExtensions.put("short", numericalCast);
        knownExtensions.put("Floor", numericalCast);
        knownExtensions.put("Ceiling", numericalCast);
        knownExtensions.put("Round", numericalCast);
        knownExtensions.put("Capitalize", new StringTransformations.Capitalize());
        knownExtensions.put("CapFirst", new StringTransformations.CapFirst(true));
        knownExtensions.put("UncapFirst", new StringTransformations.CapFirst(false));
        knownExtensions.put("JavaStringEncode", new StringTransformations.Java());
        knownExtensions.put("JavaScriptStringEncode", new StringTransformations.JavaScript());
        knownExtensions.put("ChopLinebreak", new StringTransformations.Chomp());
        knownExtensions.put("HTML", new StringTransformations.Html());
        knownExtensions.put("WebSafe", knownExtensions.get("HTML"));
        knownExtensions.put("RTF", new StringTransformations.Rtf());
        knownExtensions.put("XML", new StringTransformations.Xml());
        knownExtensions.put("XHTML", new StringTransformations.Xhtml());
        knownExtensions.put("Join", new StringFunctions.Join());
        knownExtensions.put("Number", new numberBI());
        knownExtensions.put("LeftPad", new StringFunctions.LeftPad());
        knownExtensions.put("RightPad", new StringFunctions.RightPad());
        knownExtensions.put("Groups", new groupsBI());
        knownExtensions.put("Matches", new StringFunctions.Matches());
        knownExtensions.put("WordList", new StringFunctions.WordList());
        knownExtensions.put("URL", new StringFunctions.Url());
        knownExtensions.put("First", new SequenceFunctions.First());
        knownExtensions.put("Last", new SequenceFunctions.Last());
        knownExtensions.put("Reverse", new SequenceFunctions.Reverse());
        knownExtensions.put("Scope", new MacroBuiltins.Scope());
        knownExtensions.put("Namespace", new MacroBuiltins.Namespace());
        knownExtensions.put("Keys", new HashBuiltin.Keys());
        knownExtensions.put("Values", new HashBuiltin.Values());
        knownExtensions = Collections.synchronizedMap(knownExtensions);
    }

    public static TemplateFactory getDefault() {
        return defaultFactory;
    }

    protected static void setDefault(TemplateFactory factory) {
        defaultFactory = factory;
    }

    /**
     * Set the explicit directory from which to load templates.
     */
    public void setDirectoryForTemplateLoading(String dir) {
        Path path = FileSystems.getDefault().getPath(dir);
        directoryForTemplateLoading = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(directoryForTemplateLoading)) {
            directoryForTemplateLoading = null;
            throw new IllegalArgumentException("Directory " + dir + " is not a directory.");
        }
    }

    public Extension getExtension(String name) {
        return knownExtensions.get(name);
    }

    public void registerExtension(String name, Extension extension) {
        knownExtensions.put(name, extension);
    }

    public void registerFunctionExtension(String name, Function<Object,?> func) {
        registerExtension(name, (exp,env)->func.apply(exp.lhs().evaluate(env)));
    }

    public Function<String,String> getOutputEscape() {
        return outputEscape;
    }

    public void setOutputEscape(Function<String,String> outputEscape) {
        this.outputEscape = outputEscape;
    }


    /**
     * Sets a class relative to which we do the
     * Class.getResource() call to load templates.
     */
    public void setClassForTemplateLoading(Class<?> clazz, String pathPrefix) {
        this.classForTemplateLoading = clazz;
        this.pathPrefix = pathPrefix;
    }

    public Template getTemplate(String name) throws IOException {
        return getTemplate(name, getDefaultLocale());
    }

    /** Retrieves a template specified by name
     * @return the requested template.
     * @throws FileNotFoundException if the template could not be found.
     * @throws IOException if there was a problem loading the template.
     * @throws ParseException if the template is syntactically bad.
     */
    public Template getTemplate(String name, Locale locale) throws IOException {
        String lookupKey = name + "#" + locale;
        Template cachedTemplate = templateCache.get(lookupKey);
        if (cachedTemplate != null && !needsReparse(cachedTemplate)) {
            return cachedTemplate;
        }
        if (directoryForTemplateLoading == null && classForTemplateLoading == null) {
            throw new IOException("""
                You must specify either a directory from which to load templates or a
                Class for template loading if you wish to use the ClassLoader mechanism,
                one or the other.
            """);
        }
        URL url = null;
        URLConnection connection = null;
        InputStream rawStream = null;
        long lastModified=0L;
        if (directoryForTemplateLoading != null) {
            Path path = directoryForTemplateLoading.resolve(name).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                if (!path.startsWith(directoryForTemplateLoading)) {
                    throw new IllegalArgumentException("Specified path: " + path + " must be in the directory " + directoryForTemplateLoading + " or a directory underneath it for security reasons.");
                }
                url = path.toUri().toURL();
                connection = url.openConnection();
                lastModified = connection.getLastModified();
                if (connection != null) {
                    rawStream = connection.getInputStream();
                }
            }
        }
        if (rawStream == null && classForTemplateLoading !=null) {
            url = classForTemplateLoading.getResource(pathPrefix + "/" + name);
            if (url != null) {
                connection = url.openConnection();
                lastModified = connection.getLastModified();
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
        String content = stringFromBytes(bb);
        Template result = new Template(name, content, this);
        if (result.hasParsingProblems()) {
            for (ParsingProblemImpl pp : result.getParsingProblems()) {
                System.err.println(pp.getMessage());
            }
            throw new ParseException(result.getParsingProblems());
        }
        result.setLastModified(lastModified);
        result.setLocale(locale);
        templateCache.put(lookupKey, result);
        return result;
    }

    private boolean needsReparse(Template template) throws IOException {
        if (directoryForTemplateLoading != null) {
            Path path = directoryForTemplateLoading.resolve(template.getName());
            if (Files.exists(path)) {
                long lastModified = Files.getLastModifiedTime(path).toMillis();
                return template.getLastModified() < lastModified;
            }
        }
        if (classForTemplateLoading != null) {
            URL url = classForTemplateLoading.getResource(pathPrefix + "/" + template.getName());
            if (url != null) {
                return url.openConnection().getLastModified() > template.getLastModified();
            }
        }
        return true;
    }

    public ArithmeticEngine getArithmeticEngine() {
        return arithmeticEngine;
    }

    public void setArithmeticEngine(ArithmeticEngine arithmeticEngine) {
        this.arithmeticEngine = arithmeticEngine;
    }

    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(Locale locale) {
        this.defaultLocale = locale;
    }

    public void setNumberFormat(String numberFormat) {
        if (numberFormat == null)
            throw new IllegalArgumentException("Setting \"number_format\" can't be null");
        this.numberFormat = numberFormat;
    }

    public String getNumberFormat() {
        return numberFormat;
    }

    public void setTemplateExceptionHandler(TemplateExceptionHandler templateExceptionHandler) {
        this.templateExceptionHandler = templateExceptionHandler;
    }

    public TemplateExceptionHandler getTemplateExceptionHandler() {
        return templateExceptionHandler;
    }

    /**
     * Adds a shared variable to the configuration.
     * Shared variables are variables that are visible
     * as top-level variables for all templates which use this
     * configuration, if the data model does not contain a
     * variable with the same name.
     */
    public void setSharedVariable(String name, Object value) {
        variables.put(name, wrap(value));
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
     * @see #setAllSharedVariables
     */
    public Object getSharedVariable(String name) {
        return variables.get(name);
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

    void doAutoImports(Environment env) throws IOException {
    	for (String namespace : autoImports) {
            String templateName = autoImportMap.get(namespace);
            env.importLib(templateName, namespace);
        }
    	for(String templateName: autoIncludes) {
            env.include(getTemplate(templateName, env.getLocale()), false);
        }
    }

    /**
     * Returns version number.
     */
    public static String getVersionNumber() {
    	return "3.0 Preview";
    }
}
