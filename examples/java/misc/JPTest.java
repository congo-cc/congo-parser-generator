// Test harness I was using to benchmark
// javaparser (https://javaparser.org)
// Naturally, you need a javaparser.jar on
// the classpath to run this.


import java.io.IOException;
import java.util.ArrayList;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration.LanguageLevel;

ArrayList<String> failures = new ArrayList<>();
ArrayList<Object> roots = new ArrayList<>();
boolean parallel, retainTrees;

public void main(String[] args) {
    long startTime = System.currentTimeMillis();
    var filenames = new ArrayList<String>();
    for (var arg : args) {
        if (arg.startsWith("-")) {
            if (arg.substring(1).startsWith("p")) parallel = true;
            else if (arg.substring(1).startsWith("q")) quiet = true;
            else if (arg.substring(1).startsWith("r")) retainTrees = true;
        }
        else {
           filenames.add(arg);
        }
    }
    if (parallel) IO.println("Parsing in multiple threads.");
    Stream<String> stream = parallel ? filenames.parallelStream() : filenames.stream();
    stream.forEach(filename->parseFile(filename));
    IO.println("Successfully parsed " + (filenames.size() - failures.size()) + " files.");
    IO.println("Failed on: " + failures.size() + " files.");
    for (var f : failures) {
        System.out.println("Failed on: " +f);
    }
    IO.println("Duration: " + (System.currentTimeMillis()-startTime) + " milliseconds.");
}

void parseFile(String filename) {
    if (!quiet) IO.println("Parsing file: " + filename);
    var config = StaticJavaParser.getParserConfiguration();
    config.setLanguageLevel(LanguageLevel.JAVA_26);
    var path = FileSystems.getDefault().getPath(filename);
    try {
       var root = StaticJavaParser.parse(path);
       if (retainTrees) roots.add(root);
    } catch (Exception e) {
        failures.add(filename);
        e.printStackTrace();
    }
}