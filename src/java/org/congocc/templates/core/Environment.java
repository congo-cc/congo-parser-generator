package org.congocc.templates.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.Collator;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import org.congocc.templates.core.nodes.generated.ArgsList;
import org.congocc.templates.core.nodes.generated.Block;
import org.congocc.templates.core.nodes.generated.IncludeInstruction;
import org.congocc.templates.core.nodes.generated.Macro;
import org.congocc.templates.core.nodes.generated.NestedInstruction;
import org.congocc.templates.core.nodes.generated.PositionalArgsList;
import org.congocc.templates.core.nodes.ParameterList;
import org.congocc.templates.core.nodes.generated.TemplateElement;
import org.congocc.templates.core.nodes.generated.UnifiedCall;
import org.congocc.templates.core.variables.*;
import org.congocc.templates.core.variables.scope.*;
import org.congocc.templates.*;

import static org.congocc.templates.core.variables.Wrap.*;

/**
 * Object that represents the runtime environment during template processing.
 * For every invocation of a <tt>Template.process()</tt> method, a new
 * instance of this object is created, and then discarded when
 * <tt>process()</tt> returns. This object stores the set of temporary
 * variables created by the template, the value of settings set by the template,
 * the reference to the data model root, etc. Everything that is needed to
 * fulfill the template processing job.
 * 
 * <p>
 * Data models that need to access the <tt>Environment</tt> object that
 * represents the template processing on the current thread can use the
 * {@link #getCurrentEnvironment()} method.
 * 
 * <p>
 * If you need to modify or read this object before or after the
 * <tt>process</tt> call, use
 * 
 * @author <a href="mailto:jon@revusky.com">Jonathan Revusky</a>
 * @author Attila Szegedi
 */
@SuppressWarnings("rawtypes")
public final class Environment extends Configurable implements Scope {
    private static final ThreadLocal<Environment> threadEnv = new ThreadLocal<Environment>();

    private static final Map<NumberFormatKey, NumberFormat> localizedNumberFormats = new HashMap<NumberFormatKey, NumberFormat>();

    // Do not use this object directly; clone it first! DecimalFormat isn't
    // thread-safe.
    private static final DecimalFormat C_NUMBER_FORMAT = new DecimalFormat(
            "0.################",
            new DecimalFormatSymbols(Locale.US));
    static {
        C_NUMBER_FORMAT.setGroupingUsed(false);
        C_NUMBER_FORMAT.setDecimalSeparatorAlwaysShown(false);
    }

    private final Map<String,Object> rootDataModel;

    private final List<TemplateElement> elementStack = new ArrayList<TemplateElement>();

    private final List<String> recoveredErrorStack = new ArrayList<String>();

    private NumberFormat numberFormat;

    private Map<String, NumberFormat> numberFormats;

    private NumberFormat cNumberFormat;

    private Collator collator;

    private Writer out;

    private MacroContext currentMacroContext;

    private Scope mainNamespace;

    private Scope currentScope;

    private Map<Macro, MacroContext> macroContextLookup = new HashMap<>();

    private Map<Macro, Scope> macroToNamespaceLookup = new HashMap<>();

    private HashMap<String, Object> globalVariables = new HashMap<>();

    private HashMap<String, Scope> loadedLibs;

    private Throwable lastThrowable;

    private Object lastReturnValue;

    private List<Scope> nodeNamespaces;

    // Things we keep track of for the fallback mechanism.
    private int nodeNamespaceIndex;

    private String currentNodeName, currentNodeNS;

    private String cachedURLEscapingCharset;

    private boolean urlEscapingCharsetCached;

    /**
     * Retrieves the environment object associated with the current thread. Data
     * model implementations that need access to the environment can call this
     * method to obtain the environment object that represents the template
     * processing that is currently running on the current thread.
     */
    public static Environment getCurrentEnvironment() {
        return threadEnv.get();
    }

    public Environment(Template template, Map<String,Object> rootDataModel, Writer out) {
        super(template);
        this.currentScope = mainNamespace = new BlockScope(template.getRootElement(), this);
        this.out = out;
        this.rootDataModel = rootDataModel;
        importMacros(template);
    }

    public void setCurrentScope(Scope scope) {
        this.currentScope = scope;
    }

    public Scope getCurrentScope() {
        return currentScope;
    }

    /**
     * Retrieves the currently processed template.
     */
    public Template getTemplate() {
        return (Template) getFallback();
    }

    /**
     * Processes the template to which this environment belongs.
     */
    public void process() throws IOException {
        Environment savedEnv = threadEnv.get();
        threadEnv.set(this);
        try {
            doAutoImportsAndIncludes(this);
            Template template = getTemplate();
            render(template.getRootElement());
            // Do not flush if there was an exception.
            out.flush();
        } finally {
            threadEnv.set(savedEnv);
        }
    }

    /**
     * "Visit" the template element.
     */
    public void render(TemplateElement element) throws IOException {
        pushElement(element);
        Block nestedBlock = element.getNestedBlock();
        boolean createNewScope = nestedBlock != null 
                                 && !nestedBlock.isTemplateRoot()
                                 && !(nestedBlock.getParent() instanceof Macro)
                                 && nestedBlock.createsScope();
        Scope prevScope = currentScope;
        if (createNewScope) {
            currentScope = new BlockScope(nestedBlock, currentScope);
        }
        try {
            element.execute(this);
        } catch (TemplateException te) {
            handleTemplateException(te);
        } finally {
            popElement();
            currentScope = prevScope;
        }
    }

    /**
     * Visit a block using buffering/recovery
     */
    public void render(Block attemptBlock, Block recoveryBlock) throws IOException {
        Writer prevOut = this.out;
        StringWriter sw = new StringWriter();
        this.out = sw;
        TemplateException thrownException = null;
        try {
            render(attemptBlock);
        } catch (TemplateException te) {
            thrownException = te;
        } finally {
            this.out = prevOut;
        }
        if (thrownException != null) {
            try {
                recoveredErrorStack.add(thrownException.getMessage());
                render(recoveryBlock);
            } finally {
                recoveredErrorStack.remove(recoveredErrorStack.size() - 1);
            }
        } else {
            out.write(sw.toString());
        }
    }

    public String getCurrentRecoveredErrorMessage() {
        if (recoveredErrorStack.isEmpty()) {
            throw new TemplateException(
                    ".error is not available outside of a <#recover> block",
                    this);
        }
        return recoveredErrorStack.get(recoveredErrorStack.size() - 1);
    }

    public void render(NestedInstruction nestedInstruction) throws IOException {
        BlockScope blockScope = new BlockScope(currentMacroContext.getBody(), currentMacroContext.getInvokingScope());
        ParameterList bodyParameters = currentMacroContext.getBodyParameters();
        PositionalArgsList bodyArgs = (PositionalArgsList) nestedInstruction.getArgs();
        if (bodyParameters != null) {
            Map<String, Object> bodyParamsMap = bodyParameters.getParameterMap(bodyArgs, this, true);
            for (Map.Entry<String, Object> entry : bodyParamsMap.entrySet()) {
            	blockScope.put(entry.getKey(), entry.getValue());
            }
        }
        MacroContext invokingMacroContext = currentMacroContext;
        TemplateElement body = invokingMacroContext.getBody();
        if (body != null) {
            this.currentMacroContext = invokingMacroContext.getInvokingMacroContext();
            Configurable prevParent = getFallback();
            Scope prevScope = currentScope;
            setFallback(getCurrentNamespace().getTemplate());
            currentScope = blockScope;
            try {
                render(body);
            } finally {
                currentScope = prevScope;
                this.currentMacroContext = invokingMacroContext;
                setFallback(prevParent);
                this.currentScope = prevScope;
            }
        }
    }

    /**
     * Loop over a block, using the iterator passed in and
     * the given variable name for the loop variable.
     */
    public void process(Iterator<?> it, Block block, String loopVarName) throws IOException {
        Scope prevScope = currentScope;
        int index = 0;
        String hasNextName = loopVarName + "_has_next";
        String indexName = loopVarName + "_index";
        try {
            while (it.hasNext()) {
                currentScope = new BlockScope(block, prevScope);
                currentScope.put(loopVarName, wrap(it.next()));
                currentScope.put(hasNextName, it.hasNext());
                currentScope.put(indexName, index++);
                render(block);
            }
        } catch (BreakException br) {
        } catch (TemplateException te) {
            handleTemplateException(te);
        } finally {
            currentScope = prevScope;
        }
    }

    public void process(Object mapOrHash, Block block, String keyName, String valueName) throws IOException {
        Iterator it = null;
        TemplateHash hash = null;
        Map map = null;
        if (mapOrHash instanceof Map m) {
            it = m.keySet().iterator();
        }
        else {
            hash = (TemplateHash) mapOrHash;
            it = hash.keys().iterator();
        }
        Scope prevScope = currentScope;
        int index = 0;
        String keyHasNext = keyName + "_has_next";
        String valueHasNext = valueName + "_has_next";
        String keyIndexName = keyName + "_index";
        String valueIndexName = valueName + "_index";
        try {
            while (it.hasNext()) {
                currentScope = new BlockScope(block, prevScope);
                Object key = it.next();
                Object value = map != null ? map.get(key) : hash.get(key.toString());
                boolean hasNext = it.hasNext();
                currentScope.put(keyName, wrap(key));
                currentScope.put(valueName, wrap(value));
                currentScope.put(keyHasNext, hasNext);
                currentScope.put(valueHasNext, hasNext);
                currentScope.put(keyIndexName, index);
                currentScope.put(valueIndexName, index++);
                render(block);
            }
        } catch (BreakException br) {
        } catch (TemplateException te) {
            handleTemplateException(te);
        } finally {
            currentScope = prevScope;
        }
    }

    public <T> T runInScope(Scope scope, TemplateRunnable<T> runnable) throws IOException {
        Scope currentScope = this.currentScope;
        this.currentScope = scope;
        try {
            return runnable.run();
        } finally {
            this.currentScope = currentScope;
        }
    }

    /**
     * "visit" a macro.
     */
    public void render(Macro macro, ArgsList args, ParameterList bodyParameters, Block nestedBlock) throws IOException {
        if (macro == Macro.DO_NOTHING_MACRO) {
            return;
        }
        pushElement(macro);
        try {
            MacroContext mc = new MacroContext(macro, this, nestedBlock, bodyParameters);
            MacroContext prevMc = macroContextLookup.get(macro);
            macroContextLookup.put(macro, mc);
            if (args != null) {
                Map<String, Object> argsMap = macro.getParams().getParameterMap(args, this);
                for (Map.Entry<String, Object> entry : argsMap.entrySet()) {
                    mc.put(entry.getKey(), entry.getValue());
                }
            }
            Scope prevScope = currentScope;
            Configurable prevParent = getFallback();
            currentScope = currentMacroContext = mc;
            try {
                render(macro.getNestedBlock());                
            } catch (ReturnException re) {
            } catch (TemplateException te) {
                handleTemplateException(te);
            } finally {
                if (prevMc != null) {
                    macroContextLookup.put(macro, prevMc);
                } else {
                    macroContextLookup.remove(macro);
                }
                currentMacroContext = mc.getInvokingMacroContext();
                currentScope = prevScope;
                setFallback(prevParent);
            }
        } finally {
            popElement();
        }
    }

    public void visitMacroDef(Macro macro) {
        if (currentMacroContext == null) {
            macroToNamespaceLookup.put(macro, getCurrentNamespace());
            // getCurrentNamespace().put(macro.getName(), macro);
            this.unqualifiedSet(macro.getName(), macro);
        }
    }

    public Scope getMacroNamespace(Macro macro) {
        Scope result = macroToNamespaceLookup.get(macro);
        if (result == null) {
            result = mainNamespace; // REVISIT ??
        }
        return result;
    }

    public MacroContext getMacroContext(Macro macro) {
        return macroContextLookup.get(macro);
    }

    public MacroContext getCurrentMacroContext() {
        return currentMacroContext;
    }

    private void handleTemplateException(TemplateException te) {
        // Logic to prevent double-handling of the exception in
        // nested visit() calls.
        if (lastThrowable == te) {
            throw te;
        }
        lastThrowable = te;
        // An assertion failing is not passed to the handler, but
        // explicitly rethrown.
        if (te instanceof AssertionFailedException) {
            throw te;
        }
        // Finally, pass the exception to the handler
        getTemplateExceptionHandler().handleTemplateException(te, this, out);
    }

    public void setTemplateExceptionHandler(
            TemplateExceptionHandler templateExceptionHandler) {
        super.setTemplateExceptionHandler(templateExceptionHandler);
        lastThrowable = null;
    }

    public void setURLEscapingCharset(String urlEscapingCharset) {
        urlEscapingCharsetCached = false;
        super.setURLEscapingCharset(urlEscapingCharset);
    }

    /*
     * Note that although it is not allowed to set this setting with the
     * <tt>setting</tt>
     * directive, it still must be allowed to set it from Java code while the
     * template executes, since some frameworks allow templates to actually
     * change the output encoding on-the-fly.
     */
    public void setOutputEncoding(String outputEncoding) {
        urlEscapingCharsetCached = false;
        super.setOutputEncoding(outputEncoding);
    }

    /**
     * Returns the name of the charset that should be used for URL encoding.
     * This will be <code>null</code> if the information is not available. The
     * function caches the return value, so it is quick to call it repeately.
     */
    public String getEffectiveURLEscapingCharset() {
        if (!urlEscapingCharsetCached) {
            cachedURLEscapingCharset = getURLEscapingCharset();
            if (cachedURLEscapingCharset == null) {
                cachedURLEscapingCharset = getOutputEncoding();
            }
            urlEscapingCharsetCached = true;
        }
        return cachedURLEscapingCharset;
    }

    public Collator getCollator() {
        if (collator == null) {
            collator = Collator.getInstance(getLocale());
        }
        return collator;
    }

    public void setOut(Writer out) {
        this.out = out;
    }

    public Writer getOut() {
        return out;
    }

    public String formatNumber(Number number) {
        if (numberFormat == null) {
            numberFormat = getNumberFormatObject(getNumberFormat());
        }
        return numberFormat.format(number);
    }

    public void setNumberFormat(String formatName) {
        super.setNumberFormat(formatName);
        numberFormat = null;
    }

    public Configuration getConfiguration() {
        return getTemplate().getConfiguration();
    }

    public Object getLastReturnValue() {
        return lastReturnValue;
    }

    public void setLastReturnValue(Object lastReturnValue) {
        this.lastReturnValue = lastReturnValue;
    }

    public NumberFormat getNumberFormatObject(String pattern) {
        if (numberFormats == null) {
            numberFormats = new HashMap<String, NumberFormat>();
        }
        NumberFormat format = numberFormats.get(pattern);
        if (format != null) {
            return format;
        }

        // Get format from global format cache
        synchronized (localizedNumberFormats) {
            Locale locale = getLocale();
            NumberFormatKey fk = new NumberFormatKey(pattern, locale);
            format = localizedNumberFormats.get(fk);
            if (format == null) {
                // Add format to global format cache. Note this is
                // globally done once per locale per pattern.
                if ("number".equals(pattern)) {
                    format = NumberFormat.getNumberInstance(locale);
                } else if ("currency".equals(pattern)) {
                    format = NumberFormat.getCurrencyInstance(locale);
                } else if ("percent".equals(pattern)) {
                    format = NumberFormat.getPercentInstance(locale);
                } else if ("computer".equals(pattern)) {
                    format = getCNumberFormat();
                } else {
                    format = new DecimalFormat(pattern,
                            new DecimalFormatSymbols(getLocale()));
                }
                localizedNumberFormats.put(fk, format);
            }
        }

        // Clone it and store the clone in the local cache
        format = (NumberFormat) format.clone();
        numberFormats.put(pattern, format);
        return format;
    }

    /**
     * Returns the {@link NumberFormat} used for the <tt>c</tt> built-in.
     * This is always US English <code>"0.################"</code>, without
     * grouping and without superfluous decimal separator.
     */
    public NumberFormat getCNumberFormat() {
        // It can't be cached in a static field, because DecimalFormat-s aren't
        // thread-safe.
        if (cNumberFormat == null) {
            cNumberFormat = getNewCNumberFormat();
        }
        return cNumberFormat;
    }

    public static NumberFormat getNewCNumberFormat() {
        return (NumberFormat) C_NUMBER_FORMAT.clone();
    }

    /**
     * Returns the variable that is visible in this context. This is the
     * correspondent to an FTL top-level variable reading expression. That is,
     * it tries to find the the variable in this order:
     * <ol>
     * <li>An loop variable (if we're in a loop or user defined directive body)
     * such as foo_has_next
     * <li>A local variable (if we're in a macro)
     * <li>A variable defined in the current namespace (say, via &lt;#assign
     * ...&gt;)
     * <li>A variable defined globally (say, via &lt;#global ....&gt;)
     * <li>Variable in the data model:
     * <ol>
     * <li>A variable in the root hash that was exposed to this rendering
     * environment in the Template.process(...) call
     * <li>A shared variable set in the configuration via a call to
     * Configuration.setSharedVariable(...)
     * </ol>
     * </li>
     * </ol>
     */
    public Object getVariable(String name) {
        return currentScope.resolveVariable(name);
    }

    /**
     * This method returns a variable from the "global" namespace and falls back
     * to the data model.
     */
    public Object get(Object name) {
        Object result = globalVariables.get(name);
        if (result == null) {
            result = rootDataModel.get(name);
        }
        if (result == null) {
            result = getConfiguration().getSharedVariable(name.toString());
        }
        return result;
    }

    /**
     * Sets a variable that is visible globally. This is correspondent to FTL
     * <code><#global <i>name</i>=<i>model</i>></code>.
     */
    public void setGlobalVariable(String name, Object value) {
        globalVariables.put(name, value);
    }

    /**
     * Sets a variable in the current namespace. This corresponds to the
     * legacy <code><#assign <i>name</i>=<i>model</i>></code>. This can be
     * considered a convenient shorthand for: getCurrentNamespace().put(name,
     * model)
     */
    private void setVariable(String name, Object value) {
        getCurrentNamespace().put(name, value);
    }

    /**
     * Sets a variable in the most local scope available (corresponds to an
     * unqualified #set instruction)
     * 
     * @param name the identifier of the variable
     * @param value the value of the variable
     */
    public void unqualifiedSet(String name, Object value) {
        Scope scope = this.currentScope;
        while (!scope.isTemplateNamespace()) {
            if (scope.get(name) != null) {
                scope.put(name, value);
                return;
            }
            scope = scope.getEnclosingScope();
        }
        try {
            scope.put(name, value);
        } catch (UndeclaredVariableException uve) {
            if (globalVariables.containsKey(name)) {
                globalVariables.put(name, value);
            } else {
                throw uve;
            }
        }
    }

    /**
     * Outputs the instruction stack. Useful for debugging.
     * {@link TemplateException}s incorporate this information in their stack
     * traces.
     * 
     * @see #getElementStack() which exposes the actual element stack
     *      so that you can write your own custom stack trace or error message
     */
    public void outputInstructionStack(PrintWriter pw) {
        pw.println("----------");
        ListIterator<TemplateElement> iter = elementStack
                .listIterator(elementStack.size());
        if (iter.hasPrevious()) {
            pw.print("==> ");
            TemplateElement prev = iter.previous();
            pw.print(prev.getDescription());
            pw.print(" [");
            pw.print(prev.getLocation());
            pw.println("]");
        }
        while (iter.hasPrevious()) {
            TemplateElement prev = iter.previous();
            if (prev instanceof UnifiedCall || prev instanceof IncludeInstruction) {
                String location = prev.getDescription() + " ["
                        + prev.getLocation() + "]";
                if (location != null && location.length() > 0) {
                    pw.print(" in ");
                    pw.println(location);
                }
            }
        }
        pw.println("----------");
        pw.flush();
    }

    public Environment getEnvironment() {
        return this;
    };

    /**
     * @return null This is the final fallback scope. It has no
     *         enclosing scope.
     */
    public Scope getEnclosingScope() {
        return null;
    }

    public boolean definesVariable(String name) {
        return globalVariables.containsKey(name) || rootDataModel.get(name) != null;
    }

    public Object put(String varname, Object value) {
        return globalVariables.put(varname, value);
    }

    public Object remove(Object varname) {
        return globalVariables.remove(varname);
    }

    /**
     * Returns the main name-space. This is correspondent of FTL
     * <code>.main</code> hash.
     */
    public Scope getMainNamespace() {
        return mainNamespace;
    }

    /**
     * Returns the current name-space. This is correspondent of FTL
     * <code>.namespace</code> hash.
     */
    public Scope getCurrentNamespace() {
        Scope scope = currentScope;
        while (scope.getEnclosingScope() != this) {
            scope = scope.getEnclosingScope();
        }
        return scope;
    }

    /**
     * Returns the data model hash. This is correspondent of FTL
     * <code>.datamodel</code> hash. That is, it contains both the variables
     * of the root hash passed to the <code>Template.process(...)</code>, and
     * the shared variables in the <code>Configuration</code>.
     */
    public TemplateHash getDataModel() {
        final TemplateHash result = new TemplateHash() {
            public boolean isEmpty() {
                return false;
            }
            public Object get(String key) {
                Object value = rootDataModel.get(key);
                if (value == null) {
                    value = getConfiguration().getSharedVariable(key);
                }
                return value;
            }
        };
        return result;
    }

    public List<TemplateElement> getElementStack() {
        return Collections.unmodifiableList(elementStack);
    }

    private void pushElement(TemplateElement element) {
        elementStack.add(element);
    }

    private void popElement() {
        elementStack.remove(elementStack.size() - 1);
    }

    /**
     * Emulates <code>include</code> directive, except that <code>name</code>
     * must be tempate root relative.
     * 
     * <p>
     * It's the same as
     * <code>include(getTemplateForInclusion(name, encoding, parse))</code>.
     * But, you may want to separately call these two methods, so you can
     * determine the source of exceptions more precisely, and thus achieve more
     * intelligent error handling.
     * 
     * @see #getTemplateForInclusion(String name, String encoding, boolean
     *      parse)
     * @see #include(Template includedTemplate, boolean freshNamespace)
     */
    public void include(String name, String encoding, boolean parse) throws IOException {
        include(getTemplateForInclusion(name, encoding, parse), false);
    }

    /**
     * Gets a template for inclusion; used with
     * {@link #include(Template includedTemplate, boolean freshNamespace)}. The
     * advantage over simply
     * using <code>config.getTemplate(...)</code> is that it chooses the
     * default encoding as the <code>include</code> directive does.
     * 
     * @param name
     *                 the name of the template, relatively to the template root
     *                 directory (not the to the directory of the currently
     *                 executing
     *                 template file!). (Note that you can use
     *                 {@link org.congocc.templates.cache.TemplateCache#getFullTemplatePath} to
     *                 convert paths to template root relative paths.)
     * @param encoding
     *                 the encoding of the obtained template. If null, the encoding
     *                 of the Template that is currently being processed in this
     *                 Environment is used.
     * @param parse
     *                 whether to process a parsed template or just include the
     *                 unparsed template source.
     */
    public Template getTemplateForInclusion(String name, String encoding, boolean parse) throws IOException {
        if (encoding == null) {
            encoding = getTemplate().getEncoding();
        }
        if (encoding == null) {
            encoding = getConfiguration().getEncoding(this.getLocale());
        }
        return getConfiguration().getTemplate(name, getLocale(), encoding,
                parse);
    }

    /**
     * Processes a Template in the context of this <code>Environment</code>,
     * including its output in the <code>Environment</code>'s Writer.
     * 
     * @param includedTemplate
     *                         the template to process. Note that it does
     *                         <em>not</em> need
     *                         to be a template returned by
     *                         {@link #getTemplateForInclusion(String name, String encoding, boolean parse)}.
     */
    public void include(Template includedTemplate, boolean freshNamespace) throws IOException {
        Template prevTemplate = getTemplate();
        setFallback(includedTemplate);
        Scope prevScope = this.currentScope;
        if (freshNamespace) {
            this.currentScope = new BlockScope(includedTemplate.getRootElement(), this);
            importMacros(includedTemplate);
        } else {
            this.currentScope = getCurrentNamespace();
            importMacros(includedTemplate);
        }
        try {
            render(includedTemplate.getRootElement());
        } finally {
            this.currentScope = prevScope;
            setFallback(prevTemplate);
        }
    }

    /**
     * Emulates <code>import</code> directive, except that <code>name</code>
     * must be tempate root relative.
     * 
     * <p>
     * It's the same as
     * <code>importLib(getTemplateForImporting(name), namespace)</code>. But,
     * you may want to separately call these two methods, so you can determine
     * the source of exceptions more precisely, and thus achieve more
     * intelligent error handling.
     * 
     * @see #getTemplateForImporting(String name)
     * @see #importLib(Template includedTemplate, String namespace, boolean global)
     */
    public Scope importLib(String name, String namespace) throws IOException {
        return importLib(getTemplateForImporting(name), namespace, true);
    }

    /**
     * Gets a template for importing; used with
     * {@link #importLib(Template importedTemplate, String namespace, boolean global)}.
     * The
     * advantage over simply using <code>config.getTemplate(...)</code> is
     * that it chooses the encoding as the <code>import</code> directive does.
     * 
     * @param name
     *             the name of the template, relatively to the template root
     *             directory (not the to the directory of the currently executing
     *             template file!). (Note that you can use
     *             {@link org.congocc.templates.cache.TemplateCache#getFullTemplatePath} to
     *             convert paths to template root relative paths.)
     */
    public Template getTemplateForImporting(String name) throws IOException {
        return getTemplateForInclusion(name, null, true);
    }

    /**
     * Emulates <code>import</code> directive.
     * 
     * @param loadedTemplate
     *                       the template to import. Note that it does <em>not</em>
     *                       need
     *                       to be a template returned by
     *                       {@link #getTemplateForImporting(String name)}.
     */
    public Scope importLib(Template loadedTemplate, String namespace, boolean global) throws IOException {
        if (loadedLibs == null) {
            loadedLibs = new HashMap<>();
        }
        String templateName = loadedTemplate.getName();
        Scope existingNamespace = loadedLibs.get(templateName);
        if (existingNamespace != null) {
            if (namespace != null) {
                setVariable(namespace, existingNamespace);
            }
        } else {
            Scope newNamespace = new BlockScope(loadedTemplate.getRootElement(), this);
            if (namespace != null) {
                if (global) {
                    setGlobalVariable(namespace, newNamespace);
                } else {
                    setVariable(namespace, newNamespace);
                }
                if (getCurrentNamespace() == mainNamespace) {
                    // We make libs imported into the main namespace globally visible
                    // for least surprise reasons. (Is this right???)
                    this.put(namespace, newNamespace);
                }
            }
            loadedLibs.put(templateName, newNamespace);
            Scope prevScope = currentScope;
            currentScope = newNamespace;
            Writer prevOut = out;
            Configurable prevParent = getFallback();
            this.out = NULL_WRITER;
            setFallback(loadedTemplate);
            try {
                render(loadedTemplate.getRootElement());
            } finally {
                this.out = prevOut;
                currentScope = prevScope;
                setFallback(prevParent);
            }
        }
        return loadedLibs.get(templateName);
    }

    public String renderElementToString(TemplateElement te) throws IOException {
        Writer prevOut = out;
        try {
            StringWriter sw = new StringWriter();
            this.out = sw;
            render(te);
            return sw.toString();
        } finally {
            this.out = prevOut;
        }
    }

    private void importMacros(Template template) {
        for (Macro macro : template.getMacros().values()) {
            visitMacroDef(macro);
        }
    }

    private static final class NumberFormatKey {
        private final String pattern;

        private final Locale locale;

        NumberFormatKey(String pattern, Locale locale) {
            this.pattern = pattern;
            this.locale = locale;
        }

        public boolean equals(Object o) {
            if (o instanceof NumberFormatKey fk) {
                return fk.pattern.equals(pattern) && fk.locale.equals(locale);
            }
            return false;
        }

        public int hashCode() {
            return pattern.hashCode() ^ locale.hashCode();
        }
    }

    static public final Writer NULL_WRITER = new Writer() {
        public void write(char cbuf[], int off, int len) {}
        
        public void flush() {}

        public void close() {}
    };
}
