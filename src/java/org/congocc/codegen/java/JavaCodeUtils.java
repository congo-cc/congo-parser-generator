package org.congocc.codegen.java;

import java.util.*;
import org.congocc.parser.*;
import org.congocc.parser.Token.TokenType;
import static org.congocc.parser.Token.TokenType.*;
import org.congocc.parser.tree.*;

public class JavaCodeUtils {

    // Just a holder for static methods.
    private JavaCodeUtils() {
    }

    /**
     * Adds getter/setter methods if a field is annotated with a "@Property" annotation
     */
    static public void addGetterSetters(Node root) {
        List<FieldDeclaration> fds = root.descendants(FieldDeclaration.class);
        for (FieldDeclaration fd : fds) {
            //List<Annotation> annotations  = fd.childrenOfType(Annotation.class);
            for (Annotation annotation : getAnnotations(fd)) {
                if (annotation.getName().equals("Property")) {
                    addGetterSetter(fd);
                    annotation.getParent().remove(annotation);
                }
            }
        }
    }

    static private List<Annotation> getAnnotations(Node node) {
        List<Annotation> result = new ArrayList<>(node.childrenOfType(Annotation.class));
        Modifiers mods = node.firstChildOfType(Modifiers.class);
        if (mods != null) {
            result.addAll(mods.childrenOfType(Annotation.class));
        }
        return result;
    }

    static private void addGetterSetter(FieldDeclaration fd) {
        Node context = fd.getParent();
        int index = context.indexOf(fd);
        String fieldType = fd.firstChildOfType(Type.class).toString();
        for (Identifier id : fd.getVariableIds()) {
            ensurePrivate(fd);
            insertGetterSetter(context, fieldType, id.toString(), index);
        }
    }

    static private void ensurePrivate(FieldDeclaration fd) {
        List<Token> tokens = fd.childrenOfType(Token.class);
        Modifiers mods = fd.firstChildOfType(Modifiers.class);
        if (mods != null) {
            tokens.addAll(mods.childrenOfType(Token.class));
        }
        for (Token tok : tokens) {
            TokenType type = tok.getType();
            if (type == PRIVATE) {
                return; // Nothing to do!
            }
            else if (type == PROTECTED || type == PUBLIC) {
                tok.getParent().remove(tok);
                break;
            }
        }
        Type type = fd.firstChildOfType(Type.class);
        Token privateToken = Token.newToken(PRIVATE, "private", fd.getTokenSource());
        if (mods !=null) mods.add(privateToken);
        else fd.add(fd.indexOf(type), privateToken);
    }

    static private void insertGetterSetter(Node context, String fieldType, String fieldName, int index) {
        String getterMethodName = "get" + capitalizeFirstLetter((fieldName));
        String setterMethodName = getterMethodName.replaceFirst("g", "s");
        if (fieldType.equals("boolean")) {
            getterMethodName = getterMethodName.replaceFirst("get", "is");
        }
        String getter = "//Inserted getter for " + fieldName 
                        +"\npublic " + fieldType + " " + getterMethodName 
                        + "() {return " + fieldName + ";}";
        String setter = "//Inserted setter for " + fieldName 
                        + "\npublic void " + setterMethodName 
                        + "(" + fieldType + " " +  fieldName + ") {this." + fieldName + " = " + fieldName + ";}";
        MethodDeclaration getterMethod, setterMethod;
        getterMethod = new CongoCCParser(getter).MethodDeclaration();
        setterMethod = new CongoCCParser(setter).MethodDeclaration();
        context.add(index +1, setterMethod);
        context.add(index +1, getterMethod);
    }

    static public void removeWrongJDKElements(Node context, int target) {
        List<Annotation> annotations = context.descendants(Annotation.class, 
            a->a.getName().toLowerCase().startsWith("minjdk") || a.getName().toLowerCase().startsWith("maxjdk"));
        for (Annotation annotation : annotations) {
            boolean specifiesMax = annotation.getName().toLowerCase().startsWith("max");
            String intPart = annotation.getName().substring(6);
            int specifiedVersion;
            try {
                specifiedVersion = Integer.parseInt(intPart);
            }
            catch (NumberFormatException nfe) {
                //okay, do nothing here. Just leave the annotation there and let the 
                // Java compiler deal with the fact that it is wrong!
                continue;
            }
            boolean removeElement = specifiesMax ? target > specifiedVersion : target < specifiedVersion;
            Node parent = annotation.getParent();
            parent.remove(annotation);
            if (parent instanceof Modifiers) {
                parent = parent.getParent();
            }
            Node grandparent = parent.getParent();
            if (removeElement) {
                grandparent.remove(parent);
            } 
        }
    }

    /**
     * Uses the DeadCodeEliminator visitor class to get rid of
     * unused private methods and fields
     */
    static public void stripUnused(CompilationUnit jcu) {
        new Reaper(jcu).stripUnused();
    }

    static private String capitalizeFirstLetter(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}