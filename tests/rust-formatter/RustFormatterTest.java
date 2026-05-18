import org.congocc.codegen.rust.RustFormatter;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.rust.ast.Crate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple golden-file tests for {@link RustFormatter}.
 * Run after {@code ant -Drust.enabled=true compile jar}:
 * <pre>
 *   javac -cp build:congocc.jar -d build tests/rust-formatter/RustFormatterTest.java
 *   java -cp build:congocc.jar RustFormatterTest
 * </pre>
 */
public class RustFormatterTest {

    public static void main(String[] args) throws Exception {
        Path casesDir = Path.of("tests/rust-formatter/cases");
        int failures = 0;
        for (Path input : Files.list(casesDir).filter(p -> p.toString().endsWith(".rs")).sorted().toList()) {
            String name = input.getFileName().toString();
            Path expectedPath = casesDir.resolve(name.replace(".rs", ".expected"));
            if (!Files.exists(expectedPath)) {
                System.err.println("SKIP (no .expected): " + name);
                continue;
            }
            String source = Files.readString(input);
            Crate crate = CongoCCParser.parseRustFile(name, source);
            String actual = new RustFormatter().format(crate);
            String expected = Files.readString(expectedPath);
            if (!actual.equals(expected)) {
                failures++;
                System.err.println("FAIL: " + name);
                System.err.println("--- expected ---");
                System.err.print(expected);
                System.err.println("--- actual ---");
                System.err.print(actual);
            } else {
                System.out.println("OK: " + name);
            }
        }
        if (failures > 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("All formatter tests passed.");
    }
}
