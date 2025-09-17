#var csPackage = globals::getPreprocessorSymbol('cs.package', settings.parserPackage)
#var extension = globals::getStringSetting("TEST_EXTENSION", "")
#var testProduction = globals::getStringSetting("TEST_PRODUCTION", "")
namespace ${csPackage}.test
{
    using System;
    using System.IO;
    using ${csPackage};

    public static class ParseFiles
    {
#if !extension.empty && !testProduction.empty

        private static int successes = 0;
        private static int failures = 0;

        private static void parseFile(string arg)
        {
            try {
                Parser p = new Parser(arg);
                p.Parse${testProduction}();
                Console.WriteLine($"The C# impl parsed {arg}.");
                successes++;
            }
            catch (ParseException e) {
                Console.WriteLine($"The C# impl failed to parse {arg}:");
                Console.WriteLine(e);
                failures++;
            }
        }
/#if

        public static void Main(string[] args)
        {
#if extension.empty
            Console.WriteLine("No TEST_EXTENSION setting defined in grammar");
            Environment.Exit(1);
#elif testProduction.empty
            Console.WriteLine("No TEST_PRODUCTION setting defined in grammar");
            Environment.Exit(1);
#else
            if (args.Length == 0) {
                Console.WriteLine("Usage: ParseFiles <files or directories with files to parse>");
                Environment.Exit(1);
            }
            long start = System.DateTime.Now.Ticks;

            foreach(string arg in args) {
                if (arg.EndsWith(".${extension}") && File.Exists(arg)) {
                    parseFile(arg);
                }
                else foreach(var f in Directory.EnumerateFiles(arg, "*.${extension}", SearchOption.AllDirectories))
                {
                    parseFile(f);
                }
            }

            Console.WriteLine($"The C# impl successfully parsed {successes} files.");
            Console.WriteLine($"The C# impl failed on {failures} files.");

            long duration = (System.DateTime.Now.Ticks - start) / 10000;
            Console.WriteLine($"Duration: {duration} milliseconds.");
/#if
        }
    }
}
