package org.congocc.codegen;

import java.io.IOException;
import java.io.Writer;
import java.io.StringWriter;
import java.lang.System;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import org.congocc.app.AppSettings;
import org.congocc.app.Errors;
import org.congocc.codegen.python.PyFormatter;
import org.congocc.core.Grammar;
import org.congocc.core.RegularExpression;
import org.congocc.codegen.java.*;
import org.congocc.codegen.python.Reaper;
import org.congocc.codegen.csharp.CSharpFormatter;
import org.congocc.parser.*;
import org.congocc.parser.python.ast.Module;
import org.congocc.parser.tree.CompilationUnit;
import org.congocc.parser.tree.ObjectType;
import org.congocc.parser.Node.CodeLang;
import static org.congocc.parser.Node.CodeLang.*;

import org.congocc.templates.*;

public class FilesGenerator {
    private static final Logger logger = Logger.getLogger("filegen");

    private final Configuration templatesConfig = new org.congocc.templates.Configuration();
    private final Grammar grammar;
    private final AppSettings appSettings;
    private final Errors errors;
    private final CodeInjector codeInjector;
    private final Set<String> tokenSubclassFileNames = new LinkedHashSet<>();
    private final Map<String, String> superClassLookup = new HashMap<>();
    private final CodeLang codeLang;
    private final boolean generateRootApi;
    private final Path parserOutputDirectory;
    private final Path nodeOutputDirectory;

    void initializeTemplateEngine() throws IOException {
        Path filename = appSettings.getFilename().toAbsolutePath();
        Path dir = filename.getParent();
        //
        // The first two locations are really for developers - templates
        // are looked for in the grammar's directory, or if there is a
        // 'templates' subdirectory below that, which could, of course, be
        // a symlink to somewhere else.
        // We check for the 'templates' subdirectory existing, because otherwise
        // the template library will raise an exception.
        //

        String templateFolder = "/templates/".concat(codeLang.toString().toLowerCase());
        Path altDir = dir.resolve(templateFolder.substring(1));
        if (Files.exists(altDir)) {
            templatesConfig.setDirectoryForTemplateLoading(altDir.toString());
        } else {
            templatesConfig.setDirectoryForTemplateLoading(dir.toString());
        }
        templatesConfig.setClassForTemplateLoading(this.getClass(),templateFolder);
        templatesConfig.setNumberFormat("computer");
        templatesConfig.setArithmeticEngine(org.congocc.templates.core.ArithmeticEngine.CONSERVATIVE_ENGINE);
        templatesConfig.setSharedVariable("grammar", grammar);
        templatesConfig.setSharedVariable("globals", grammar.getTemplateGlobals());
        templatesConfig.setSharedVariable("settings", grammar.getAppSettings());
        templatesConfig.setSharedVariable("lexerData", grammar.getLexerData());
        templatesConfig.setSharedVariable("generated_by", org.congocc.app.Main.PROG_NAME);
        if (codeLang == JAVA)
           templatesConfig.addAutoImport("CU", "CommonUtils.java.ctl");
    }

    public FilesGenerator(Grammar grammar) throws IOException {
        this.grammar = grammar;
        this.appSettings = grammar.getAppSettings();
        this.codeLang = appSettings.getCodeLang();
        this.errors = grammar.getErrors();
        this.generateRootApi = appSettings.getRootAPIPackage() == null;
        this.codeInjector = grammar.getInjector();
        this.parserOutputDirectory = appSettings.getParserOutputDirectory();
        this.nodeOutputDirectory = appSettings.getNodeOutputDirectory();
    }

    public void generateAll() throws IOException {
        if (errors.getErrorCount() != 0) {
            throw new ParseException();
        }
        initializeTemplateEngine();
        switch (codeLang) {
            case JAVA -> {
                generateToken();
                generateLexer();
                generateOtherFiles();
                if (!grammar.getProductionTable().isEmpty()) {
                    if (generateRootApi) {
                       generateParseException();
                    }
                    generateParser();
                }
                boolean wanted = appSettings.getFaultTolerant() && generateRootApi;
                generateInvalidNode(wanted);
                generateParsingProblem(wanted);
                generateTreeBuildingFiles(appSettings.getTreeBuildingEnabled());
            }
            case PYTHON -> {
                // Hardcoded for now, could make configurable later
                String[] paths = new String[]{
                        "__init__.py",
                        "utils.py",
                        "tokens.py",
                        "lexer.py",
                        "parser.py",
                        "test/parse_files.py"
                };
                for (String p : paths) {
                    // Could check if regeneration is needed, but for now
                    // always (re)generate
                    generate(parserOutputDirectory, p);
                }
            }
            case CSHARP -> {
                // Hardcoded for now, could make configurable later
                String[] paths = new String[]{
                        "Utils.cs",
                        "Tokens.cs",
                        "Lexer.cs",
                        "Parser.cs",
                        "test/ParseFiles.cs",
                        "test/ParseFiles.csproj",
                        null  // filled in below
                };
                String csPackageName = grammar.getTemplateGlobals().getPreprocessorSymbol("cs.package", appSettings.getParserPackage());
                paths[paths.length - 1] = csPackageName + ".csproj";
                for (String p : paths) {
                    // Could check if regeneration is needed, but for now
                    // always (re)generate
                    generate(parserOutputDirectory, p);
                }
            }
        }
    }

    public void generate(Path outDir, String outputFile) throws IOException {
        generate(null, outDir, outputFile);
    }

    private final Set<String> nonNodeNames = new LinkedHashSet<>() {
        {
            add("ParseException.java");
            add("ParsingProblem.java");
            add("Token.java");
            add("InvalidToken.java");
            add("Node.java");
            add("InvalidNode.java");
            add("TokenSource.java");
            add("NonTerminalCall.java");
        }
    };

    private String getTemplateName(String outputFilename) {
        String result = outputFilename + ".ctl";
        if (codeLang == JAVA) {
            if (outputFilename.equals(appSettings.getBaseTokenClassName() + ".java")) {
                result = "Token.java.ctl";
            } else if (tokenSubclassFileNames.contains(outputFilename)) {
                result = "ASTToken.java.ctl";
            } else if (outputFilename.equals(appSettings.getParserClassName() + ".java")) {
                result = "Parser.java.ctl";
            } else if (outputFilename.endsWith("Lexer.java")
                    || outputFilename.equals(appSettings.getLexerClassName() + ".java")) {
                result = "Lexer.java.ctl";
            } else if (outputFilename.equals(appSettings.getBaseNodeClassName() + ".java")) {
                result = "BaseNode.java.ctl";
            }
            else if (outputFilename.contains("ParseFiles")) {
                // use value set in initializer above
            }
            else if (outputFilename.startsWith(appSettings.getNodePrefix())) {
                if (!nonNodeNames.contains(outputFilename) && !outputFilename.equals(appSettings.getBaseTokenClassName()+".java")) {
                    result = "ASTNode.java.ctl";
                }
            }
        }
        else if (codeLang == CSHARP) {
            if (outputFilename.endsWith(".csproj") && !outputFilename.contains("ParseFiles")) {
                result = "project.csproj.ctl";
            }
        }
        return result;
    }

    public void generate(String nodeName, Path outDir, String outputFile) throws IOException {
        Path outputPath = outDir.resolve(outputFile);
        logger.fine(String.format("Generating: %s", outputFile));
        String currentFilename = outputPath.getFileName().toString();
        String templateName = getTemplateName(outputFile);
        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("filename", currentFilename);
        dataModel.put("isAbstract", grammar.nodeIsAbstract(nodeName));
        dataModel.put("isInterface", grammar.nodeIsInterface(nodeName));
        dataModel.put("isFinal", codeInjector.isFinal(nodeName));
        dataModel.put("isSealed", codeInjector.isSealed(nodeName));
        dataModel.put("isNonSealed", codeInjector.isNonSealed(nodeName));
        dataModel.put("CI", "true".equals(System.getenv("CI")));
        String key = null;
        Set<ObjectType> permitsList = null;

        if (nodeName != null) {
            key = appSettings.getNodePackage() + "." + nodeName;
            permitsList = codeInjector.getPermitsList(key);
        }
        if (permitsList == null) {
            dataModel.put("permitsList", new ArrayList<>());
        } else {
           dataModel.put("permitsList", permitsList);
        }
        if (codeLang == JAVA) {
            String classname = currentFilename.substring(0, currentFilename.length() - 5);  // length of ".java"
            String superClassName = superClassLookup.get(classname);
            if (superClassName == null) superClassName = appSettings.getBaseTokenClassName();
            dataModel.put("superclass", superClassName);
        }
        Writer out = new StringWriter();
        Template template = templatesConfig.getTemplate(templateName);
        // Sometimes needed in templates for e.g. injector.hasInjectedCode(node)
        dataModel.put("injector", grammar.getInjector());
        template.process(dataModel, out);
        String code = out.toString();
        if (!appSettings.isQuiet()) {
            System.out.println("Outputting: " + outputPath.normalize());
        }
        Files.createDirectories(outputPath.getParent());
        if (currentFilename.endsWith(".java")) {
            outputJavaFile(code, outputPath);
        }
        else if (currentFilename.endsWith(".cs")) {
            outputCSharpFile(code, outputPath);
        }
        else  {
            outputPythonFile(code, outputPath);
        }
    }

    private static int countChars(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }

    void outputPythonFile(String code, Path outputFile) throws IOException {
        Module module;
        Writer out = Files.newBufferedWriter(outputFile);
        int initialLines = countChars(code, '\n');

        try {
            if (!outputFile.toString().endsWith("parser.py")) {
                out.write(code);
                return;
            }
            module = CongoCCParser.parsePythonFile(outputFile.getFileName().toString(), code);
        }
        catch (Exception e) {
            errors.addError(e.getMessage());
            out.write(code);
            e.printStackTrace();
            return;
        }
        finally {
            out.flush();
            out.close();
        }
        try (Writer output = Files.newBufferedWriter(outputFile)) {
            Reaper reaper = new Reaper(module);
            reaper.reap();
            String s = new PyFormatter().format(module, false);
            output.write(s);
        }
    }

    void outputCSharpFile(String code, Path outputFile) throws IOException {
        org.congocc.parser.csharp.ast.CompilationUnit cscu;
        Writer out = Files.newBufferedWriter(outputFile);
        int initialLines = countChars(code, '\n');

        try {
           cscu = CongoCCParser.parseCSharpFile(outputFile.getFileName().toString(), code);
        } catch (Exception e) {
            e.printStackTrace();
            errors.addError(e.getMessage());
            out.write(code);
            return;
        } finally {
            out.flush();
            out.close();
        }
        try (Writer output = Files.newBufferedWriter(outputFile)) {
            if (outputFile.toString().endsWith("Parser.cs")) {
                org.congocc.codegen.csharp.Reaper reaper = new org.congocc.codegen.csharp.Reaper(cscu);
                reaper.reap();
            }
            CSharpFormatter formatter = new CSharpFormatter();
            formatter.visit(cscu);
            String s = formatter.getText();
            int finalLines = countChars(s, '\n');
            if (initialLines != finalLines) {
                logger.fine(String.format("Line count went from %d to %d", initialLines, finalLines));
            }
            output.write(s);
        }
    }

    void outputJavaFile(String code, Path outputFile) throws IOException {
        Path dir = outputFile.getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        CompilationUnit jcu;
        Writer out = Files.newBufferedWriter(outputFile);
        try {
            jcu = CongoCCParser.parseJavaFile(grammar, outputFile.getFileName().toString(), code);
        } catch (Exception e) {
            e.printStackTrace();
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
            generate(parserOutputDirectory, "TokenSource.java");
            generate(parserOutputDirectory, "NonTerminalCall.java");
            generate(parserOutputDirectory, "test/ParseFiles.java");
        }
    }

    void generateParseException() throws IOException {
        Path outputFile = parserOutputDirectory.resolve("ParseException.java");
        if (regenerate(outputFile)) {
            generate(parserOutputDirectory, "ParseException.java");
        }
    }

    private void generateOrDelete(String nodeName, Path outDir, String outputFile, boolean wanted) throws IOException {
        Path outputPath = outDir.resolve(outputFile);
        if (wanted) {
            if (regenerate(outputPath)) {
                generate(nodeName, outDir, outputFile);
            }
        }
        else {
            if (Files.exists(outputPath)) {
                Files.delete(outputPath);
            }
        }
    }

    void generateParsingProblem(boolean wanted) throws IOException {
        generateOrDelete(null, parserOutputDirectory,"ParsingProblem.java", wanted);
    }

    void generateInvalidNode(boolean wanted) throws IOException {
        generateOrDelete(null, nodeOutputDirectory,"InvalidNode.java", wanted);
    }

    void generateToken() throws IOException {
        String filename = appSettings.getBaseTokenClassName() + ".java";
        Path outputFile = parserOutputDirectory.resolve(filename);
        if (regenerate(outputFile)) {
            generate(parserOutputDirectory, filename);
        }
        outputFile = parserOutputDirectory.resolve("InvalidToken.java");
        if (regenerate(outputFile)) {
            generate(parserOutputDirectory, "InvalidToken.java");
        }
    }

    void generateLexer() throws IOException {
        String filename = appSettings.getLexerClassName() + ".java";
        generate(parserOutputDirectory, filename);
    }

    void generateParser() throws IOException {
        if (errors.getErrorCount() !=0) {
        	throw new ParseException();
        }
        String filename = appSettings.getParserClassName() + ".java";
        generate(parserOutputDirectory, filename);
    }

    void generateNodeFile(boolean wanted) throws IOException {
        generateOrDelete(null, parserOutputDirectory, "Node.java", wanted);
    }

    private boolean regenerate(Path file) throws IOException {
        return true;
/*
        boolean result = false;

        if (!Files.exists(file)) {
        	result = true;
        }
        else {
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
                    result = true;
                }
                if (typename.equals(appSettings.getBaseTokenClassName())) {
                    // The Token class now contains the TokenType enum,
                    // so we always regenerate.
                    result = true;
                }
            }
            //
            // For now regenerate() isn't called for generating Python or C# files,
            // but I'll leave this here for the moment
            //
            result = extension.equals(".py") || extension.equals(".cs");    // for now, always regenerate
        }
        logger.fine(String.format("regenerate %s -> %s", file, result));
        return result;
 */
    }

    void generateTreeBuildingFiles(boolean wanted) throws IOException {
        if (generateRootApi) {
    	    generateNodeFile(wanted);
        }
        Map<String, String> files = new LinkedHashMap<>();
        if (appSettings.getBaseNodeClassName().indexOf('.') == -1) {
            files.put(appSettings.getBaseNodeClassName(), getOutputFile(appSettings.getBaseNodeClassName()));
        }
        for (RegularExpression re : grammar.getLexerData().getOrderedNamedTokens()) {
            if (re.isPrivate()) continue;
            String tokenClassName = re.getGeneratedClassName();
            if (tokenClassName.indexOf('.') != -1) continue;
            String outputFile = getOutputFile(tokenClassName);
            files.put(tokenClassName, outputFile);
            tokenSubclassFileNames.add(outputFile);
            String superClassName = re.getGeneratedSuperClassName();
            if (superClassName != null) {
                if (superClassName.indexOf('.') == -1) {
                    outputFile = getOutputFile(superClassName);
                    files.put(superClassName, outputFile);
                    tokenSubclassFileNames.add(outputFile);
                }
                superClassLookup.put(tokenClassName, superClassName);
            }
        }
        for (Map.Entry<String, String> es : appSettings.getExtraTokens().entrySet()) {
            String value = es.getValue();
            String outputFile = getOutputFile(value);
            files.put(value, outputFile);
            tokenSubclassFileNames.add(outputFile);
        }
        for (String nodeName : grammar.getNodeNames()) {
            if (nodeName.indexOf('.')>0) continue;
            String outputFile = getOutputFile(nodeName);
            if (tokenSubclassFileNames.contains(outputFile)) {
                String name = outputFile.substring(0, outputFile.length() - 5);  // for ".java"
                errors.addError("The name " + name + " is already used as a Node type.");
            }
            files.put(nodeName, outputFile);
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String s = entry.getValue();
            Path outPath = nodeOutputDirectory.resolve(s);
            if (regenerate(outPath)) {
                generateOrDelete(entry.getKey(), nodeOutputDirectory, s, wanted);
            }
        }
    }

    // only used for tree-building files (a bit kludgy)
    private String getOutputFile(String nodeName) throws IOException {
        if (nodeName.equals(appSettings.getBaseNodeClassName())) {
            return nodeName + ".java";
        }
        String className = grammar.getNodeClassName(nodeName);
        //KLUDGE
        if (nodeName.equals(appSettings.getBaseNodeClassName())) {
            className = nodeName;
        }
        return className + ".java";
    }
}
