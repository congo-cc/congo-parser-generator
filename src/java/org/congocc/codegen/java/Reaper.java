package org.congocc.codegen.java;

import java.util.*;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;

import static org.congocc.parser.Token.TokenType.*;

/**
 * A visitor that eliminates unused code.
 * It is not absolutely correct, in the sense of catching all
 * unused methods or fields, but works for our purposes.
 * For example, it does not take account overloaded methods, so
 * if the method name is referenced somewhere, it is assumed to be used.
 * However, it might be a reference to a method with the same name
 * with different arguments.
 * Also variable names can be in a sense overloaded by being defined
 * in inner classes, but we don't bother about that either.
 */
class Reaper extends Node.Visitor {
    private final Set<String> usedMethodNames = new HashSet<>();
    private final Set<String> usedTypeNames = new HashSet<>();
    private final Set<String> usedVarNames = new HashSet<>();
    private final Set<String> usedImportDeclarations = new HashSet<>();
    private final CompilationUnit jcu;
    private boolean onSecondPass;

    Reaper(CompilationUnit jcu) {
        this.jcu = jcu;
    }

    void stripUnused() {
        // Visit the tree over and over until
        // nothing is added. Then we can stop.
        int prevNameCount, nameCount;
        do {
            prevNameCount = usedMethodNames.size() + usedTypeNames.size() + usedVarNames.size();
            visit(jcu);
            nameCount = usedMethodNames.size() + usedTypeNames.size() + usedVarNames.size();
        } while (nameCount > prevNameCount);
        // If the name of the method is not in usedMethodNames, we delete it.
        for (MethodDeclaration md : jcu.descendants(MethodDeclaration.class, md->!usedMethodNames.contains(md.getName()))) {
            md.getParent().remove(md);
        }
        // We go through all the private FieldDeclarations and get rid of any variables that
        // are not in usedVarNames
        for (FieldDeclaration fd : jcu.descendants(FieldDeclaration.class, this::isPrivate)) {
            stripUnusedVars(fd);
        }

        for (TypeDeclaration td : jcu.descendants(TypeDeclaration.class, this::isPrivate)) {
            if (!usedTypeNames.contains(td.getName())) {
                td.getParent().remove(td);
            }
        }

        usedMethodNames.clear();
        usedTypeNames.clear();
        usedVarNames.clear();
        onSecondPass = true;
        visit(jcu);

        // Now get rid of unused and repeated imports.
        for (ImportDeclaration imp : jcu.childrenOfType(ImportDeclaration.class)) {
            if (!usedImportDeclarations.add(getKey(imp))) {
                jcu.remove(imp);
                //System.out.println("Removing " + imp);
                continue;
            }
            if (imp.firstChildOfType(STAR) == null) {
                List<Identifier> names = imp.descendants(Identifier.class);
                String name = names.get(names.size()-1).toString();
                // Note that a static import can import methods.
                if (imp.firstChildOfType(STATIC) != null && usedMethodNames.contains(name)) continue;
                if (!usedTypeNames.contains(name)) {
                    //System.out.println("Removing " + imp);
                    jcu.remove(imp);
                }
            }
        }
    }

    private String getKey(ImportDeclaration decl) {
        StringBuilder result = new StringBuilder();
        for (Node child : decl.descendants(Token.class)) result.append(child);
        return result.toString();
    }

    private boolean isPrivate(Node node) {
        // a bit kludgy, when we are on the second pass before 
        // getting rid of imports, we just say nothing is private, since we 
        // presumably got rid of everything to be got rid of, on the previous pass
        // except for unused imports.
        if (onSecondPass) return false;
        if (node.firstChildOfType(PRIVATE) != null) return true;
        Modifiers mods = node.firstChildOfType(Modifiers.class);
        return mods != null && mods.firstChildOfType(PRIVATE) != null;
    }

    void visit(MethodDeclaration md) {
        if (!isPrivate(md)) {
            usedMethodNames.add(md.getName());
        }
        if (usedMethodNames.contains(md.getName())) {
            recurse(md);
        }
    }

    void visit(ObjectType ot) {
        Identifier firstID = ot.firstChildOfType(Identifier.class);
        usedTypeNames.add(firstID.toString());
        recurse(ot);
    }

    void visit(Annotation ann) {
        String firstID = ann.firstDescendantOfType(Identifier.class).toString();
        usedTypeNames.add(firstID);
        recurse(ann);
    }

    void visit(MethodCall mc) {
        String lhs = mc.firstChildOfType(InvocationArguments.class).previousSibling().getImage();
        usedMethodNames.add(lhs.substring(lhs.lastIndexOf('.')+1));
        recurse(mc);
    }

    void visit(MethodReference mr) {
        usedMethodNames.add(mr.firstChildOfType(Identifier.class).toString());
        recurse(mr);
    }

    void visit(Name name) {
        if (name.size() > 1 || !(name.getParent() instanceof MethodCall)) {
            usedVarNames.add(name.get(0).toString());
            usedTypeNames.add(name.get(0).toString());
        }
    }

    void visit(DotName dotName) {
        usedVarNames.add(dotName.getLastChild().toString());
        recurse(dotName);
    } 

    void visit(VariableDeclarator vd) {
        if (!isPrivate(vd.getParent()) || usedVarNames.contains(vd.getName())) {
            recurse(vd);
        }
    }

    void visit(TypeDeclaration td) {
        if (!isPrivate(td) || usedTypeNames.contains(td.getName())) {
            recurse(td);
        }
    }

    void visit(ImportDeclaration decl) {}
    
    void visit(PackageDeclaration decl) {}

    void visit(FieldDeclaration fd) {
        if (isUsed(fd)) {
            recurse(fd);
        }
    }

    private boolean isUsed(FieldDeclaration fd) {
        if (!isPrivate(fd)) return true;
        for (VariableDeclarator vd : fd.childrenOfType(VariableDeclarator.class)) {
            if (usedVarNames.contains(vd.getName())) return true;
        }
        return false;
    }



    // Get rid of any variable declarations where the variable name
    // is not in usedVarNames. The only complicated case is if the field
    // has more than one variable declaration comma-separated
    private void stripUnusedVars(FieldDeclaration fd) {
        Set<Node> toBeRemoved = new HashSet<>();
        for (VariableDeclarator vd : fd.childrenOfType(VariableDeclarator.class)) {
            if (!usedVarNames.contains(vd.getName())) {
                toBeRemoved.add(vd);
                Node prev = vd.previousSibling();
                Node next = vd.nextSibling();
                if (prev.getType()==COMMA) {
                    toBeRemoved.add(prev);
                }
                else if (next.getType() == COMMA) {
                    toBeRemoved.add(next);
                }
            }
        }
        for (Node n : toBeRemoved) {
            fd.remove(n);
        }
        if (fd.firstChildOfType(VariableDeclarator.class) == null) {
            fd.getParent().remove(fd);
        }
    }
}