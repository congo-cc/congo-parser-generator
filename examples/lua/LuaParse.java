import java.io.*;
import java.util.*;

import org.parsers.lua.LuaParser;
import org.parsers.lua.Node;

/**
 * A test harness for parsing Lua source code
  */
public class LuaParse {

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
      long startTime = System.nanoTime();
      long parseStart, parseTime;
      for (File file : files) {
          try {
             // A bit screwball, we'll dump the tree if there is only one arg. :-)
              parseStart = System.nanoTime();
              parseFile(file, files.size() == 1);
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

   static public void parseFile(File file, boolean dumpTree) throws IOException {
       LuaParser parser = new LuaParser(file.toPath());
       Node root=parser.Root();
       if (dumpTree) {
           root.dump("");
       }
   }

   static public void addFilesRecursively(List<File> files, File file) {
       if (file.isDirectory()) {
           for (File f : file.listFiles()) {
	         addFilesRecursively(files, f);
	   }
       }
       else if (file.getName().endsWith(".lua")) {
           files.add(file);
       }
   }

   static public void usage() {
       System.out.println("Usage: java LuaParse <sourcefiles or directories>");
       System.out.println("If you just pass it one Lua source file, it dumps the AST");
       System.exit(-1);
   }
}
