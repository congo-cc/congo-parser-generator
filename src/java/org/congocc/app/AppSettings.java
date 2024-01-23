package org.congocc.app;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.congocc.core.Grammar;
import org.congocc.core.LexerData;
import org.congocc.core.RegexpSpec;
import org.congocc.parser.Node;
import org.congocc.parser.tree.MethodCall;

/**
 * Class to hold the various application settings
 */
public class AppSettings {

    private static final Pattern extraTokenPattern = Pattern.compile("^(\\w+)(#\\w+)?$");
    private final Grammar grammar;
    private final Errors errors;
    private Map<String, Object> settings = new HashMap<>();
    private Path outputDir, filename, includedFileDirectory;
    private String codeLang, parserPackage, parserClassName, lexerClassName, baseName, baseNodeClassName, baseTokenClassName;

    private final Set<String> usedIdentifiers = new HashSet<>();
    private final Set<String> tokensOffByDefault = new HashSet<>();
    private final Map<String, String> extraTokens = new LinkedHashMap<>();
    private boolean ignoreCase, quiet;
    private int jdkTarget = 8;

    public AppSettings(Grammar grammar) {
        this.grammar = grammar;
        this.errors = grammar.getErrors();
    }

    private final String booleanSettings = ",FAULT_TOLERANT,FAULT_TOLERANT_DEFAULT,PRESERVE_TABS,PRESERVE_LINE_ENDINGS,"
                                    + "JAVA_UNICODE_ESCAPE,IGNORE_CASE,LEXER_USES_PARSER,NODE_DEFAULT_VOID,SMART_NODE_CREATION," 
                                    + "NODE_USES_PARSER,TREE_BUILDING_DEFAULT,TREE_BUILDING_ENABLED,TOKENS_ARE_NODES," 
                                    + "SPECIAL_TOKENS_ARE_NODES,UNPARSED_TOKENS_ARE_NODES," 
                                    + "TOKEN_MANAGER_USES_PARSER,ENSURE_FINAL_EOL,MINIMAL_TOKEN,C_CONTINUATION_LINE," 
                                    + "USE_CHECKED_EXCEPTION,LEGACY_GLITCHY_LOOKAHEAD,TOKEN_CHAINING,USES_PREPROCESSOR,X_JTB_PARSE_TREE,X_SYNTHETIC_NODES_ENABLED,";

    private final String stringSettings = ",BASE_NAME,PARSER_PACKAGE,PARSER_CLASS,LEXER_CLASS,BASE_SRC_DIR,BASE_NODE_CLASS,"
                                    + "BASE_TOKEN_CLASS,NODE_PREFIX,NODE_CLASS,NODE_PACKAGE,DEFAULT_LEXICAL_STATE," 
                                    + "NODE_CLASS,OUTPUT_DIRECTORY,DEACTIVATE_TOKENS,EXTRA_TOKENS,ROOT_API_PACKAGE," 
                                    + "COPYRIGHT_BLURB,TERMINATING_STRING,";

    private final String integerSettings = ",TAB_SIZE,TABS_TO_SPACES,JDK_TARGET,";

    private static final Map<String, String> locationAliases = new HashMap<String, String>() {
        {
            put("JAVA_IDENTIFIER_DEF", "/include/java/JavaIdentifierDef.ccc");
            put("JAVA_LEXER", "/include/java/JavaLexer.ccc");
            put("JAVA", "/include/java/Java.ccc");
            put("PYTHON_IDENTIFIER_DEF", "/include/python/PythonIdentifierDef.ccc");
            put("PYTHON_LEXER", "/include/python/PythonLexer.ccc");
            put("PYTHON", "/include/python/Python.ccc");
            put("CSHARP", "/include/csharp/CSharp.ccc");
            put("CSHARP_LEXER", "/include/csharp/CSharpLexer.ccc");
            put("CSHARP_IDENTIFIER_DEF", "/include/csharp/CSharpIdentifierDef.ccc");
            put("PREPROCESSOR", "/include/preprocessor/Preprocessor.ccc");
            put("JSON", "/include/json/JSON.ccc");
            put("JSONC", "/include/json/JSONC.ccc");
            put("LUA", "/include/lua/Lua.ccc");
        }
    };

    public String getCodeLang() {return codeLang;}

    public void setCodeLang(String codeLang) {this.codeLang = codeLang;}

    public Set<String> getDeactivatedTokens() {
        return tokensOffByDefault;
    }
    public Map<String,String> getExtraTokens() {return extraTokens;}
    public List<String> getExtraTokenNames() {return new ArrayList<>(extraTokens.keySet());}
    public Collection<String> getExtraTokenClassNames() {return extraTokens.values();}

    public boolean isASetting(String key) {
        return booleanSettings.contains("," + key + ",")
              || stringSettings.contains("," + key + ",")
              || integerSettings.contains("," + key + ",");
    }
    
    private void typeCheckSettings(Map<String, Object> settings) {
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (booleanSettings.contains("," + key + ",")) {
                if (!(value instanceof Boolean)) {
                    errors.addError("The option " + key + " is supposed to be a boolean (true/false) type");
                }
            }
            else if (stringSettings.contains("," + key + ",")) {
                if (!(value instanceof String)) {
                    errors.addError("The option " + key + " is supposed to be a string");
                }
            }
            else if (integerSettings.contains("," + key + ",")) {
                if (!(value instanceof Integer)) {
                    errors.addError("The option " + key + " is supposed to be an integer");
                }
            }
            else {
                errors.addWarning("The option " + key + " is not recognized and will be ignored.");
            }
        }
    }

    /**
     * Some warnings if incompatible options are set.
     */
    private void sanityCheckSettings() {
        if (!getTreeBuildingEnabled()) {
            String msg = "You have specified the OPTION_NAME option but it is "
                    + "meaningless unless the TREE_BUILDING_ENABLED is set to true."
                    + " This option will be ignored.\n";
            if (settings.get("TOKENS_ARE_NODES") != null) {
                errors.addWarning(null, msg.replace("OPTION_NAME", "TOKENS_ARE_NODES"));
            }
            if (settings.get("UNPARSED_TOKENS_ARE_NODES") != null) {
                errors.addWarning(null, msg.replace("OPTION_NAME", "UNPARSED_TOKENS_ARE_NODES"));
            }
            if (settings.get("SMART_NODE_CREATION") != null) {
                errors.addWarning(null, msg.replace("OPTION_NAME", "SMART_NODE_CREATION"));
            }
            if (settings.get("NODE_DEFAULT_VOID") != null) {
                errors.addWarning(null, msg.replace("OPTION_NAME", "NODE_DEFAULT_VOID"));
            }
            if (settings.get("NODE_USES_PARSER") != null) {
                errors.addWarning(null, msg.replace("OPTION_NAME", "NODE_USES_PARSER"));
            }
            if (settings.get("X_SYNTHETIC_NODES_ENABLED") != null) {
                errors.addWarning(null, msg.replace("OPTION_NAME", "X_SYNTHETIC_NODES_ENABLED"));
                if (settings.get("X_JTB_PARSE_TREE") != null) {
                    errors.addWarning(null, msg.replace("OPTION_NAME", "X_JTB_PARSE_TREE")
                    		         .replace("TREE_BUILDING_ENABLED", "X_SYNTHETIC_NODES_ENABLED"));
                }
            }
        }
    }


    public void setSettings(Map<String, Object> settings) {
        typeCheckSettings(settings);
        if (!grammar.isInInclude()) {
            this.settings = settings;
            sanityCheckSettings();
        }
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            switch (key) {
                case "IGNORE_CASE":
                    setIgnoreCase((Boolean) value);
                    break;
                case "DEFAULT_LEXICAL_STATE":
                    grammar.setDefaultLexicalState((String) value);
                    break;
                case "DEACTIVATE_TOKENS": {
                    String tokens = (String) settings.get(key);
                    for (StringTokenizer st = new StringTokenizer(tokens, ", \t\n\r"); st.hasMoreTokens(); ) {
                        String tokenName = st.nextToken();
                        tokensOffByDefault.add(tokenName);
                    }
                    break;
                }
                case "EXTRA_TOKENS": {
                    String tokens = (String) settings.get(key);
                    for (StringTokenizer st = new StringTokenizer(tokens, ",\r\n"); st.hasMoreTokens(); ) {
                        String tokenNameAndMaybeClass = st.nextToken();
                        Matcher m = extraTokenPattern.matcher(tokenNameAndMaybeClass);
                        if (m.matches()) {
                            MatchResult mr = m.toMatchResult();
                            String tokenName = mr.group(1);
                            String tokenClassName = mr.group(2);
                            if (tokenClassName == null) {
                                tokenClassName = tokenName + "Token";
                            } else {
                                tokenClassName = tokenClassName.substring(1);
                            }
                            extraTokens.put(tokenName, tokenClassName);
                        }
                    }
                    break;
                }
                case "BASE_SRC_DIR":
                case "OUTPUT_DIRECTORY":
                    if (!grammar.isInInclude() && outputDir == null)
                        outputDir = Paths.get((String) value);
                    break;
            }
            if (!grammar.isInInclude() && key.equals("JDK_TARGET") && jdkTarget ==0){
                int jdkTarget = (Integer) value;
                if (jdkTarget >=8 && jdkTarget <= 19) {
                    this.jdkTarget = (Integer) value; 
                }
                else {
                    this.jdkTarget = 8;
                    errors.addWarning(null, "Invalid JDK Target " + jdkTarget);
                }
            }
        }
    }


    public int getJdkTarget() {
        if (jdkTarget == 0) return 8;
        return jdkTarget;
    }

    public void setJdkTarget(int jdkTarget) {this.jdkTarget = jdkTarget;}

    //FIXME.
    public String getBaseSourceDirectory() {
        return outputDir == null ? "." : outputDir.toString();
    }

    public Path getParserOutputDirectory() throws IOException {
        String baseSrcDir = (String) settings.get("BASE_SRC_DIR");
        if (baseSrcDir == null) {
            baseSrcDir = outputDir == null ? "." : outputDir.toString();
        }
        Path dir = Paths.get(baseSrcDir);
        if (!dir.isAbsolute()){
            Path inputFileDir = filename.toAbsolutePath().getParent();
            dir = inputFileDir.resolve(baseSrcDir);
        }
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String packageName = getParserPackage();
        if (packageName != null  && packageName.length() >0) {
            int dotPosition;

            switch (codeLang) {
                case "java":
                    packageName = packageName.replace('.', '/');
                    dir = dir.resolve(packageName);
                    if (!Files.exists(dir)) {
                        Files.createDirectories(dir);
                    }
                    break;
                case "python":  // Use last part of package, append "parser"
                    dotPosition = packageName.lastIndexOf('.');

                    if (dotPosition >= 0) {
                        packageName = packageName.substring(dotPosition + 1);
                    }
                    packageName = packageName.concat("parser");
                    // Use a user-specified value if available
                    packageName = grammar.getPreprocessorSymbols().getOrDefault("py.package", packageName);
                    dir = dir.resolve(packageName);
                    if (!Files.exists(dir)) {
                        Files.createDirectories(dir);
                    }
                    break;
                case "csharp":
                    // Use last part of package, append "parser", prepend "cs-"
                    // only if outDir isn't specified
                    if (outputDir == null) {
                        dotPosition = packageName.lastIndexOf('.');

                        if (dotPosition >= 0) {
                            packageName = packageName.substring(dotPosition + 1);
                        }
                        packageName = "cs-".concat(packageName.concat("parser"));
                        dir = dir.resolve(packageName);
                        if (!Files.exists(dir)) {
                            Files.createDirectories(dir);
                        }
                    }
                    break;
                default:
                    throw new UnsupportedOperationException(String.format("Code generation in '%s' is not currently supported.", codeLang));
            }
        }
        return dir;
    }
    
    public String separatorString() {
        // Temporary solution. Use capital sigma for Python / others, for now
        return codeLang.equals("java") ? "$": "\u03A3";
    }

    public String getParserPackage() {
        if (parserPackage == null) {
            parserPackage = (String) settings.get("PARSER_PACKAGE");
        }
        if (parserPackage == null || parserPackage.length() == 0) {
            String s = getParserClassName();
            parserPackage = (s == null) ? null : s.toLowerCase();
        }
        return parserPackage;
    }

    public String getParserClassName() {
        if (parserClassName ==null) {
            parserClassName = (String) settings.get("PARSER_CLASS");
        }
        if (parserClassName == null) {
            // The base name might not be set if just parsing in memory, with no code generation being done.
            // In that case, we effectively just return null.
            String s = getBaseName();
            parserClassName = (s == null) ? null : (s + "Parser");
        }
        return parserClassName;
    }

    public String getLexerClassName() {
        if (lexerClassName == null) {
            lexerClassName = (String) settings.get("LEXER_CLASS");
        }
        if (lexerClassName == null) {
            // The base name might not be set if just parsing in memory, with no code generation being done.
            // In that case, we effectively just return null.
            String s = getBaseName();
            lexerClassName = (s == null) ? null : (s + "Lexer");
        }
        return lexerClassName;
    }

    String getBaseName() {
        if (baseName == null) {
            baseName = (String) settings.get("BASE_NAME");
        }
        // The filename might not be set if just parsing in memory, with no code generation being done.
        // In that case, we effectively just return null.
        if ((baseName == null) && (filename != null)) {
            baseName = filename.getFileName().toString();
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot >0) {
                baseName = baseName.substring(0, lastDot);
            }
            baseName = removeNonJavaIdentifierPart(baseName);
            if (Character.isLowerCase(baseName.charAt(0))) {
                baseName = baseName.substring(0, 1).toUpperCase() 
                                  + baseName.substring(1);
            }
        }
        return baseName;
    }


    public Path getNodeOutputDirectory() throws IOException {
        String nodePackage = getNodePackage();
        String baseSrcDir = getBaseSourceDirectory();
        if (nodePackage == null || nodePackage.equals("") || baseSrcDir.equals("")) {
            return getParserOutputDirectory();
        }
        Path baseSource = Paths.get(baseSrcDir);
        if (!baseSource.isAbsolute()) {
            Path grammarFileDir = filename.normalize().getParent();
            if (grammarFileDir == null) grammarFileDir = Paths.get(".");
            baseSource = grammarFileDir.resolve(baseSrcDir).normalize();
        }
        if (!Files.isDirectory(baseSource)) {
            if (!Files.exists(baseSource)) {
                throw new FileNotFoundException("Directory " + baseSrcDir + " does not exist.");
            }
            throw new FileNotFoundException(baseSrcDir + " is not a directory.");
        }
        Path result = baseSource. resolve(nodePackage.replace('.', '/')).normalize();
        if (!Files.exists(result)) {
            Files.createDirectories(result);
        } else if (!Files.isDirectory(result)) {
            throw new IOException(result + " is not a directory.");
        }
        return result;
    }

    public String getNodePackage() {
        String nodePackage = (String) settings.get("NODE_PACKAGE");
        String s = getParserPackage();
        if (nodePackage == null || nodePackage.equals(s)) {
            nodePackage = (s == null) ? null : s + ".ast";
        }
        return nodePackage;
    }

    public String getRootAPIPackage() {
        String rootApiPackage = (String) settings.get("ROOT_API_PACKAGE");
        if (rootApiPackage != null && rootApiPackage.equals(getParserPackage())) {
            return null;
        }
        return rootApiPackage;
    }

    public String getCopyrightBlurb() {
        String result = (String) settings.get("COPYRIGHT_BLURB");
        return result != null ? result : "";
    }

    public Path resolveLocation(String location) {
        Path path = Paths.get(location);
        if (Files.exists(path)) return path;
        if (!path.isAbsolute()) {
            path = filename.getParent();
            if (path == null) {
                path = Paths.get(".");
            }
            path = path.resolve(location);
            if (Files.exists(path)) return path;
        }
        if (includedFileDirectory != null) {
            path = includedFileDirectory.resolve(location);
            if (Files.exists(path)) return path;
        }
        if (LexerData.isJavaIdentifier(location)) {
            location = resolveAlias(location);
            /*
             * Look to see if a file with the same name exists in the directory of the current filename, If it does,
             * use it
             */
            path = filename.toAbsolutePath().getParent().resolve(Paths.get(location).getFileName());
            if (Files.exists(path)) return path;
        }
        URI uri;
        try {
            uri = getClass().getResource(location).toURI();
        } catch (Exception e) {
            return null;
        }
        try {
            return Paths.get(uri);
        } catch (FileSystemNotFoundException fsne) {
           try {
               FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
               return fs.getPath(location);
           }
           catch (Exception e) {
               e.printStackTrace();
           }
        }
        return null;
    }

    public String resolveAlias(String location) {
        return locationAliases.getOrDefault(location, location);
    }

    public Path resolveLocation(List<String> locations) {
        for (String location : locations) {
            Path path = resolveLocation(location);
            if (path != null) return path;
        }
        return null;
    }

    public boolean isIgnoreCase() {return ignoreCase;}
    public void setIgnoreCase(boolean ignoreCase) {this.ignoreCase = ignoreCase;}
    public boolean getTreeBuildingEnabled() {
        Boolean b = (Boolean) settings.get("TREE_BUILDING_ENABLED");
        return b == null || b;
    }

    public int getTabSize() {
        Integer i = (Integer) settings.get("TAB_SIZE");
        if (i==null) {
            i = (Integer) settings.get("TABS_TO_SPACES");
        }
        return i==null ? 1 : i;
    }

    public boolean getUseCheckedException() {
        Boolean b = (Boolean) settings.get("USE_CHECKED_EXCEPTION");
        return b != null && b;
    }

    public boolean getCppContinuationLine() {
        Boolean b = (Boolean) settings.get("C_CONTINUATION_LINE");
        return b != null && b;
    }

    public boolean getJavaUnicodeEscape() {
        Boolean b = (Boolean) settings.get("JAVA_UNICODE_ESCAPE");
        return b != null && b;
    }

    public boolean getPreserveTabs() {
        Boolean b = (Boolean) settings.get("PRESERVE_TABS");
        if (b!=null) return b;
        if (settings.get("TAB_SIZE")==null && settings.get("TABS_TO_SPACES")==null)
            return true;
        return getTabSize() ==0;
    }

    public boolean getPreserveLineEndings() {
        Boolean b = (Boolean) settings.get("PRESERVE_LINE_ENDINGS");
        return b != null && b;
    }

    public boolean getEnsureFinalEOL() {
        Boolean b = (Boolean) settings.get("ENSURE_FINAL_EOL");
        return b != null && b;
    }

    public boolean getTokenChaining() {
        Boolean b = (Boolean) settings.get("TOKEN_CHAINING");
        if (b != null) return b;
        return checkForMethodName("preInsert");
    }

    public boolean getMinimalToken() {
        if (getTokenChaining()) return false;
        Boolean b = (Boolean) settings.get("MINIMAL_TOKEN");
        if (b != null) return b;
        return !checkForMethodName("setImage") && !checkForMethodName("setCachedImage");
    }

    public boolean getNodeUsesParser() {
        Boolean b = (Boolean) settings.get("NODE_USES_PARSER");
        return b != null && b;
    }

    public boolean getJtbParseTree() {
        Boolean b = (Boolean) settings.get("X_JTB_PARSE_TREE");
        return b != null && b;
    }

    public boolean getSyntheticNodesEnabled() {
        Boolean b = (Boolean) settings.get("X_SYNTHETIC_NODES_ENABLED");
        return b != null && b;
    }

    public boolean getLexerUsesParser() {
        Boolean b = (Boolean) settings.get("LEXER_USES_PARSER");
        return b != null && b;
    }

    public boolean getFaultTolerant() {
        Boolean b = (Boolean) settings.get("FAULT_TOLERANT");
        return b != null && b;
    }  
    
    public boolean getTokensAreNodes() {
        Boolean b = (Boolean) settings.get("TOKENS_ARE_NODES");
        return b == null || b;
    }
    
    public boolean getUnparsedTokensAreNodes() {
        Boolean b = (Boolean) settings.get("TOKENS_ARE_NODES");
        if (b == null) b = (Boolean) settings.get("SPECIAL_TOKENS_ARE_NODES");
        return b != null;
    }

    public boolean getSmartNodeCreation() {
        Boolean b = (Boolean) settings.get("SMART_NODE_CREATION");
        return b == null || b;
    }

    public boolean getTreeBuildingDefault() {
        Boolean b = (Boolean) settings.get("TREE_BUILDING_DEFAULT");
        return b == null || b;
    }

    public boolean getFaultTolerantDefault() {
        Boolean b = (Boolean) settings.get("FAULT_TOLERANT_DEFAULT");
        return b == null || b;
    }

    public boolean getNodeDefaultVoid() {
        Boolean b = (Boolean) settings.get("NODE_DEFAULT_VOID");
        return b != null && b;
    }

    public boolean getLegacyGlitchyLookahead() {
        Boolean b = (Boolean) settings.get("LEGACY_GLITCHY_LOOKAHEAD");
        return b!=null && b;
    }

    public boolean getUsesPreprocessor() {
        if (getCppContinuationLine()) return true;
        Boolean b = (Boolean) settings.get("USES_PREPROCESSOR");
        return b != null && b;
    }
    
    public String getNodePrefix() {
        String nodePrefix = (String) settings.get("NODE_PREFIX");
        if (nodePrefix == null) nodePrefix = "";
        return nodePrefix;
    }

    public String getTerminatingString() {
        String terminatingString = (String) settings.get("TERMINATING_STRING");
        if (terminatingString == null) {
            if (getEnsureFinalEOL()) {
                terminatingString = "\n";
            }
        }
        return terminatingString == null ? "" : terminatingString;
    }

    public boolean getHasLazyTokens() {
        return grammar.firstDescendantOfType(RegexpSpec.class, RegexpSpec::isLazy) != null;
    }


    public String getBaseNodeClassName() {
        if (baseNodeClassName == null) {
            baseNodeClassName = (String) settings.get("BASE_NODE_CLASS");
        }
        if (baseNodeClassName == null) {
            if (getRootAPIPackage() == null || getBaseName().length() == 0) {
                baseNodeClassName = "BaseNode";
            } else {
                baseNodeClassName = getBaseName() + "Node";
            }
        }
        return baseNodeClassName;
    }

    public String getBaseTokenClassName() {
        if (baseTokenClassName == null) {
            baseTokenClassName = (String) settings.get("BASE_TOKEN_CLASS");
            if (baseTokenClassName == null) {
                if (getRootAPIPackage() == null || getBaseName().length() == 0) {
                    baseTokenClassName = "Token";
                }
                else {
                    baseTokenClassName = getBaseName() + "Token";
//                    baseTokenClassName = "Token";
                }
            }
        }
        return baseTokenClassName;
    }

    public void setOutputDir(Path outputDir) {this.outputDir = outputDir;}

    public Path getOutputDir() {return outputDir;}

    public Path getIncludedFileDirectory() {
        return includedFileDirectory;
    }

    public void setIncludedFileDirectory(Path includedFileDirectory) {
        this.includedFileDirectory = includedFileDirectory;
    }

    public Path getFilename() {
        return filename;
    }

    public void setFilename(Path filename) {
        this.filename = filename;
    }

    public boolean isQuiet() {
        return quiet;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public boolean getDebugFaultTolerant() {
        return false;
    }

    public String generateUniqueIdentifier(String prefix, Node exp) {
        String inputSource = exp.getInputSource();
        String sep = separatorString();

        if (inputSource != null) {
            int lastSlash = Math.max(inputSource.lastIndexOf('\\'), inputSource.lastIndexOf('/'));
            if (lastSlash+1<inputSource.length()) inputSource = inputSource.substring(lastSlash+1);
        } else {
            inputSource = "";
        }
        String id = prefix + inputSource + sep + exp.getBeginLine() + sep + exp.getBeginColumn();
        id = removeNonJavaIdentifierPart(id);
        while (usedIdentifiers.contains(id)) {
            id += sep;
        }
        usedIdentifiers.add(id);
        return id;
    }

    public String generateIdentifierPrefix(String basePrefix) {
        return basePrefix + separatorString();
    }

    static public String removeNonJavaIdentifierPart(String s) {
        StringBuilder buf = new StringBuilder(s.length());
        for (int ch : s.codePoints().toArray()) {
            boolean addChar = buf.length() == 0 ? (Character.isJavaIdentifierStart(ch)) : Character.isJavaIdentifierPart(ch);
            if (addChar) {
                buf.appendCodePoint(ch);
            }
            if (ch == '.') buf.appendCodePoint('_');
        }
        return buf.toString();
    }

    private boolean checkForMethodName(String methodName) {
        return grammar.firstDescendantOfType(MethodCall.class, 
            mc->mc.get(0).getLastChild().toString().equals(methodName)) != null;
    }
}