# The Congo Parser Generator

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/congo-cc/congo-parser-generator)

The Congo Parser Generator is a Recursive Descent Parser Generator that generates code in Java, Python, and C#. 

Here are some highlights:

## Grammars 

Congo contains complete, up-to-date grammars for:

- [Java](https://github.com/congo-cc/congo-parser-generator/tree/main/examples/java)
- [Python](https://github.com/congo-cc/congo-parser-generator/tree/main/examples/python)
- [C#](https://github.com/congo-cc/congo-parser-generator/tree/main/examples/csharp)
- [Lua](https://github.com/congo-cc/congo-parser-generator/tree/main/examples/lua)
- [JSON](https://github.com/congo-cc/congo-parser-generator/tree/main/examples/json)

Any of these grammars may be freely used in your own projects, though it would be *nice* if you acknowledge the use and provide a link to this project. The above-linked grammars also can be studied as examples. (Best would be to start with the JSON grammar, move on to Lua, then Python, Java, and C# in order of complexity.)

## Cutting Edge Features

CongoCC has some features that, to the best of our knowledge are not in most (or possibly *any*) competing tools, such as:

- [Contextual Predicates](https://wiki.parsers.org/doku.php?id=contextual_predicates)
- [Context-sensitive tokenization](https://parsers.org/javacc21/activating-de-activating-tokens/)
- [Assertions](https://parsers.org/tips-and-tricks/introducing-assertions/) also [here](https://parsers.org/announcements/revisiting-assertions-introducing-the-ensure-keyword/)
- [Clean, streamlined up-to-here syntax](https://wiki.parsers.org/doku.php?id=up_to_here)
- [Support for the full 32-bit Unicode standard](https://parsers.org/javacc21/javacc-21-now-supports-full-unicode/)
- [Code Injection into generated classes](https://wiki.parsers.org/doku.php?id=code_injection_in_javacc_21)
- [Informative Stack Traces that include location information relative to the Grammar]()
- [A Preprocessor allowing one to turn on/off parts of the grammar based on various conditions](https://parsers.org/tips-and-tricks/javacc-21-has-a-preprocessor/)
- [An INCLUDE directive to allow large grammar files to be broken into multiple physical files](https://wiki.parsers.org/doku.php?id=include)

CongoCC also supports [fault-tolerant parsing](https://parsers.org/javacc21/the-promised-land-fault-tolerant-parsing/) that is admittedly in an unpolished, experimental stage, but basically usable.

If you are interested in this project, either as a user or developer, by all means sign up on our [Discussion Forum](https://discuss.congocc.org) and post any questions or suggestions there.

See our [QuickStart Guide](https://parsers.org/home/).
