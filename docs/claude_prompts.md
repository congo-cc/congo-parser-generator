# Production Rust Parser-Generator

The ultimate goal of this project is to output production quality, Rust language parsers for any valid CongoCC language specification file (.ccc files).  We will improve upon the performance, maintainability, usability and integration of previous prototypes by starting from scratch.  This means we will:

- Only use code and artifacts in the "forked" branch of the git repository.
- Ignore all previous logs and history involved in prior attempts to generate Rust parsers.

Our first task will be to inspect the current code and generate a detailed CLAUDE.md file, which includes an explanation of the code layout and usage.  In addition to inspecting the local code in the "forked" branch, documentation from the CongoCC site (https://parsers.org/) can be incorporated.  Please generate CLAUDE.md for the code as it now exists before we begin adding Rust support. 

## Possible Approaches for Rust Support

Please suggest possible designs for enhancing CongoCC in the "forked" branch with the ability to output Rust parsers.  The requirements are as follows:

1. High performance - input files should be parsed quickly as possible and return a representation of the root node.  The parse can complete without constructing a complete AST as long as an AST can be constructed on demand using the root node.     
2. Efficient AST traversal - users can use the root node of a parse to obtain an AST that is optimized for traversal.  Users can walk the AST in a depth-first traversal by using the Visitor design pattern.  The AST should preserve all significant information about each node, including parent and child relationships, source image, source code line and position, and operator and operand types.     
3. Precise error messages - error messages should report the line and position where an error occurs, the context in which the error occurs, the set of expected lexemes valid at that point in the parse.
4. Ease of use - the interface for parsing, AST traversal, debugging, and complete and concise AST display should be simple to use.
5. Integrated support - Rust support must be seemlessly integrated into the existing CongoCC codebase, build system, test regime, examples and documentation so that the CongoCC developers can easily assess whether or not to incorporate it.
6. Comprehensive testing and example code- Rust parsers should be generated and tested for all *.ccc specification files targeted by the other supported language parsers.

Compare the pros and cons of each suggested design. 

## Develop an Implementation Plan

Please develop detailed plan for implementing Design B (Direct Tree with Index-Based Nodes).  Include in the plan a summary of directories and public APIs that will be created.  Save the plan to docs/rust_plan.md.  Present the plan for review before making any changes.

### Plan Tweaks

1. Very nice.  I'd like to make several changes to the plan.  I'll address one at a time.  The first change is to make edition = "2024" in Cargo.toml.ctl
2. I expect that the syntax_tree is an Abstract Syntax Tree rather than a raw parse tree.  If that's the case, please rename the file syntax_tree.rs to ast.rs and change all names that refer to syntax_tree or tree to ast.

### Visitor Alternatives

In addition to the current read-only Visitor API, we also need to support both node-local and structural change capabilities, preferably using a map style API. What API would  
  you suggest?   

I'd like to include a pretty print API in the plan for every generated parser.  What implementation would you recommend? 

## Plan Compleness Analysis

Let's check the plan for complete coverage of CongoCC and the grammars it can process.  It is IMPORTANT to recognize that CongoCC is a parser-generated written in Java that generates parsers in multiple languages.  Our goal is to support all features of the CongoCC grammar specification when generating Rust parsers.  This implies that a fully functioning Rust parser should be producible from any valid CongoCC grammar file (.ccc). 

Below is documentation that defines how grammars are specified in CongoCC and the features that are supported.  Since CongoCC evolved from JavaCC and JavaCC21, CongoCC essentially still supports JavaCC's specification.  We start with JavaCC documentation since it is more complete that CongoCC's.  We then reference CongoCC documents and grammar.

### Legacy JavaCC Documentation

1. [JavaCC grammar file syntax](https://javacc.github.io/javacc/documentation/grammar.html)
2. [JavaCC BNF](https://javacc.github.io/javacc/documentation/bnf.html)

### CongoCC Documentation

1. [CongoCC grammar specification](https://github.com/congo-cc/congo-parser-generator/blob/master/src/grammars/CongoCC.ccc)
2. [Fundementals](https://parsers.org/tips-and-tricks/having-fun-with-the-fundamentals-part-i/)
3. [Unicode support](https://parsers.org/javacc21/javacc-21-now-supports-full-unicode/)
4. [Code injection](https://wiki.parsers.org/doku.php?id=code_injection_in_javacc_21)
5. [Assertions](https://parsers.org/tips-and-tricks/introducing-assertions/)
6. [Revised assertions](https://parsers.org/announcements/revisiting-assertions-introducing-the-ensure-keyword/)
7. [Context-sensitive tokenization](https://parsers.org/javacc21/activating-de-activating-tokens/)
8. [Contextual predicates](https://wiki.parsers.org/doku.php?id=contextual_predicates)
9. [Nested lookahead](https://parsers.org/javacc21/nested-lookahead-redux/)

### Analysis Questions

1. Is it true that Rust parsers ever need to read or understand the CongoCC grammar?
2. Can generated Rust parsers implement all semantics and features expressible in a CongoCC grammar file?
    1. If not, explain what features cannot be implemented and why.
3. How will code injection be implemented in Rust parsers?
    1. In what language will injected code be specified in the grammar file? 
4. How will lookahead/scan be implemented in Rust parsers?


<!-- Very nice analysis.  Let's have CongoCC automatically set -p __rust__ when -lang rust is set on the command line.  Also, in cases where injected Java code cannot be safely translated to Rust, insert the commented out Java code were the Rust code would go with an explanation that this requires user intervention.  In such cases, the parser should output a warning for each case where user intervention is required.  -->

It does not appear that the implementation specified in docs/rust_plan.md has been completed.  I see comments like, "This template will be fleshed out in Step 6 of the implementation plan." in .ctl files.  I don't see the AstMapper as described in section 2.7 of the plan.  I don't see any Rust example code or tests of existing grammars described in section 4.1 of the plan.  Let's do an inventory of the parts of the plan that have been implemented and the parts not yet implemented.

Let's begin by doing the following:
1. Everything in the plan (docs/rust_plan.md) must be implemented as complete, production quality code using best practices described in rust-skills.  No TODO's, missing functions, or stub code to be filled in later.  The generated parsers must be correct, safe and high performance.
2. A Rust parser must be generated for every grammar file identified in section 4.1 and the Rust parsers must be validated using the same inputs used by other language parsers.  No failures can be ignored.
3. Generate simple examples of using Rust parsers.
4. Generate a README_RUST.md file that explains how to build, test and use Rust parsers.


The file AntTest.out captures the output of "ant test" in the latest congo-rust project with Rust support.  The file AntTest.orig.out captures the output of "ant test" in the latest production version of CongoCC (no Rust support).  Below are new warnings that appear in the new code that don't appear in the production code:
1. 
```
[java] Warning: examples/csharp/CSharp.ccc:209:7:The token VOID cannot be matched at this point.
``` 
2. 
```
test-rust:
     [exec]    Compiling org-parsers-lua v0.1.0 (/home/rich/git/congo-rust/examples/lua/rust-luaparser)
     [exec] warning: comparison is useless due to type limits
     [exec]     --> parser.rs:3307:43
     [exec]      |
     [exec] 3307 |             self.builder.close_node_scope(self.builder.node_arity()>=0);
     [exec]      |                                           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
     [exec]      |
     [exec]      = note: `#[warn(unused_comparisons)]` on by default
     [exec] 
     [exec] warning: `org-parsers-lua` (lib test) generated 1 warning
     [exec] warning: `org-parsers-lua` (lib) generated 1 warning (1 duplicate)
```

The first warning appears in multiple places.  The second only in one place.  Please analyze why these warnings occur and suggest how to fix them.

## Plan for Refactoring Rust Support

It appears that the Java, Python and CSharp parsers are tested against many more test files for the json, jsonc and lua languages.  We want all the json, jsonc and lua language files that are tested by Java, Python and CSharp parsers to also be tested by the cooresponding Rust parsers for those languages.  Please generate a plan for comprehensively testing all the Rust parsers generated during the build and for reporting the test results.  

I've tried to explain that we need to comprehensively test Rust parsers against all language files for the grammars they support.  I'll try explaining again by way of example.  The generated Java, Python and CSharp parsers each parse many .lua language files.  The Rust parser parses none.  The examples/lua/rust-luaparser/test-data directory contains many lua language files, but the examples/lua/rust-luaparser/tests/parse_files.rs test driver SILENTLY SKIPS the tests if the test directory is not found.  A similar situation happens with cics testing.  Please do the following:

1. Under no circumstances EVER silently fail to run tests.  If tests can't be run, log exactly what was expected and the failure that occurred.
2. For every grammar for which a Rust parser is generated, run all tests that the any of the cooresponding Java, Python and CSharp parsers run.
    1. If test language files are generated dynamically, make sure a copy of them is available for Rust parsers to process.
3. It is absolutely critical for there to be extensive testing of generated Rust parsers before Rust support can be considered production ready.  Always show exactly what tests were run for each parser and whether a test succeeded or failed.  The failure of any test indicates a bug that will have to be fixed.

## Code Injection Analysis

Please analyze how the examples/arithmetic1.ccc and examples/arithmetic2.ccc grammars guide the implementation of a Rust parser.  Specifically, how to the INJECT code blocks which are written in Java get translated into Rust in the generated parser.  Please document the results of your analysis in docs/arithmetic_analysis.md.

Nice analysis.  Let's implement your recommended "Option B (#if __rust__ blocks), but with a concrete usability improvement".  Please do the following:

1. Update arithmetic_analysis.md with your comparison of the different approches to INJECT support and your recommendation.
2. Implement your recommendation to work with any grammar file, including complex ones like the Java, C# and Python grammars.
    1. For every parser that requires handwritten Rust code to complete the grammar's specification, create an additional FIXME.md file in the parser source directory that provides an inventory of all fix-ups needed in the generated code.  
    2. Include in FIXME.md an overview of the interfaces the developer can use in writing their code and a recommendation of a test methodology.
    3. Provide any Rust helper functions that will make it easier to write and test the Rust gap code.
3. Regenerate the examples/arithmetic parser with the recommended INJECT support.
4. Guarentee no regression by rerunning all tests from scratch.   

Good work. Should the Java code injected in grammar files be inserted into inject.rs files as block comments?  For example, should commented out Java code be written into each impl site in rust-arith2/inject.rs?  If so, please enhance the Rust parser-generator to do that and then verify that no regression has occurred.

## Rust Parser for Java Source Code

Please update the build to generate a Rust parser for the examples/java/Java.ccc grammar, which is the grammar for Java source code.  At this time, the new Rust parser should be compiled but not have any tests run because handwritten Rust code needs to be supplied first.  Eventually, the Rust parser will test against the same files as the Java parser, but for now comment out the execution of Rust parser tests in build.xml.  Run the build and check that the Rust parser is generated and compiled and that no regressions have taken place.   

## Rust Parser for Python Source Code

Please update the build to generate a Rust parser for the examples/python/Python.ccc grammar, which is the grammar for Python source code.  At this time, the new Rust parser should be compiled but not have any tests run because handwritten Rust code needs to be supplied first.  Eventually, the Rust parser will test against the same files as the Python parser, but for now comment out the execution of Rust parser tests in build.xml.  Run the build and check that the Rust parser is generated and compiled and that no regressions have taken place.   

## Rust Parser for C# Source Code

Please update the build to generate a Rust parser for the examples/csharp/CSharp.ccc grammar, which is the grammar for CSharp source code.  At this time, the new Rust parser should be compiled but not have any tests run because handwritten Rust code needs to be supplied first.  Eventually, the Rust parser will test against the same files as the CSharp parser, but for now comment out the execution of Rust parser tests in build.xml.  Run the build and check that the Rust parser is generated and compiled and that no regressions have taken place.

## Rust Arithmetic Evaluator Code Generation

The overall goal is to implement a fully functional arithmetic expression evaluator in Rust in examples/arithmetic.  The Arithmetic2.ccc and Arithmetic1.ccc language files were used to generate the rust-arith2 and rust-arith1 parser code.  These language files contained INJECT code written in Java, which was not automatically translated into Rust and needs to be completed in this task.  

Please develop a plan to implement the following Rust code:
1. An evaluate() function as shown in rust-arith2/FIXME.md.
2. A new main.rs program that takes an expression string from the command line, parses it and, if valid, evaluates and returns the numeric value of the expression.

Please generate an implementation plan that is guided by the FIXME.md and inject.rs files in rust-arith2 and rust-arith1 directories.  Note that some of the information in these files, such as dependencies on ex1 and ex2 Java code, are incorrect.  The evaluate() code should be added to the one or both of the inject.rs files in rust-arith2 and rust-arith1.  Determining code placement is a key element of the plan.  The commented out Java code in the inject.rs files should remain unchanged for documentation purposes.  

The plan should include the creation of comprehensive positive and negative tests that exercise all common and edge cases.  Under no circumstances should failed tests be skipped or ignored.  Final validation requires that no regression has occured.  The plan should also include the creation of README.md files in rust-arith2 and rust-arith1 that explain in detail how to build, test and use the Rust parser/evaluator.

###  Integrate Evaluator into Build

Very nice.  The final task is to automate the copying of the hand-written files in examples/arithmetic/rust-saved into their cooresponding code directories.  The copying should take place after parser generation but before compilation and testing.  Rebuild cleanly and validate that the build now runs the evaluate.rs tests successfully.

## AST Tranversal Tests

Create test programs in the examples/arithmetic/arith2/tests directory that demonstrate different AST traversal scenarios.  The goal is to provide examples of how to use the AST API, so provide explanatory comments in the code where needed.  Each program will operate on the following 3 inputs: 

    2+3*4
    (2+3)*4
    ((1+2)*(3+4))/(5-(6-7))

All programs will display their output when invoked as follows:

    cargo test --test <program name> -- --nocapture

When run without the --nocaption option, no output will be displayed other than the normal test program success/failure information.  

### Pretty Print Test
Write the **pretty_test.rs** test program that invokes the built-in implementation for pretty printing in pretty.rs for each input.

### Visitor Test
Write the **visitor_test.rs** test program that uses the Visitor trait in visitor.rs to traverse every node in each AST.  For each arithmetic operator encountered, print a single line of output containing the operator and the number of children it has.  An example output line is "operator: *, children: 2".  Indent each line to signify the operator's depth in the AST.

### AstMapper Value Test
Write the **astmapper_value_test.rs** test program to demonstrate how to modify values in an AST.  Begin by evaluating each input expression to determine is numerical value.  Then use the AstMapper trait in visitor.rs to traverse every node in each AST.  Multiply each numeric literal encountered by 2.  Evaluate the modified AST and assert that it's value is the expected value.  For each input value, print the original expression and its value and the modified expression and its value. 

### AstMapper Structure Test
Write the **astmapper_struct_test.rs** test program to demonstrate how to modify the structure of an AST.  Begin by evaluating each input expression to determine is numerical value.  Then use the AstMapper trait in visitor.rs to append an addition operation to the end of each input expression.  The addition operation adds 1 to each expression.  For example, "2+3\*4" becomes "2+3\*4+1".  Evaluate the modified AST and assert that it's value is the expected value.  For each input value, print the original expression and its value and the modified expression and its value. 

### AstMapper Structure2 Test
Let's add another test program to the examples/arithmetic/rust-arith2/tests directory.  The new program will use the same inputs as the previous test programs:

    2+3*4
    (2+3)*4
    ((1+2)*(3+4))/(5-(6-7))

Like the previous test programs, the new program will display its output when invoked as follows:

    cargo test --test <program name> -- --nocapture

When run without the --nocaption option, no output will be displayed other than the normal test program success/failure information.

Write the new **astmapper_struct2_test.rs** test program to demonstrate how to modify the structure of an AST using the AstMapper API.  The **astmapper_struct_test.rs** test program simply modified the input expression strings and then parsed and evaluated those modified strings.  That approach worked, but may not be available or desirable in all cases.  In **astmapper_struct2_test.rs**, we want to map the original expression's AST to a modified AST that has a new addition operation tacked on the to end of the AST.     

Specifically, start with each input expression's AST and use the AstMapper API to insert nodes at the end of the AST that cause 1 to be added to the numeric value of the expression.  The resulting AST must be a valid, parsable AST.  Evaluate the modified AST and assert that it's value is the expected value.  For each input value, print the original expression and its value and the modified expression and its value.  Update documentation to describe this new test and how it differs from **astmapper_struct_test.rs**.  Run all test to verify no regression has occured.

Great.  Let's increase clarity and coverage in **astmapper_struct2_test.rs** by (1) dumping the original AST in addition to the modified AST, and (2) by processing all input expressions from TEST_CASES in astmapper_struct2_ast_validity().

## Pretty Print Tweaks

Please rename the render_ast() method in pretty.rs.ctl to pretty_print(), change all example Rust code to use the new name, and update all comments, test programs and README files to use the new name and correct method signature.  Set the default width for pretty printing to be 120.  Rebuild all example code from scratch and run all Rust test programs.  Insure that no regression has taken place and that all tests succeed.  Never ignore tests that fail or that are missing.

## Update Documentation

This task puts the final touches on Rust-specific documentation.  Specifically, let's check documenation files for completeness and correctness.  The files to check are: 

    README_RUST.md
    examples/arithmetic/rust-saved/arith1/README.md
    examples/arithmetic/rust-saved/arith2/README.md
    examples/arithmetic/rust-saved/README.md

Update README_RUST.md:
1. Include references to all other Rust-specific README files.
2. In addition to the Visitor Pattern section, add sections that briefly describe how and when to use AstMapper:
    1. How to change modify existing nodes in an AST without changing an AST's structure.  
    2. How to change an AST's structure by inserting or deleting nodes. 
3. Describe how to build and run Rust example code.  In particular, point out AST traversal test code:
    1. For visitor usage, see examples/arithmetic/rust-arith2/tests/visitor_test.rs
    2. For modifying node values usage, see examples/arithmetic/rust-arith2/tests/astmapper_value_test.rs
    3. For modifying structure usage, see examples/arithmetic/rust-arith2/tests/astmapper_struct2_test.rs

Update examples/arithmetic/rust-saved/arith2/README.md with a detailed description of AstMapper and how it can be used (1) to modify existing AST nodes and (2) to modify AST structure. 

## Rethinking Structural Changes to ASTs

Let's re-evaluate how structural changes to an AST are made.  Structural changes include the addition, deletion and modification of AST nodes and of parent/child relationships.  The current implementation uses AstMapper (visitor.rs) and AstBuilder (ast.rs) to make structural changes.  The examples/arithmetic/rust-arith2/tests/astmapper_struct2_test.rs shows how to append nodes to the end of an AST.  In this example, 1 is added to input expressions.  A limitation is revealed on line 233 in astmapper_struct2_test.rs:

    let extended_source = format!("{}+1", input);

In general, it will not be so easy to modify the parser's input.  If modifying input text was easy, then AST manipulation wouldn't be necessary since one could always just create a new input string and then parse it.  Specifically, AST structural changes in the middle of an AST or changes that reshuffle parent/child relationships are difficult, in general, to do to input text directly.  Parsers expose ASTs to avoid direct text manipulation, which itself requires parsing.

One possible approach that would eliminate the burden of constructing a new input string would be if every AST had a `generate_string()` method that generated a string based on the current state of the AST.  Generated strings can optionally be validated by using the original parser, so no new validation code is required.  

Please suggest improvements to the current design that achieve these goals:
    1. Generality - Any structural change should be possible to any part of an AST
    2. Ease of Use - Programmers should be able to manually manipulate ASTs in a systematic way
        1. It should be easy traverse ASTs and make structural changes along the way
        2. No step should require solving difficult parsing problems 
    3. Performance - AST modifications should minimize cpu and memory overhead 

If the current design cannot be easily improved, please explain why.

## More AST Testing

Let's create a new test that further demonstrates using synthetic tokens and nodes to change the structure of an AST.  Create the new examples/arithmetic/rust-arith2/tests/astmapper_struct3_test.rs test that processes the ASTs of the following expressions:
-    5 + 6 * (2 * 5)
-    2 * 6 - (5 + 1) / 3
1. For each of the above expressions, do the following:
    1. Replace each occurance of the number 5 in the AST with the synthesized AST for (2 + 3).
    2. Replace each occurance of the number 6 in the AST with the synthesized AST for (2 * 3).
2. Dump the ASTs before modification and after modification.
3. Evaluate the numeric value of the ASTs before modification and after modification.
4. Save the new test in the rust-saved subtree so that it will be preserved between builds. 
5. Rebuild all Rust parsers and run their tests to verify that no regression has taken place.

## Rename Tests for Accuracy and Consistency

In examples/arithmetic/rust-saved/arith2, please rename astmapper_struct2_test.rs to synthetic_struct2_test.rs and rename astmapper_struct3_test.rs to synthetic_struct3_test.rs.  The new names reflect the APIs used in those tests.  Update README_RUST.md and all README.md files under the rust-saved directory to reflect the new names.  Similarly, update the inline comments and function names in the test programs to be consistent with the new names.  Finally, rebuild all Rust parsers and run their tests to verify that no regression has taken place.  
