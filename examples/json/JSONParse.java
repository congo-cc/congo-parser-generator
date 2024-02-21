import java.io.*;
import org.parsers.json.*;

public class JSONParse {
    static public void parseFile(File file, boolean dumpTree) throws IOException, ParseException {
        JSONParser parser = new JSONParser(file.toPath());
        parser.Root();
        Node root=parser.rootNode();
        if (dumpTree) {
            root.dump();
        }
    }

    static public void main(String[] args) throws Exception {
      if (args.length == 0) {
        usage();
      }
      else {
        for (String arg :args) {
          File f = new File(arg);
          try {
            parseFile(f, true);
          }
          catch (Exception e) {
            System.err.println("Error parsing file: " + f);
            e.printStackTrace();
          }
        }
      }
    }

    static public void usage() {
      System.out.println("Little test harness for JSON Parser");
      System.out.println("java JSONParse <filename>");
    }
}
