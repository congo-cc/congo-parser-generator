package org.congocc.codegen;

import java.io.IOException;
import java.io.Writer;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.congocc.app.AppSettings;
import org.congocc.app.Errors;
import org.congocc.core.Grammar;
import org.congocc.core.RegularExpression;
import org.congocc.codegen.java.*;
import org.congocc.codegen.csharp.CSharpFormatter;
import org.congocc.parser.*;
import org.congocc.parser.tree.CompilationUnit;
import org.congocc.parser.tree.ObjectType;

import freemarker.template.*;
import freemarker.cache.*;

public class FilesGenerator {

    private final Configuration fmConfig = new freemarker.template.Configuration();
    private final Grammar grammar;
    // private final LexerData lexerData;
    private final AppSettings appSettings;
    private final Errors errors;
    private final CodeInjector codeInjector;
    private final Set<String> tokenSubclassFileNames = new LinkedHashSet<>();
    private final Map<String, String> superClassLookup = new HashMap<>();
    private final String codeLang;
    private final boolean generateRootApi;

    void initializeTemplateEngine() throws IOException {
        Path filename = appSettings.getFilename().toAbsolutePath();
        Path dir = filename.getParent();
        //
        // The first two loaders are really for developers - templates
        // are looked for in the grammar's directory, and then in a
        // 'templates' subdirectory below that, which could, of course, be
        // a symlink to somewhere else.
        // We check for the 'templates' subdirectory existing, because otherwise
        // FreeMarker will raise an exception.
        //
        TemplateLoader templateLoader;
        String templateFolder = "/templates/".concat(codeLang);
        Path altDir = dir.resolve(templateFolder.substring(1));
        ArrayList<TemplateLoader> loaders = new ArrayList<>();
        loaders.add(new FileTemplateLoader(dir.toFile()));
        if (Files.exists(altDir)) {
            loaders.add(new FileTemplateLoader(altDir.toFile()));
        }
        loaders.add(new ClassTemplateLoader(this.getClass(), templateFolder));
        templateLoader = new MultiTemplateLoader(loaders.toArray(new TemplateLoader[0]));

        fmConfig.setTemplateLoader(templateLoader);
        //fmConfig.setObjectWrapper(new BeansWrapper());
        fmConfig.setNumberFormat("computer");
        fmConfig.setArithmeticEngine(freemarker.core.ArithmeticEngine.CONSERVATIVE_ENGINE);
        //fmConfig.setStrictVariableDefinition(true);
        fmConfig.setSharedVariable("grammar", grammar);
        fmConfig.setSharedVariable("globals", grammar.getTemplateGlobals());
        fmConfig.setSharedVariable("settings", grammar.getAppSettings());
        fmConfig.setSharedVariable("lexerData", grammar.getLexerData());
        fmConfig.setSharedVariable("generated_by", org.congocc.app.Main.PROG_NAME);
        if (codeLang.equals("java"))
           fmConfig.addAutoImport("CU", "CommonUtils.java.ftl");
    }

    public FilesGenerator(Grammar grammar) {
        this.grammar = grammar;
        // this.lexerData = grammar.getLexerData();
        this.appSettings = grammar.getAppSettings();
        this.codeLang = appSettings.getCodeLang();
        this.errors = grammar.getErrors();
        this.generateRootApi = appSettings.getRootAPIPackage() == null; 
        this.codeInjector = grammar.getInjector();
    }

    public void generateAll() throws IOException { 
        if (errors.getErrorCount() != 0) {
            throw new ParseException();
        }
        initializeTemplateEngine();
        switch (codeLang) {
            case "java":
                generateToken();
                generateLexer();
                generateOtherFiles();
                if (!grammar.getProductionTable().isEmpty()) {
                    if (generateRootApi) {
                       generateParseException();
                    }
                    generateParser();
                }
                if (appSettings.getFaultTolerant() && generateRootApi) {
                    generateInvalidNode();
                    generateParsingProblem();
                }
                if (appSettings.getTreeBuildingEnabled()) {
                    generateTreeBuildingFiles();
                }
                break;
            case "python": 
                // Hardcoded for now, could make configurable later
                String[] paths = new String[]{
                        "__init__.py",
                        "utils.py",
                        "tokens.py",
                        "lexer.py",
                        "parser.py"
                };
                Path outDir = appSettings.getParserOutputDirectory();
                for (String p : paths) {
                    Path outputFile = outDir.resolve(p);
                    // Could check if regeneration is needed, but for now
                    // always (re)generate
                    generate(outputFile);
                }
                break;
            case "csharp": 
                // Hardcoded for now, could make configurable later
                paths = new String[]{
                        "Utils.cs",
                        "Tokens.cs",
                        "Lexer.cs",
                        "Parser.cs",
                        null  // filled in below
                };
                String csPackageName = grammar.getTemplateGlobals().getPreprocessorSymbol("cs.package", appSettings.getParserPackage());
                paths[paths.length - 1] = csPackageName + ".csproj";
                outDir = appSettings.getParserOutputDirectory();
                for (String p : paths) {
                    Path outputFile = outDir.resolve(p);
                    // Could check if regeneration is needed, but for now
                    // always (re)generate
                    generate(outputFile);
                }
                break;
            default:
                throw new UnsupportedOperationException(String.format("Code generation in '%s' is currently not supported.", codeLang));
        }
    }

    public void generate(Path outputFile) throws IOException {
        generate(null, outputFile);
    }

    private final Set<String> nonNodeNames = new LinkedHashSet<String>() {
        {
            add("ParseException.java");
            add("ParsingProblem.java");
            add("Token.java");
            add("InvalidToken.java");
            add("Node.java");
//            add("InvalidNode.java");
            add("TokenSource.java");
            add("NonTerminalCall.java");
        }
    };

    private String getTemplateName(String outputFilename) {
        String result = outputFilename + ".ftl";
        if (codeLang.equals("java")) {
            if (outputFilename.equals(appSettings.getBaseTokenClassName() + ".java")) {
                result = "Token.java.ftl";
            } else if (tokenSubclassFileNames.contains(outputFilename)) {
                result = "ASTToken.java.ftl";
            } else if (outputFilename.equals(appSettings.getParserClassName() + ".java")) {
                result = "Parser.java.ftl";
            } else if (outputFilename.endsWith("Lexer.java")
                    || outputFilename.equals(appSettings.getLexerClassName() + ".java")) {
                result = "Lexer.java.ftl";
            } else if (outputFilename.equals(appSettings.getBaseNodeClassName() + ".java")) {
                result = "BaseNode.java.ftl";
            }
            else if (outputFilename.startsWith(appSettings.getNodePrefix())) {
                if (!nonNodeNames.contains(outputFilename) && !outputFilename.equals(appSettings.getBaseTokenClassName()+".java")) {
                    result = "ASTNode.java.ftl";
                }
            } 
        }
        else if (codeLang.equals("csharp")) {
            if (outputFilename.endsWith(".csproj")) {
                result = "project.csproj.ftl";
            }
        }
        return result;
    }

    public void generate(String nodeName, Path outputFile) throws IOException {
        String currentFilename = outputFile.getFileName().toString();
        String templateName = getTemplateName(currentFilename);
        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("filename", currentFilename);
        dataModel.put("isAbstract", grammar.nodeIsAbstract(nodeName));
        dataModel.put("isInterface", grammar.nodeIsInterface(nodeName));
        dataModel.put("isFinal", codeInjector.isFinal(nodeName));
        dataModel.put("isSealed", codeInjector.isSealed(nodeName));
        dataModel.put("isNonSealed", codeInjector.isNonSealed(nodeName));
        String key = appSettings.getNodePackage() + "." + nodeName;
        Set<ObjectType> permitsList = codeInjector.getPermitsList(key);
        if (permitsList == null) {
            dataModel.put("permitsList", new ArrayList<>());
        } else {
           dataModel.put("permitsList", codeInjector.getPermitsList(key));
        }
        String classname = currentFilename.substring(0, currentFilename.length() - 5);
        String superClassName = superClassLookup.get(classname);
        if (superClassName == null) superClassName = appSettings.getBaseTokenClassName();
        dataModel.put("superclass", superClassName);
        Writer out = new StringWriter();
        Template template = fmConfig.getTemplate(templateName);
        // Sometimes needed in templates for e.g. injector.hasInjectedCode(node)
        dataModel.put("injector", grammar.getInjector());
        template.process(dataModel, out);
        String code = out.toString();
        if (!appSettings.isQuiet()) {
            System.out.println("Outputting: " + outputFile.normalize());
        }
        if (outputFile.getFileName().toString().endsWith(".java")) {
            outputJavaFile(code, outputFile);
        } 
        else if (outputFile.getFileName().toString().endsWith(".cs")) {
            outputCSharpFile(code, outputFile);
        }
        else try (Writer outfile = Files.newBufferedWriter(outputFile)) {
            outfile.write(code);
        }
    }

    void outputCSharpFile(String code, Path outputFile) throws IOException {
        org.congocc.parser.csharp.ast.CompilationUnit cscu;
        Writer out = Files.newBufferedWriter(outputFile);
        try {        
           cscu = CongoCCParser.parseCSharpFile(outputFile.getFileName().toString(), code);
        } catch (Exception e) {
            out.write(code);
            return;
        } finally {
            out.flush();
            out.close();
        }
        try (Writer output = Files.newBufferedWriter(outputFile)) {
            CSharpFormatter formatter = new CSharpFormatter();
            formatter.visit(cscu);
            output.write(formatter.getText());
        }
    }

    void outputJavaFile(String code, Path outputFile) throws IOException {
        Path dir = outputFile.getParent();
        if (Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        CompilationUnit jcu;
        Writer out = Files.newBufferedWriter(outputFile);
        try {
            jcu = CongoCCParser.parseJavaFile(outputFile.getFileName().toString(), code);
        } catch (Exception e) {
            out.write(code);
            return;
        } finally {
            out.flush();
            out.close();
        }
        try (Writer output = Files.newBufferedWriter(outputFile)) {
            codeInjector.injectCode(jcu);
            JavaCodeUtils.removeWrongJDKElements(jcu, grammar.getAppSettings().getJdkTarget());
            JavaCodeUtils.addGetterSetters(jcu);
            JavaCodeUtils.stripUnused(jcu);
            JavaFormatter formatter = new JavaFormatter();
            output.write(formatter.format(jcu));
        }
    }

    void generateOtherFiles() throws IOException {
        if (generateRootApi) {
            Path outputFile = appSettings.getParserOutputDirectory().resolve("TokenSource.java");
            generate(outputFile);
            outputFile = appSettings.getParserOutputDirectory().resolve("NonTerminalCall.java");
            generate(outputFile);
        }
    }

    void generateParseException() throws IOException {
        Path outputFile = appSettings.getParserOutputDirectory().resolve("ParseException.java");
        if (regenerate(outputFile)) {
            generate(outputFile);
        }
    }

    void generateParsingProblem() throws IOException {
        Path outputFile = appSettings.getParserOutputDirectory().resolve("ParsingProblem.java");
        if (regenerate(outputFile)) {
            generate(outputFile);
        }
    }

    void generateInvalidNode() throws IOException {
        Path outputFile = appSettings.getNodeOutputDirectory().resolve("InvalidNode.java");
        if (regenerate(outputFile)) {
            generate(outputFile);
        }
    }

    void generateToken() throws IOException {
        String filename = appSettings.getBaseTokenClassName() + ".java";
        Path outputFile = appSettings.getParserOutputDirectory().resolve(filename);
        if (regenerate(outputFile)) {
            generate(outputFile);
        }
        outputFile = appSettings.getParserOutputDirectory().resolve("InvalidToken.java");
        if (regenerate(outputFile)) {
            generate(outputFile);
        }
    }
    
    void generateLexer() throws IOException {
        String filename = appSettings.getLexerClassName() + ".java";
        Path outputFile = appSettings.getParserOutputDirectory().resolve(filename);
        generate(outputFile);
    }

    void generateParser() throws IOException {
        if (errors.getErrorCount() !=0) {
        	throw new ParseException();
        }
        String filename = appSettings.getParserClassName() + ".java";
        Path outputFile = appSettings.getParserOutputDirectory().resolve(filename);
        generate(outputFile);
    }
    
    void generateNodeFile() throws IOException {
        Path outputFile = appSettings.getParserOutputDirectory().resolve("Node.java");
        if (regenerate(outputFile)) {
            generate(outputFile);
        }
    }

    private boolean regenerate(Path file) throws IOException {
        if (!Files.exists(file)) {
        	return true;
        } 
        String ourName = file.getFileName().toString();
        String canonicalName = file.normalize().getFileName().toString();
       	if (canonicalName.equalsIgnoreCase(ourName) && !canonicalName.equals(ourName)) {
            String msg = "You cannot have two files that differ only in case, as in " 
       	                          + ourName + " and "+ canonicalName 
       	                          + "\nThis does work on a case-sensitive file system but fails on a case-insensitive one (i.e. Mac/Windows)"
       	                          + " \nYou will need to rename something in your grammar!";
            throw new IOException(msg);
        }
        String filename = file.getFileName().toString();
        // Changes here to allow different rules to be used for different
        // languages. At the moment there are no non-Java code injections
        String extension = codeLang.equals("java") ? ".java" : codeLang.equals("python") ? ".py" : ".cs";
        if (filename.endsWith(extension)) {
            String typename = filename.substring(0, filename.length()  - extension.length());
            if (codeInjector.hasInjectedCode(typename)) {
                return true;
            }
            if (typename.equals(appSettings.getBaseTokenClassName())) {
                // The Token class now contains the TokenType enum
                // so we always regenerate.
                return true;
            }
        }
        //
        // For now regenerate() isn't called for generating Python or C# files,
        // but I'll leave this here for the moment
        //
        return extension.equals(".py") || extension.equals(".cs");    // for now, always regenerate
    }

    void generateTreeBuildingFiles() throws IOException {
        if (generateRootApi) {
    	    generateNodeFile();
        }
        Map<String, Path> files = new LinkedHashMap<>();
        if (appSettings.getBaseNodeClassName().indexOf('.') == -1) {
            files.put(appSettings.getBaseNodeClassName(), getOutputFile(appSettings.getBaseNodeClassName()));
        }
        for (RegularExpression re : grammar.getLexerData().getOrderedNamedTokens()) {
            if (re.isPrivate()) continue;
            String tokenClassName = re.getGeneratedClassName();
            if (tokenClassName.indexOf('.') != -1) continue;
            Path outputFile = getOutputFile(tokenClassName);
            files.put(tokenClassName, outputFile);
            tokenSubclassFileNames.add(outputFile.getFileName().toString());
            String superClassName = re.getGeneratedSuperClassName();
            if (superClassName != null) {
                if (superClassName.indexOf('.') == -1) {
                    outputFile = getOutputFile(superClassName);
                    files.put(superClassName, outputFile);
                    tokenSubclassFileNames.add(outputFile.getFileName().toString());
                }
                superClassLookup.put(tokenClassName, superClassName);
            }
        }
        for (Map.Entry<String, String> es : appSettings.getExtraTokens().entrySet()) {
            String value = es.getValue();
            Path outputFile = getOutputFile(value);
            files.put(value, outputFile);
            tokenSubclassFileNames.add(outputFile.getFileName().toString());
        }
        for (String nodeName : grammar.getNodeNames()) {
            if (nodeName.indexOf('.')>0) continue;
            Path outputFile = getOutputFile(nodeName);
            if (tokenSubclassFileNames.contains(outputFile.getFileName().toString())) {
                String name = outputFile.getFileName().toString();
                name = name.substring(0, name.length() -5);
                errors.addError("The name " + name + " is already used as a Node type.");
            }
            files.put(nodeName, outputFile);
        }
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            if (regenerate(entry.getValue())) {
                generate(entry.getKey(), entry.getValue());
            }
        }
    }

    // only used for tree-building files (a bit kludgy)
    private Path getOutputFile(String nodeName) throws IOException {
        if (nodeName.equals(appSettings.getBaseNodeClassName())) {
            return appSettings.getNodeOutputDirectory().resolve(nodeName + ".java");
        }
        String className = grammar.getNodeClassName(nodeName);
        //KLUDGE
        if (nodeName.equals(appSettings.getBaseNodeClassName())) {
            className = nodeName;
        }
        return appSettings.getNodeOutputDirectory().resolve(className + ".java");
    }
}
