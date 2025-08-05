Use the build.xml in this directory to build a Java source code parser in either Java, C# or Python. This is the same Java parser/grammar that is used in CongoCC itself to handle embedded java code in CongoCC grammar files. In fact, the CongoCC.ccc grammar file in the src/grammars directory simply uses the grammar file in this directory via an [INCLUDE](https://doku.javacc.com/doku.php?id=include).

You can see it in action by simply running:

     ant test-java

The Java parser (in Java) can be tested on its own via:

     java JParse <files or directories>

If you give it a directory, it parses all the .java files in there recursively. Also, it will go into .zip and/or .jar files looking Java source!

If you run it with the `-p` option, it launches parsers in multiple threads, so it tends to be faster. It runs with quieter output, using `-q`. The `-s` option parses all the Java source code in `src.zip`, assuming it can find it, which it usually can.

The main routine has the somewhat odd feature that, if there is only one source file as an argument, it also outputs the parse tree to stdout.

The Python parser can be tested via:

    python3 jparse.py <files or directories>

It seems to be about 100 times slower than the Java parser!

The CSharp parser can be tested via:

    dotnet ./cs-javaparser/bin/Debug/net6.0/org.parsers.java.dll <directory to run over>

REPORTING BUGS
--------------

If you find bugs in the grammar, please write to revusky@congocc.org or visit our community at https://discuss.congocc.org/
