using nla_test;

internal class Tester
{
    private static void Main(string[] args)
    {
        var p = new Parser(args[0]);

        try {
            p.ParseRoot();
            p.parse_status = "parse succeeded";
        }
        catch (ParseException) {
            p.parse_status = "parse failed";
        }
        System.Console.WriteLine($"{args[0]}: glitchy = {p.LegacyGlitchyLookahead.ToString().ToLower()}: {p.lookahead_status}, {p.parse_status}");
    }
}
