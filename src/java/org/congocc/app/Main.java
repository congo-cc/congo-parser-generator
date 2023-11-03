package org.congocc.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;

import org.congocc.core.Grammar;


/**
 * Entry point.
 */
public final class Main {

    public static final String PROG_NAME = "CongoCC Parser Generator";
    public static final String URL = "Go to https://discuss.congocc.org for more information.";
    private static String manifestContent = "", jarFileName = "congocc.jar";
    private static Path jarPath;
    private static final FileSystem fileSystem = FileSystems.getDefault();
    private static final Pattern symbolPattern = Pattern.compile("^(\\w+(\\.\\w+)*)(=(\\w+(\\.\\w+)*))?$");

    static {
        try {
            Enumeration<URL> urls = Main.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                InputStream is = url.openStream();
                int available = is.available();
                byte[] bytes = new byte[available];
                is.read(bytes);
                is.close();
                String content = new String(bytes);
                if (content.contains("congocc.app.Main")) {
                    String path = url.getFile();
                    if (path.startsWith("file:")) {
                        path = path.substring(5);
                    }
                    int exclamIndex = path.lastIndexOf('!');
                    if (exclamIndex > 0) {
                        path = path.substring(0, exclamIndex);
                    }
                    jarPath = fileSystem.getPath(path);
                    jarFileName = jarPath.getFileName().toString();
                    manifestContent = content;
                    break;
                }
            }
        } catch (Exception e) {
            // Oh well, never mind!
        }
    }

    static void checkForNewer() {
        if (jarPath != null && Files.exists(jarPath) 
             && (jarPath.endsWith("congocc.jar") || jarPath.endsWith("congocc-full.jar")))
           try {
                long jarLastModified = Files.getLastModifiedTime(jarPath).toMillis();
                if (System.currentTimeMillis() - jarLastModified < 3600000L) {
                    // If the current jarfile is less than an hour old, let's not bother.
                    return;
                }
                //URL url = new URL("https://parsers.org/download/congocc.jar");
                URL url = new URI("https://parsers.org/download/congocc.jar").toURL();
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(1000);
                long lastUpdate = connection.getLastModified();
                if (lastUpdate > jarLastModified) {
                    System.out.println("Found newer version of congocc at " + url);
                    System.out.println("Download it? (y/N)");
                    Scanner scanner = new Scanner(System.in);
                    String response = scanner.nextLine().trim().toLowerCase();
                    if (response.equals("y") || response.equals("yes")) {
                        boolean renamedFileSuccessfully = false;
                        String oldFilename = jarPath.getFileName().toString().replace("congocc",
                                "congocc-" + System.currentTimeMillis());
                        Path oldPath = jarPath.resolveSibling(oldFilename);
                        try {
                            Files.move(jarPath, oldPath);
                            renamedFileSuccessfully = true;
                        } catch (Exception e) {
                            System.out.println("Failed to save older version of jarfile");
                            System.out.println("Possibly directory " + oldPath.getParent() + " is not writeable.");
                            scanner.close();
                            return;
                        }
                        System.out.println("Updating jarfile...");
                        InputStream inputStream = url.openStream();
                        OutputStream fileOS = Files.newOutputStream(jarPath);
                        byte[] data = new byte[1024];
                        int byteContent;
                        while ((byteContent = inputStream.read(data, 0, 1024)) != -1) {
                            fileOS.write(data, 0, byteContent);
                        }
                        fileOS.close();
                        scanner.close();
                        System.out.println("Fetched newer jarfile from server.");
                        if (renamedFileSuccessfully)
                            System.out.println("Older jarfile is at: " + oldPath);
                        System.out.println("Exiting...");
                        System.exit(-1);
                    }
                }
            } catch (Exception e) {
                // Never mind.
            }
    }

    private static final String [] otherSupportedLanguages = new String[] {
        "python",
        "csharp"
    };

    static void usage() {
        List<String> validChoices = new ArrayList<>(Arrays.asList(otherSupportedLanguages));
        validChoices.add(0, "java");
        StringBuilder sb = new StringBuilder();
        int n = validChoices.size();

        for (int i = 0; i < n; i++) {
            sb.append(String.format("'%s'", validChoices.get(i)));
            if (i < (n - 2)) {
                sb.append(", ");
            }
            else if (i == (n - 2)) {
                sb.append(" and ");
            }
        }

        System.out.println("Usage:");
        System.out.println("    java -jar " + jarFileName + " grammarfile");
        System.out.println();
        System.out.println("The following command-line flags are available:");
        System.out.println(" -d <directory>    Specify the directory (absolute or relative to the grammarfile location) to place generated files");
        System.out.println("   For example:   -d ../../src/generated");
        System.out.println("   If this is unset, files are generated relative to the grammar file location.");
        System.out.println(" -lang <language>  Specify the language to generate code in (the default is 'java')");
        System.out.println("                     (valid choices are currently " + sb + ")");
        System.out.println(" -jdkN             Specify the target JDK version. N is a number from 8 to 19. (Default is 8)");
        System.out.println("                     (this is only useful when the code generation is in Java)");
        System.out.println(" -n                Suppress the check for a newer version");
        System.out.println(" -p                Define one or more comma-separated (no spaces) symbols to pass to the preprocessor.");
        System.out.println("   For example:   -p debug,strict");
        System.out.println(" -q                Quieter output");
        System.out.println();
    }

    /**
     * The main program.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            bannerLine();
            usage();
            checkForNewer();
            System.exit(1);
        }
        Path grammarFile = null, outputDirectory = null;
        String codeLang = "java";
        int jdkTarget = 0;
        Map<String, String> preprocessorSymbols = new HashMap<>();
        boolean quiet = false, noNewerCheck = false;
        for (int i=0; i<args.length;i++) {
            String arg = args[i];
            if (arg.charAt(0) == '-') {
                if (arg.startsWith("--")) arg = arg.substring(1);
                if (arg.equalsIgnoreCase("-p")) {
                    if (i==args.length-1) {
                        System.err.println("-p flag with no preprocessor symbols afterwards");
                        System.exit(-1);
                    }
                    String symbols = args[++i];
                    StringTokenizer st = new StringTokenizer(symbols, ",");
                    while (st.hasMoreTokens()) {
                        String s = st.nextToken().trim();
                        Matcher m = symbolPattern.matcher(s);
                        if (!m.find()) {
                            System.err.printf("-p flag with invalid argument '%s'%n", s);
                            System.exit(-1);
                        }
                        String name = m.group(1);
                        String value = m.group(4);
                        if (value == null) {
                            value = "1";
                        }
                        preprocessorSymbols.put(name, value);
                    }
                }
                else if (arg.equalsIgnoreCase("-d")) {
                    if (i==args.length-1) {
                        System.err.println("-d flag with no output directory");
                        System.exit(-1);
                    }
                    outputDirectory = Paths.get(args[++i]);
                }
                else if (arg.equalsIgnoreCase("-n")) {
                    noNewerCheck = true;
                }
                else if (arg.equalsIgnoreCase("-q") || arg.equalsIgnoreCase("-quiet")) {
                    quiet = true;
                }
                else if (arg.equalsIgnoreCase("-lang")) {
                    String candidate = args[++i];

                    if (!candidate.equals("java")) {
                        if (!Arrays.asList(otherSupportedLanguages).contains(candidate.toLowerCase())) {
                            System.err.printf("Not a supported code generation language: '%s'%n", candidate);
                            System.exit(-1);
                        }
                        codeLang = candidate.toLowerCase();
                        if (jdkTarget != 0) {
                            System.err.println("The -jdk flag is only compatible with a Java target.");
                            System.exit(-1);
                        }
                    }
                }
                else if (arg.toLowerCase().startsWith("-jdk")) {
                    if (!codeLang.equals("java")) {
                        System.err.println("The -jdk flag is only compatible with a Java target.");
                        System.exit(-1);
                    }
                    String number = arg.substring(4);
                    try {
                       jdkTarget = Integer.parseInt(number);
                    } catch (NumberFormatException nfe) {
                        System.err.println("Expecting a number after 'jdk', like -jdk11");
                    }
                    if (jdkTarget <8 || jdkTarget > 19) {
                        System.err.println("The JDK Target currently must be between 8 and 19.");
                    }
                }
                else {
                    System.err.println("Ignoring unknown flag: " + arg);
                }
            } else {
                if (grammarFile == null) {
                    grammarFile = Paths.get(arg);
                    if (!Files.exists(grammarFile)) {
                        System.err.println("File " + grammarFile + " does not exist!");
                        System.exit(-1);
                    }
                }
                else {
                    System.err.println("Extraneous argument " + arg);
                    System.exit(-1);
                }
            }
        }
        if (!noNewerCheck) {
            checkForNewer();
        }
        if (grammarFile == null) {
            System.err.println("No input file specified");
            System.exit(-1);
        }
        if (!Files.exists(grammarFile)) {
            System.err.println("File " + grammarFile + " does not exist!");
            System.exit(-1);
        }
        if (outputDirectory !=null) {
            if (!Files.exists(outputDirectory)) {
                try {Files.createDirectories(outputDirectory);}
                catch (IOException ioe) {
                    System.err.println("Cannot create directory " + outputDirectory);
                    System.exit(-1);
                }
                if (!Files.isWritable(outputDirectory)) {
                    System.err.println("Cannot write to directory " + outputDirectory);
                    System.exit(-1);
                }
            }
        }
        int errorcode = mainProgram(grammarFile, outputDirectory, codeLang, jdkTarget, quiet, preprocessorSymbols);
        System.exit(errorcode);
    }

    /**
     * @param grammarFile The input file
     * @param outputDir The output directory, if this is null, just use the directory where the input file is.
     * @param quiet Whether to be silent (or quiet). Currently does nothing!
     * @return error code
     */
    public static int mainProgram(Path grammarFile, Path outputDir, String codeLang, int jdkTarget, boolean quiet, Map<String, String> symbols)
      throws IOException {
        if (!quiet) bannerLine();
        Grammar grammar = new Grammar(outputDir, codeLang, jdkTarget, quiet, symbols);
        grammar.parse(grammarFile, true);
        grammar.createOutputDir();
        Errors errors = grammar.getErrors();
        grammar.doSanityChecks();
        if (errors.getErrorCount() > 0) {
            outputErrors(grammar, quiet);
            return 1;
        }
        grammar.generateLexer();
        if (errors.getErrorCount() > 0) {
            outputErrors(grammar, quiet);
            return 1;
        }
        grammar.generateFiles();
        if (errors.getWarningCount() == 0 && !quiet) {
            System.out.println("Parser generated successfully.");
        } else if (errors.getWarningCount()>0) {
            System.out.println("Parser generated with 0 errors and "
                                + errors.getWarningCount() + " warnings.");
        }
        outputErrors(grammar, quiet);
        return (errors.getErrorCount() == 0) ? 0 : 1;
    }

    static void outputErrors(Grammar grammar, boolean quiet) {
        Errors errors = grammar.getErrors();
        for (String error : errors.getErrorMessages()) {
            System.err.println(error);
        }
        for (String warning : errors.getWarningMessages()) {
            System.err.println(warning);
        }
        if (!quiet && !errors.getErrorMessages().isEmpty()) for (String info: errors.getInfoMessages()) {
            System.err.println(info);
        }
    }

    /**
     * This prints the banner line when the various tools are invoked. This
     * takes as argument the tool's full name and its version.
     */
    static public void bannerLine() {
    	System.out.println();
        System.out.println(Main.PROG_NAME + getBuiltOnString());
        System.out.println(Main.URL);
        System.out.println("(type \"java -jar congocc.jar\" with no arguments for help)\n");
        System.out.println();
    }


    static private String getBuiltOnString() {
    	if (manifestContent.equals("")) {
    		return "";
    	}
    	String buildDate = "unknown date";
    	String builtBy = "somebody";
    	StringTokenizer st = new StringTokenizer(manifestContent, ": \t\n\r", false);
    	while (st.hasMoreTokens()) {
    		String s = st.nextToken();
    		if (s.equals("Build-Date")) {
    			buildDate = st.nextToken();
    		}
    		if (s.equals("Built-By")) {
    			builtBy = st.nextToken();
    		}
    	}
    	return " (" + jarFileName + " built by " + builtBy + " on " + buildDate + ")";
    }
}
