package org.congocc.output.java;

import java.util.*;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;
import static org.congocc.parser.TokenType.*;

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
    private Set<String> usedMethodNames = new HashSet<>();
    private Set<String> usedTypeNames = new HashSet<>();
    private Set<String> usedVarNames = new HashSet<>();
    private CompilationUnit jcu;

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
            md.getParent().removeChild(md);
        }
        // We go through all the private FieldDeclarations and get rid of any variables that
        // are not in usedVarNames
        for (FieldDeclaration fd : jcu.descendants(FieldDeclaration.class, fd->isPrivate(fd))) {
            stripUnusedVars(fd);
        }

        for (TypeDeclaration td : jcu.descendants(TypeDeclaration.class, td->isPrivate(td))) {
            if (!usedTypeNames.contains(td.getName())) {
                td.getParent().removeChild(td);
            }
        }

        // Now get rid of unused imports.
        for (ImportDeclaration imp : jcu.childrenOfType(ImportDeclaration.class)) {
            if (imp.firstChildOfType(STAR) == null) {
                List<Identifier> names = imp.descendantsOfType(Identifier.class);
                String name = names.get(names.size()-1).getImage();
                // Note that a static import can import methods.
                if (imp.firstChildOfType(STATIC) != null && usedMethodNames.contains(name)) continue;
                if (!usedTypeNames.contains(name)) {
                    jcu.removeChild(imp);
                }
            }
        }
    }

    private boolean isPrivate(Node node) {
        if (node.firstChildOfType(PRIVATE) != null) return true;
        Modifiers mods = node.firstChildOfType(Modifiers.class);
        return mods == null ? false : mods.firstChildOfType(PRIVATE) != null;
    }

    void visit(MethodDeclaration md) {
        if (!isPrivate(md) || usedMethodNames.contains(md.getName())) {
            recurse(md);
        }
    }

    void visit(ObjectType ot) {
        String firstID = ot.firstDescendantOfType(Identifier.class).getImage();
        usedTypeNames.add(firstID);
    }

    void visit(Identifier id) {
        if (id.getNext().getType() == LPAREN || id.getParent() instanceof MethodReference) {
            usedMethodNames.add(id.getImage());
        }
        else {
            usedVarNames.add(id.getImage());
            if (id.getNext().getType() == DOT || id.firstAncestorOfType(ThrowsList.class) != null) {
                usedTypeNames.add(id.getImage());
            }
        }
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

    // Get rid of any variable declarations where the variable name
    // is not in usedNames. The only complicated case is if the field
    // has more than one variable declaration comma-separated
    private void stripUnusedVars(FieldDeclaration fd) {
        Set<Node> toBeRemoved = new HashSet<Node>();
        for (VariableDeclarator vd : fd.childrenOfType(VariableDeclarator.class)) {
            if (!usedVarNames.contains(vd.getName())) {
                toBeRemoved.add(vd);
                Node prev = vd.previousSibling();
                Node next = vd.nextSibling();
                if (prev.getTokenType()==COMMA) {
                    toBeRemoved.add(prev);
                }
                else if (next.getTokenType() == COMMA) {
                    toBeRemoved.add(next);
                }
            }
        }
        for (Node n : toBeRemoved) {
            fd.removeChild(n);
        }
        if (fd.firstChildOfType(VariableDeclarator.class) == null) {
            fd.getParent().removeChild(fd);
        }
    }
}