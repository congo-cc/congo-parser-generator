import java.io.*;
import java.util.*;

import org.parsers.peg.*;
import org.parsers.peg.ast.*;

/**
 * A test harness for parsing PEG grammar source code
  */
public class Pongo {

   static public ArrayList<Node> roots= new ArrayList<>();

   static public void main(String args[]) {
      List<File> failures = new ArrayList<File>();
      List<File> successes = new ArrayList<File>();
      if (args.length == 0) {
        usage();
      }
      List<File> files = new ArrayList<File>();
      for (String arg : args) {
          File file = new File(arg);
          if (!file.exists()) {
              System.err.println("File " + file + " does not exist.");
              continue;
          }
	   addFilesRecursively(files, file);
      }
      Pongo pp = new Pongo();
      long startTime = System.nanoTime();
      long parseStart, parseTime;
      for (File file : files) {
          try {
             // A bit screwball, we'll dump the tree if there is only one arg. :-)
              parseStart = System.nanoTime();
              PegGrammar root = pp.parseFile(file, files.size() == 1);
              String fileName = file.getName();
              fileName = "grammar/generated/" + fileName.replaceAll(".peg", ".ccc");
              File outFile = new File(fileName);
              PrintStream ps;
              if (outFile.createNewFile() || outFile.exists() && outFile.canWrite()) {
                  ps = new PrintStream(outFile);
              } else {
                  System.err.println("Cannot create or write file " + outFile);
                  ps = System.out;
              }
              if (pp.convert(root, ps)) {
                  System.out.println("CongoCC parser written to " + outFile);
              };
          }
          catch (Exception e) {
              System.err.println("Error processing file: " + file);
              e.printStackTrace();
	          failures.add(file);
              continue;
          }
          parseTime = System.nanoTime() - parseStart;
          String parseTimeString = "" + parseTime/1000000.0;
          parseTimeString = parseTimeString.substring(0, parseTimeString.indexOf('.')+2);
          System.out.println("Parsed " + file + " in " + parseTimeString + " milliseconds.");
          successes.add(file);
       }
       System.out.println();
       for (File file : failures) {
           System.out.println("Parse failed on: " + file);
       }
       if (files.size() > 1) {
           System.out.println("\nParsed " + successes.size() + " files successfully");
           System.out.println("Failed on " + failures.size() + " files.");
       }
       String duration = "" + (System.nanoTime()-startTime)/1E9;
       duration = duration.substring(0, duration.indexOf('.') + 2);
       System.out.println("\nDuration: " + duration + " seconds");
       if (!failures.isEmpty()) System.exit(-1);
    }

   public PegGrammar parseFile(File file, boolean dumpTree) throws IOException {
       PegParser parser = new PegParser(file.toPath());
       PegGrammar root=parser.PegGrammar();
       if (dumpTree) {
           root.dump("");
       }
       return root;
   }

   static public void addFilesRecursively(List<File> files, File file) {
       if (file.isDirectory()) {
           for (File f : file.listFiles()) {
	         addFilesRecursively(files, f);
	   }
       }
       else if (file.getName().endsWith(".peg")) {
           files.add(file);
       }
   }

   static public void usage() {
       System.out.println("Usage: java Pongo <sourcefiles or directories>");
       System.out.println("If you just pass it one source file, it dumps the AST");
       System.exit(-1);
   }
   
   private static final Set<String> JAVA_CORE_CLASSES = Set.of(
           // java.lang package
           "String", "Integer", "Double", "Boolean", "Character", "Math", "Object", "Class", 
           "System", "Runtime", "Thread", "Exception", "Error", "Enum", "Comparable",
           "Throwable", "StringBuilder", "StringBuffer", "Void", "Number", "StrictMath",

           // java.util package
           "List", "ArrayList", "LinkedList", "Vector", "Stack", "Queue", "Deque",
           "HashMap", "TreeMap", "Hashtable", "HashSet", "TreeSet", "Collections",
           "Optional", "Iterator", "Random", "Date", "UUID", "Stream",

           // java.io package
           "File", "FileReader", "FileWriter", "BufferedReader", "PrintWriter", "Console",
           "InputStream", "OutputStream", "Reader", "Writer"
       );

   static boolean isJavaCoreClass(String className) {
       return JAVA_CORE_CLASSES.contains(className.trim());
   }
   
   static final boolean AUTO_ENTAILMENT = true;
   static final boolean ANTLR = false;
   static final boolean TRACE=false;
   
   public void convert(PegGrammar n) {
       convert(n, System.out);
   }
   
   public boolean convert(PegGrammar n, PrintStream ps) {
       try {
           PegVisitor visitor = new PegVisitor(new IndentingPrintStream(ps));
           visitor.visit(n);
           return true;
       } catch (Exception e) {
           e.printStackTrace();
           return false;
       }
   }
   
   static String convertEscapedString(String literal) { 
       if (
           ((literal.startsWith("\"") && literal.charAt(literal.length()-1) == '\"') ||
           (literal.startsWith("'") && literal.charAt(literal.length()-1) == '\'')) &&
           literal.length() > 1
          ) {
           literal = literal.substring(1, literal.length() - 1);
       }
       StringBuilder sb = new StringBuilder("\"");
       boolean isEscape = false;
       int oDigit = 0;
       int oAccum = 0;
       for (int i=0; i < literal.length(); i++) {
           char c = literal.charAt(i);
           if (oDigit > 0 && oDigit <= 3) {
               if ('0' > c || c > '7') {
                   oAccum = (oAccum * 8) + (c - '0');
                   oDigit++;
                   continue;
               } else {
                   String hex = String.format("%04X", oAccum); 
                   sb.append("\\u").append(String.format("%04X", oAccum));
               }
           }
           oDigit = 0;
           if (isEscape) {
                isEscape = false;
                if ('0' > c || c > '7') {
                   switch (c) {
                   case 'u' :
                   case 'U' : {
                       sb.append('\\')
                       .append(c)
                       .append(literal.charAt(++i))
                       .append(literal.charAt(++i))
                       .append(literal.charAt(++i))
                       .append(literal.charAt(++i));
                       break;
                   }  
                   case 'n':
                   case 'r':
                   case 't':
                   case '\\':
                   case '\"':
                       sb.append('\\');
                       // fall thru
                   default:
                       sb.append(c);
                   }
                } else {
                    // octal ASCII code point
                    oAccum = c - '0';
                    oDigit = 1;
                }
           } else {
               if (c == '\\') {
                   isEscape = true;
                   continue;
               }
               if (c == '\"') {
                   sb.append("\\\"");
                   continue;
               }
               sb.append(c);
           }
       }
       sb.append("\"");
       return sb.toString();
   }
   
   static String normalizeForJava(String raw) {
       // properly escape for Java
       raw = convertEscapedString(raw);
       // remove outer quotes
       raw = raw.substring(1, raw.length() - 1);
       // now normalize whitespace
       StringBuilder sb = new StringBuilder();
       boolean isSkipping = false;
       for (int i = 0; i < raw.length(); i++) {
           char aChar = raw.charAt(i);
           if (aChar > 0x20) {
               sb.append(aChar);
               isSkipping = false;
           } else if (!isSkipping) {
               sb.append(' ');
               isSkipping = true;
           }
       }
       return sb.toString();
   }
   
   public class IndentingPrintStream extends PrintStream {
       int indentation = 0;
       int indent = 2;
       boolean isNL = true;
       public IndentingPrintStream(OutputStream out) {
           super(out);
       }
       public IndentingPrintStream indent() {
           indentation += indent;
           return this;
       }
       public IndentingPrintStream outdent() {
           indentation -= indent;
           return this;
       }
       public IndentingPrintStream nl() {
           super.println();
           super.append(" ".repeat(indent*indentation));
           return this;
       }
       public IndentingPrintStream colon() {
           super.print(" : ");
           return this;
       }
       public IndentingPrintStream semi() {
           super.print("; ");
           return this;
       }
       public IndentingPrintStream append(String s) {
           super.append(s);
           return this;
       }
   }
   
   public class PegVisitor extends Node.Visitor {
       
       IndentingPrintStream ps = null;
       Map<String,String> tokenDefinitions = new LinkedHashMap<>();
       int tokenId = -1;
       
       private String mungPegIdentifier(String identifier) {
           if (isJavaCoreClass(identifier)) {
               return "_"+identifier;
           }
           return identifier;
       }
       
       private String resolveToken(String tokenDef) {
           String tokenName;
           if (!tokenDefinitions.containsKey(tokenDef)) {
               if (tokenDef.matches("\\[\".(.)?\\\"\\]")) {
                   tokenName = tokenDef.substring(1, tokenDef.length() - 1);
                   return tokenName;
               }
               tokenName = "CLASS_" + ++tokenId;
               tokenDefinitions.put(tokenDef, tokenName);
           } else {
               tokenName = tokenDefinitions.get(tokenDef);
           }
           return "LEXICAL_STATE " + tokenName +"_STATE (<" + tokenName + ">)";
       }
       
       private void printTokenDefinitions(IndentingPrintStream ps) {
           ps.nl().append("<ANY> TOKEN : <ANY_CHAR: ~[] >;").nl();
           Iterator<Map.Entry<String,String>> i = tokenDefinitions.entrySet().iterator();
           while (i.hasNext()) {
               Map.Entry<String,String> e = i.next();
               String tokenName = e.getValue();
               String tokenDefinition = e.getKey();
               ps.append("<").append(tokenName).append("_STATE> TOKEN:").append(" ")
                 .append("<").append(tokenName).append(": ").append(tokenDefinition)
                 .append(" >;").nl();
           }
       }
       
       public PegVisitor(IndentingPrintStream ps) {
           this.ps = ps;
           ps.append("/* Generated by: PONGO (Peg -> cONGOcc) Parser Generator. Do not edit. */").nl();
       }
       
       void visit(Comment n) {
           String comment = n.toString();
           if (comment.startsWith("#")) {
               comment = comment.replaceFirst("#", "//");
           }
           ps.append(comment).nl(); 
           recurse(n);
       }
       
       void visit(WHITESPACE n) {
           ps.append(n.toString());
       }
       
       void visit(Grammar n) {
           recurse(n);
           printTokenDefinitions(ps);
       }
       
       void visit(Definition n) {
           String name = mungPegIdentifier(n.firstChildOfType(Identifier.class).toString());
           ps.nl().append(name).colon().indent().nl();
           if (TRACE) {
               ps.append("{System.out.println(\"applying?: " + normalizeForJava(name) + "\");}# ");
           }
           visit(n.firstChildOfType(Expression.class));
           if (TRACE) {
               ps.append("{System.out.println(\"applied!: " + normalizeForJava(name) + "\");}# ");
           }
           ps.outdent().nl().semi();
       }
       
       boolean isChoiceExpression = false;
       Stack<Boolean> isChoice = new Stack<>();
       
       void visit(Expression expression) {
           if (expression.childrenOfType(Sequence.class).size() > 1) {
               isChoice.push(true);
               if (ANTLR) {
                   ps.append("( ");
               }
           } else {
               isChoice.push(false);
           }
           recurse(expression);
           if (ANTLR && isChoice.peek()) {
               ps.append(")+ ");
           }
           isChoice.pop();
       }
       
       void visit(Sequence sequence) {
           List<Entails> entails = sequence.childrenOfType(Entails.class);
           isExplicitEntailment = entails != null && entails.size() > 0;
           if (ANTLR && isChoice.peek()) {
               ps.append("ENSURE {&} ");
           }
           recurse(sequence);
           if (AUTO_ENTAILMENT && !isPredicate && !isExplicitEntailment && isChoice.peek()) {
               ps.append(" =>|| ");
           }
           if (TRACE) {
               ps.append("{System.out.println(\"chosen: " + normalizeForJava(sequence.toString()) + "\");}# ");
           }
       }
       
       boolean isPredicate = false;
       
       void visit(Prefix n) {
           if (n.firstChildOfType(NOT.class) != null || n.firstChildOfType(AND.class) != null) {
               isPredicate = true;
               //TODO: use ASSERT if this is post-entailment (explicit)
               ps.append("ENSURE ");
               visit(n.firstChildOfType(BaseNode.class));
               ps.append("( ");
               visit(n.firstChildOfType(Suffix.class));
               ps.append(") ");
           } else {
               isPredicate = false;
               recurse(n);
           }
       }
       
       boolean isExplicitEntailment = false;
       
       void visit(Suffix n) {
           boolean isModified = false;
           if ( n.size() > 1 && 
               (n.get(1).toString().trim().equals("?") ||
                n.get(1).toString().trim().equals("*") ||
                n.get(1).toString().trim().equals("+"))) {
               isModified = true;
           }
           // if */?/+, wrap the Primary in parentheses
           if (isModified) {
               ps.append("( ");
           }
           Node primary = n.get(0);
           visit(primary);
           if (isModified) {
               if (AUTO_ENTAILMENT && !isPredicate && !isExplicitEntailment) {
                   ps.append("=>|| ");
               }
               if (TRACE) {
                   ps.append("{System.out.println(\"iterating: " + normalizeForJava(primary.toString()) + "\");}# ");
               }
               ps.append(")");
           }
           for (int i = 1; i < n.size(); i++) {
               visit(n.get(i));
           }
       } 
       
       boolean isVoid = false;
       
       void visit(FRAGMENT n) {
           isVoid = true;
       }
       
       void visit(Identifier n) {
           ps.append(mungPegIdentifier(n.toString()));
           if (isVoid) {
               ps.append("#void");
           }
           ps.append(' ');
           isVoid = false;
       }
       
       void visit(Literal n) {
           // "..." | '...' with C escaping
           // form the Java string, use it in situ
           String literal = "";
           List<Char> chars = n.childrenOfType(Char.class);
           if (chars != null && !chars.isEmpty()) {
               for (Char literalChar : chars) {
                   literal += literalChar.toString();
               }
           } else {
               // only when bootstrapping from original, hand-coded  boot_peg.inc!
               literal = n.toString();
           }
           ps.append(convertEscapedString(literal)).append(" ");
       }
       
       void visit(_Class n) {
           // [...] with \] and \\ escaped
           StringBuilder sb = new StringBuilder();
           List<Range> ranges = n.childrenOfType(Range.class);
           sb.append('[');
           if (ranges.size() > 0) {
               for (Range r : ranges) {
                   List<Char> chars = r.childrenOfType(Char.class);
                   String char1 = chars.get(0).toString();
                   sb.append(convertEscapedString(char1));
                   if (chars.size() > 1) {
                       String char2 = chars.get(1).toString();
                       sb.append('-').append(convertEscapedString(char2));
                   }
                   sb.append(',');
               }
               sb.deleteCharAt(sb.length() - 1);
           }
           sb.append(']');
           ps.append(resolveToken(sb.toString())).append(" ");
       }
       
       void visit(QUESTION n) {
           ps.append("? ");
           recurse(n);
       }
       
       void visit(STAR n) {           
           ps.append("* ");
           recurse(n);
       }
       
       void visit(Cardinality n) {
           recurse(n);
           ps.append(" ");
       }
       
       void visit(PLUS n) {
           ps.append("+ ");
           recurse(n);
       }
       
       void visit(Entails n) {
           if (!isPredicate) {
               ps.append("=>|| ");
               isExplicitEntailment = true;
           } else {
               ps.append(" /* explicit entailment is ignored in this position */ ");
           }
           recurse(n);
       }
       
//       void visit(Action n) {
//           ps.append("{{").append(n.toString()).append("}}");
//           recurse(n);
//       }
       
       void visit(SLASH n) {
           ps.append("| ");
           recurse(n);
       }
       
       void visit(BAR n) {
           ps.append("| ");
           recurse(n);
       }
       
       void visit(HASH n) {
           ps.append("&");
           recurse(n);
       }
       
       void visit(NOT n) {
           ps.append('~');
           recurse(n);
       }
       
       void visit(OPEN n) {
           ps.append("( ");
           recurse(n);
           isChoice.push(false);
       }
       
       void visit(CLOSE n) {
           ps.append(") ");
           recurse(n);
           isChoice.pop();
       }
       
       void visit(DOT n) {
           ps.append("LEXICAL_STATE ANY (<ANY_CHAR>) ");
           recurse(n);
       }
   }
}
