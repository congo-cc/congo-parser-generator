package org.congocc.templates;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import org.congocc.templates.core.Configurable;
import org.congocc.templates.core.Environment;
import org.congocc.templates.core.nodes.generated.Block;
import org.congocc.templates.core.nodes.generated.ImportDeclaration;
import org.congocc.templates.core.nodes.generated.Macro;
import org.congocc.templates.core.nodes.generated.TemplateElement;
import org.congocc.templates.core.nodes.generated.TemplateHeaderElement;
import org.congocc.templates.core.parser.*;

/**
 * <p>A core Congo Templates API that represents a compiled template.
 * Typically, you will use a {@link Configuration} object to instantiate a template.
 *
 * <PRE>
      Configuration cfg = new Configuration();
      ...
      Template myTemplate = cfg.getTemplate("myTemplate.html");
   </PRE>
 *
 * <P>However, you can also construct a template directly by passing in to
 * the appropriate constructor a java.lang.CharSequence instance that contains
 * the raw template text. The compiled template is
 * stored in an an efficient data structure for later use.
 *
 * <p>To render the template, i.e. to merge it with a data model, and
 * thus produce "cooked" output, call the <tt>process</tt> method.
 *
 * <p>Any error messages from exceptions thrown during compilation will be
 * included in the output stream and thrown back to the calling code.
 * To change this behavior, you can install custom exception handlers using
 * {@link Configurable#setTemplateExceptionHandler(TemplateExceptionHandler)} on
 * a Configuration object (for all templates belonging to a configuration) or on
 * a Template object (for a single template).
 *
 * <p>It's not legal to modify the values of the Template engine settings: a) while the
 * template is executing; b) if the template object is already accessible from
 * multiple threads.
 *
 * @version $Id: Template.java,v 1.218 2005/12/07 00:31:18 revusky Exp $
 */

public class Template extends Configurable {
    private Block rootElement;
    private Map<String, Macro> macros = new HashMap<String, Macro>();
    private List<ImportDeclaration> imports = new ArrayList<>();
    private String encoding = "UTF-8";
    private String name = "template";

    private List<ParsingProblemImpl> parsingProblems = new ArrayList<>();
    private TemplateHeaderElement headerElement;

    private long lastModified;

    /**
     * A prime constructor to which all other constructors should
     * delegate directly or indirectly.
     */
    protected Template(String name, Configuration cfg)
    {
        super(cfg);
        this.name = name;
        this.lastModified = System.currentTimeMillis();
    }

	public Template(String name, CharSequence input, Configuration cfg,
			String encoding) throws IOException
    {
        this(name, cfg);
        this.encoding = encoding;
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

    /**
     * Returns a trivial template, one that is just a single block of
     * plain text, no dynamic content. (Used by the cache module to create
     * unparsed templates.)
     * @param name the path of the template file relative to the directory what you use to store
     *        the templates. See {@link #getName} for more details.
     * @param content the block of text that this template represents
     * @param config the configuration to which this template belongs
     */
    static public Template getPlainTextTemplate(String name, String content,
            Configuration config) {
        Template template = new Template(name, config);
        template.rootElement = new Block() {
        	public void execute(Environment env) throws IOException {
        		env.getOut().write(content);
                env.getBuffer().append(content);
        	}
        };
        return template;
    }

    /**
     * Processes the template, using data from the map, and outputs
     * the resulting text to the supplied <tt>Writer</tt>
     * @param rootMap the root node of the data model.
     * @param out a <tt>Writer</tt> to output the text to.
     * @throws TemplateException if an exception occurs during template processing
     * @throws IOException if an I/O exception occurs during writing to the writer.
     */
    public void process(Map<String,Object> rootMap, Writer out) throws IOException
    {
        new Environment(this, rootMap, out).process();
    }

    /**
     * The path of the template file relative to the directory what you use to store the templates.
     * For example, if the real path of template is <tt>"/www/templates/community/forum.fm"</tt>,
     * and you use "<tt>"/www/templates"</tt> as
     * {@link Configuration#setDirectoryForTemplateLoading "directoryForTemplateLoading"},
     * then <tt>name</tt> should be <tt>"community/forum.fm"</tt>. The <tt>name</tt> is used for example when you
     * use <tt>&lt;include ...></tt> and you give a path that is relative to the current
     * template, or in error messages when the template engine logs an error while it processes the template.
     */
    public String getName() {
        return name;
    }

    public Configuration getConfiguration() {
        Configuration config = (Configuration)getFallback();
        if (config == null) {
            config = Configuration.getDefaultConfiguration();
        }
        return config;
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
     * Sets the character encoding to use for
     * included files. Usually you don't set this value manually,
     * instead it is assigned to the template upon loading.
     */

    public void setEncoding(String encoding) {
        this.encoding = encoding;
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

    static public class WrongEncodingException extends RuntimeException {

        public String specifiedEncoding;

        public WrongEncodingException(String specifiedEncoding) {
            this.specifiedEncoding = specifiedEncoding;
        }

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
