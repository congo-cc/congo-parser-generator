package org.congocc.codegen.csharp;

import java.util.*;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.congocc.parser.Node;
import org.congocc.parser.csharp.ast.*;

public class Reaper {
    private static final Logger logger = Logger.getLogger("reaper");
    private final CompilationUnit cu;
    private static final Pattern parserSetPattern = Pattern.compile("(first|follow)_set", Pattern.CASE_INSENSITIVE);
    private static final Pattern methodPattern = Pattern.compile("^(Parse|(backscan|scan|check|assert|recover)Σ)");

    public Reaper(CompilationUnit cu) {
        this.cu = cu;
    }

    private static boolean isParserClass(ClassDeclaration cd) {
        Identifier ident = cd.firstChildOfType(Identifier.class);

        return (ident != null) && ident.toString().equals("Parser");
    }

    public void reap() {
        if ("true".equals(System.getenv("CONGOCC_CSHARP_REAPER_OFF"))) {
            return;
        }

        List<ClassDeclaration> classes = cu.descendantsOfType(ClassDeclaration.class);
        ClassDeclaration pc = cu.firstDescendantOfType(ClassDeclaration.class, Reaper::isParserClass);
        if (pc == null) {
            return;
        }

        List<FieldDeclaration> fieldDecls = pc.childrenOfType(FieldDeclaration.class);
        Map<String, FieldDeclaration> parserSets = new HashMap<>();

        for (FieldDeclaration fd : fieldDecls) {
            List<VariableDeclarator> varDecls = fd.childrenOfType(VariableDeclarator.class);

            for (VariableDeclarator vd : varDecls) {
                String name = vd.firstChildOfType(Identifier.class).toString();

                if (parserSetPattern.matcher(name).find()) {
                    logger.fine(String.format("Adding parser set: %s", name));
                    parserSets.put(name, fd);
                }
            }
        }

        List<MethodDeclaration> methods = pc.childrenOfType(MethodDeclaration.class);
        Map<String, MethodDeclaration> wantedMethods = new HashMap<>();
        Map<String, MethodDeclaration> otherMethods = new HashMap<>();
        List<String> keyList;

        for (MethodDeclaration m : methods) {
            List<Identifier> idents = m.childrenOfType(Identifier.class);
            String name = idents.get(idents.size() - 1).toString();

            if (methodPattern.matcher(name).find()) {
                if (name.startsWith("Parse")) {
                    logger.fine(String.format("Adding wanted method: %s", name));
                    wantedMethods.put(name, m);
                }
                else {
                    logger.fine(String.format("Adding other method: %s", name));
                    otherMethods.put(name, m);
                }
            }
        }
/*
        We now do multiple passes to resolve dependencies. In each pass, we loop through the methods
        to be inspected (the wanted_methods, initially) and look for names of parser sets or other
        methods. If a parser set name is found, remove it from the parser_sets as is referenced in
        a method we're going to keep. If a method name is found, add it to a "to_inspect" map which
        represents methods to be kept and which will be examined in later passes. When there are no
        more to inspect, the passes end. The methods to be examined in the next pass will be
        transferred from other_methods to an inspect_next mapping.

        We don't need to sort keys before processing, but it's useful to have a deterministic order
        when logging and comparing results on different platforms.
*/
        Map<String, MethodDeclaration> toInspect = wantedMethods;
        while (!toInspect.isEmpty()) {
            Map<String, MethodDeclaration> inspectNext = new HashMap<>();

            keyList = new ArrayList<>(toInspect.keySet());
            Collections.sort(keyList);
            for (String key : keyList) {
                logger.fine(String.format("Inspecting method %s", key));
                MethodDeclaration method = toInspect.get(key);
                Block block = method.firstChildOfType(Block.class);

                Set<String> names = new HashSet<>();
                for (Identifier ident : block.descendantsOfType(Identifier.class)) {
                    names.add(ident.toString());
                }
                for (String n : names) {
                    if (parserSets.containsKey(n)) {
                        logger.fine(String.format("Found reference to parser set %s", n));
                        parserSets.remove(n);
                    }
                    else if (otherMethods.containsKey(n)) {
                        logger.fine(String.format("Found reference to method %s", n));
                        inspectNext.put(n, otherMethods.get(n));
                        otherMethods.remove(n);
                    }
                }
            }
            wantedMethods.putAll(inspectNext);
            toInspect = inspectNext;
        }
        // What's left in parserSets and otherMethods are now apparently never used
        keyList = new ArrayList<>(parserSets.keySet());
        Collections.sort(keyList);
        for (String key : keyList) {
            FieldDeclaration fd = parserSets.get(key);
            fd.getParent().remove(fd);
            logger.fine(String.format("Removed parser set %s", key));
        }
        keyList = new ArrayList<>(otherMethods.keySet());
        Collections.sort(keyList);
        for (String key : keyList) {
            MethodDeclaration meth = otherMethods.get(key);
            meth.getParent().remove(meth);
            logger.fine(String.format("Removed method %s", key));
        }
/*
        Now go through the wanted methods looking for unused scanToEnd variables, and remove their
        declarations. We just look for the identifier in later statements in the method to determine
        usage - pretty simplistic.
*/
        for (MethodDeclaration meth : wantedMethods.values()) {
            Block block = meth.firstChildOfType(Block.class);
            List<Node> statements = block.children();
            Node found = null;

            for (Node statement : statements) {
                if (found == null) {
                    if (statement.toString().equals("var scanToEnd = false;")) {
                        found = statement;
                        continue;
                    }
                }
                else {
                    List<Identifier> idents = statement.descendantsOfType(Identifier.class);
                    for (Identifier ident: idents) {
                        if (ident.toString().equals("scanToEnd")) {
                            found = null;  // pretend we never found it, so it can't be removed
                            break;
                        }
                    }
                    if (found == null) {    // was reset above, no need to look further
                        break;
                    }
                }
            }
            if (found != null) {
                found.getParent().remove(found);
            }
        }
    }
}
