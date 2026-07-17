package org.congocc.templates;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

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
    private String name;

    private List<ParsingProblemImpl> parsingProblems = new ArrayList<>();

    private long lastModified;
    private TemplateFactory factory;
    private ArithmeticEngine arithmeticEngine;
    private Locale locale;
    private String numberFormat;
    private Function<String,String> outputEscape;

	Template(String name, CharSequence content, TemplateFactory factory)
    {
        this.name = name;
        this.factory = factory;
        CTLParser parser = new CTLParser(this, content);
        parser.setInputSource(getName());
        this.rootElement = parser.Root();
        new PostParseVisitor(this).visit();
	}

    public Template(String name, CharSequence content) {
        this(name, content, TemplateFactory.getDefault());
    }

    public Template(CharSequence content) {
        this("template", content, TemplateFactory.getDefault());
    }

    public TemplateFactory getTemplateFactory() {
        return factory;
    }

    public ArithmeticEngine getArithmeticEngine() {
        if (arithmeticEngine != null) {
            return arithmeticEngine;
        }
        return getTemplateFactory().getArithmeticEngine();
    }

    public Function<String,String> getOutputEscape() {
        if (outputEscape == null) {
            return getTemplateFactory().getOutputEscape();
        }
        return outputEscape;
    }

    public void setOutputEscape(Function<String,String> outputEscape) {
        this.outputEscape = outputEscape;
    }

    public Locale getLocale() {
        if (this.locale != null) {
            return locale;
        }
        return getTemplateFactory().getLocale();
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public String getNumberFormat() {
        if (numberFormat == null) {
            return getTemplateFactory().getNumberFormat();
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
        getTemplateFactory().doAutoImports(env);
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
        getTemplateFactory().doAutoImports(env);
        env.process();
    }

    public String getName() {
        return name;
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
     * Called by code internally to maintain
     * a list of imports
     */
    public void addImport(ImportDeclaration id) {
        imports.add(id);
    }

    public TemplateHeaderElement getHeaderElement() {
    	return rootElement.firstChildOfType(TemplateHeaderElement.class);
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

    long getLastModified() {
        return lastModified;
    }

    void setLastModified(long lastModified) {
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
