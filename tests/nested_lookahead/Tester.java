import java.io.IOException;
import java.nio.file.Paths;

import nla_test.TestParser;
import nla_test.ParseException;

public class Tester {

    public static void main (String[] args) throws IOException {
        TestParser p = new TestParser(Paths.get(args[0]));
        try {
            p.Root();
            p.parse_status = "parse succeeded";
        }
        catch (ParseException pe) {
            p.parse_status = "parse failed";
        }
        System.out.println(String.format("%s: glitchy = %s: %s, %s", args[0], p.getLegacyGlitchyLookahead(), p.lookahead_status, p.parse_status));
    }
}


