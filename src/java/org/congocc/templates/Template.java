package org.congocc.templates;

import java.io.IOException;
import java.util.*;

import org.congocc.templates.core.ArithmeticEngine;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.Block;
import org.congocc.templates.core.nodes.generated.ImportDeclaration;
import org.congocc.templates.core.nodes.generated.Macro;
import org.congocc.templates.core.nodes.generated.TemplateElement;
import org.congocc.templates.core.nodes.generated.TemplateHeaderElement;
import org.congocc.templates.core.parser.*;

public class Template {
    private Block rootElement;
    private Map<String, Macro> macros = new HashMap<String, Macro>();
    private List<ImportDeclaration> imports = new ArrayList<>();
    private String encoding = "UTF-8";
    private String name = "template";

    private List<ParsingProblemImpl> parsingProblems = new ArrayList<>();
    private TemplateHeaderElement headerElement;

    private long lastModified;
    private TemplateFactory factory;
    private ArithmeticEngine arithmeticEngine;
    private Locale locale;
    private String numberFormat;

    /**
     * A prime constructor to which all other constructors should
     * delegate directly or indirectly.
     */
    protected Template(String name, TemplateFactory factory)
    {
        this.name = name;
        this.factory = factory;
        this.lastModified = System.currentTimeMillis();
    }

	public Template(String name, CharSequence input, TemplateFactory cfg)
    {
        this(name, cfg);
        CTLParser parser = new CTLParser(this, input);
        parser.setInputSource(getName());
        this.rootElement = parser.Root();
        PostParseVisitor ppv = new PostParseVisitor(this);
        ppv.visit(this);
	}

    public Template(String content) {
        CTLParser parser = new CTLParser(this, content);
        this.rootElement = parser.Root();
        new PostParseVisitor(this).visit(this);
    }

    public TemplateFactory getTemplateFactory() {
        return factory;
    }

    public ArithmeticEngine getArithmeticEngine() {
        if (arithmeticEngine != null) {
            return arithmeticEngine;
        }
        return factory.getArithmeticEngine();
    }

    public Locale getLocale() {
        if (this.locale != null) {
            return locale;
        }
        return factory.getLocale();
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public String getNumberFormat() {
        if (numberFormat == null) {
            return factory.getNumberFormat();
        }
        return numberFormat;
    }

    public void setNumberFormat(String numberFormat) {
        this.numberFormat = numberFormat;
    }

    /**
     * Processes the template, using data from the map, and
     * returns the output.
     * @param rootMap the root node of the data model.
     * @return the result of the template processing job
     * @throws TemplateException if an exception occurs during template processing
     * @throws IOException if an I/O exception occurs
     */
    public String process(Map<String,Object> rootMap) throws IOException
    {
        Environment env = new Environment(this, rootMap);
        getConfiguration().doAutoImportsAndIncludes(env);
        env.process();
        return env.getOutput();
    }

    /**
     * Processes the template, using data from the map, and outputs
     * the resulting text to the supplied <tt>Appendable</tt>
     * @param rootMap the root node of the data model.
     * @throws TemplateException if an exception occurs during template processing
     * @throws IOException if an I/O exception occurs
     */
    public void process(Map<String,Object> rootMap, Appendable appendable) throws IOException
    {
        Environment env = new Environment(this, rootMap);
        env.setLocale(getLocale());
        env.setBuffer(appendable);
        getConfiguration().doAutoImportsAndIncludes(env);
        env.process();
    }

    /**
     * The path of the template file relative to the directory what you use to store the templates.
     * For example, if the real path of template is <tt>"/www/templates/community/forum.fm"</tt>,
     * and you use "<tt>"/www/templates"</tt> as
     * {@link TemplateFactory#setDirectoryForTemplateLoading "directoryForTemplateLoading"},
     * then <tt>name</tt> should be <tt>"community/forum.fm"</tt>. The <tt>name</tt> is used for example when you
     * use <tt>&lt;include ...></tt> and you give a path that is relative to the current
     * template, or in error messages when the template engine logs an error while it processes the template.
     */
    public String getName() {
        return name;
    }

    public TemplateFactory getConfiguration() {
        return factory;
    }

    public List<ParsingProblemImpl> getParsingProblems() {
    	return parsingProblems;
    }

    public boolean hasParsingProblems() {
    	return !parsingProblems.isEmpty();
    }

    public void addParsingProblem(ParsingProblemImpl problem) {
    	parsingProblems.add(problem);
    }

    /**
     * Returns the character encoding used for reading included files.
     */
    public String getEncoding() {
        return this.encoding;
    }

    /**
     * Called by code internally to maintain
     * a list of imports
     */
    public void addImport(ImportDeclaration id) {
        imports.add(id);
    }

    public void setHeaderElement(TemplateHeaderElement headerElement) {
    	this.headerElement = headerElement;
    }

    public TemplateHeaderElement getHeaderElement() {
    	return headerElement;
    }

    public boolean declaresVariable(String name) {
        return getRootElement().declaresVariable(name);
    }

    public void declareVariable(String name) {
        getRootElement().declareVariable(name);
    }

    /**
     *  @return the root TemplateElement object.
     */
    public TemplateElement getRootTreeNode() {
        return getRootElement();
    }

    public List<ImportDeclaration> getImports() {
        return imports;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Called by code internally to maintain
     * a table of macros
     */
    public void addMacro(Macro macro) {
        String macroName = macro.getName();
        synchronized(macros) {
            macros.put(macroName, macro);
        }
    }

    public Map<String,Macro> getMacros() {
        return macros;
    }

    public Block getRootElement() {
        return rootElement;
    }
}
