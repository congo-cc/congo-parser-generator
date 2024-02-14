package org.congocc.codegen.python;

import java.util.*;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.congocc.parser.Node;
import org.congocc.parser.python.ast.*;

public class Reaper {
    private static final Logger logger = Logger.getLogger("reaper");
    private final Module module;
    private static final Pattern parserSetPattern = Pattern.compile("(first|follow)_set", Pattern.CASE_INSENSITIVE);
    private static final Pattern methodPattern = Pattern.compile("^(parse_|(backscan|scan|check|assert|recover)\u03a3)");

    public Reaper(Module module) {
        this.module = module;
    }

    private static boolean isAssignment(Statement stmt) {
        return stmt.getFirstChild() instanceof Assignment;
    }

    private static boolean isParserClass(ClassDefinition cd) {
        Name name = cd.firstChildOfType(Name.class);

        return (name != null) && name.toString().equals("Parser");
    }

    public void reap() {
        logger.fine("Reaping started");
        if ("true".equals(System.getenv("CONGOCC_PYTHON_REAPER_OFF"))) {
            logger.fine("Reaping disabled via environment variable, aborting");
            return;
        }

        ClassDefinition pc = module.firstDescendantOfType(ClassDefinition.class, Reaper::isParserClass);
        if (pc == null) {
            logger.fine("Parser class not found, aborting");
            return;
        }

        Node block = pc.getLastChild();
        assert block instanceof Block;
        List<Statement> statements = block.childrenOfType(Statement.class);
        List<Statement> assignments = statements.stream().filter(Reaper::isAssignment).collect(Collectors.toList());
        List<String> keyList;

        Map<String, Statement> parserSets = new HashMap<>();
        for (Statement a : assignments) {
            String name = a.getFirstChild().getFirstChild().toString();

            if (parserSetPattern.matcher(name).find()) {
                logger.fine(String.format("Adding parser set: %s", name));
                parserSets.put(name, a);
            }
        }

        Map<String, FunctionDefinition> wantedMethods = new HashMap<>();
        Map<String, FunctionDefinition> otherMethods = new HashMap<>();
        List<FunctionDefinition> functions = block.childrenOfType(FunctionDefinition.class);
        for (FunctionDefinition f : functions) {
            String name = f.firstChildOfType(Name.class).toString();

            if (methodPattern.matcher(name).find()) {
                if (name.startsWith("parse_")) {
                    logger.fine(String.format("Adding wanted method: %s", name));
                    wantedMethods.put(name, f);
                }
                else {
                    logger.fine(String.format("Adding other method: %s", name));
                    otherMethods.put(name, f);
                }
            }
        }
        logger.fine(String.format("Found %d parser sets and %d methods", parserSets.size(), functions.size()));
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
        Map<String, FunctionDefinition> toInspect = wantedMethods;
        while (!toInspect.isEmpty()) {
            Map<String, FunctionDefinition> inspectNext = new HashMap<>();

            keyList = new ArrayList<>(toInspect.keySet());
            Collections.sort(keyList);
            for (String key : keyList) {
                logger.fine(String.format("Inspecting method %s", key));
                FunctionDefinition method = toInspect.get(key);
                block = method.getLastChild();
                assert block instanceof Block;

                Set<String> dotNames = new HashSet<>();
                for (DotName dn : block.descendantsOfType(DotName.class)) {
                    Node last = dn.getLastChild();
                    dotNames.add(last.toString());
                }
                keyList = new ArrayList<>(dotNames);
                Collections.sort(keyList);
                for (String dn : keyList) {
                    if (parserSets.containsKey(dn)) {
                        logger.fine(String.format("Found reference to parser set %s", dn));
                        parserSets.remove(dn);
                    }
                    else if (otherMethods.containsKey(dn)) {
                        logger.fine(String.format("Found reference to method %s", dn));
                        inspectNext.put(dn, otherMethods.get(dn));
                        otherMethods.remove(dn);
                    }
                }
            }
            wantedMethods.putAll(inspectNext);
            toInspect = inspectNext;
        }
        // What's left in parserSets and otherMethods are now apparently never used
        logger.fine(String.format("Found %d parser sets and %d methods to remove", parserSets.size(), otherMethods.size()));
        keyList = new ArrayList<>(parserSets.keySet());
        Collections.sort(keyList);
        for (String key : keyList) {
            Statement stmt = parserSets.get(key);
            stmt.getParent().remove(stmt);
            logger.fine(String.format("Removed parser set %s", key));
        }
        keyList = new ArrayList<>(otherMethods.keySet());
        Collections.sort(keyList);
        for (String key : keyList) {
            FunctionDefinition fd = otherMethods.get(key);
            fd.getParent().remove(fd);
            logger.fine(String.format("Removed method %s", key));
        }
/*
        Now go through the wanted methods looking for unused scanToEnd variables, and remove their
        declarations. We just look for the identifier in later statements in the method to determine
        usage - pretty simplistic.
*/
        for (FunctionDefinition meth : wantedMethods.values()) {
            block = meth.firstChildOfType(Block.class);
            List<Node> stmts = block.children();
            Node found = null;

            for (Node statement : stmts) {
                if (found == null) {
                    if (statement.toString().equals("scan_to_end = False\n")) {
                        found = statement;
                        // continue; (implicit, comment here for clarity)
                    }
                }
                else {
                    List<Name> idents = statement.descendantsOfType(Name.class);
                    for (Name ident: idents) {
                        if (ident.toString().equals("scan_to_end")) {
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
