Just a simple example for doing 4-function arithmetic.

The `Arithmetic1.ccc` grammar just defines a very simple grammar.

The `Arithmetic2.ccc` uses (via INCLUDE) the grammar defined in `Arithmetic1.ccc` and defines via (INJECT) the routines to evaluate the various nodes in the generated tree.

To build and run a simple test:

     ant test

To test it:

    java ex2.Calc
