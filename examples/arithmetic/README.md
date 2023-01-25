Just a simple example for doing 4-function arithmetic.

The `Arithmetic1.ccc` grammar just defines a very simple grammar.

The `Arithmetic2.ccc` uses (via INCLUDE) the grammar defined in `Arithmetic1.ccc` and defines via (INJECT) the routines to evaluate the various nodes in the generated tree.

To build the examples:

     java -jar <path_to_jar>/congocc-full.jar Arithmetic1.ccc
     javac ex1/*.java
     java -jar<path_to_jar>/congocc-full.jar Arithmetic2.ccc
     javac ex2/*.java

To test it:

    java ex1.Calc

and:

    java ex2.Calc