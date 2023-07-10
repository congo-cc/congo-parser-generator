package org.congocc.codegen.java;

import java.util.*;

import org.congocc.app.AppSettings;
import org.congocc.core.Grammar;
import org.congocc.parser.*;
import org.congocc.parser.tree.*;

/**
 * Class to hold the code that comes from the grammar file
 * and is later "injected" into the output source files 
 */
public class CodeInjector {
    
    private final Map<String, TypeDeclaration> types = new HashMap<>();  // Not presently queried ...
    private final Map<String, Set<ImportDeclaration>> injectedImportsMap = new HashMap<>();
    private final Map<String, Set<Annotation>> injectedAnnotationsMap = new HashMap<>();
    private final Map<String, List<ObjectType>> extendsLists = new HashMap<>();
    private final Map<String, List<ObjectType>> implementsLists = new HashMap<>();
    private final Map<String, TypeParameters> typeParameterLists = new HashMap<>();
    private final Map<String, List<ClassOrInterfaceBodyDeclaration>> bodyDeclarations = new HashMap<>();
    private final Set<String> overriddenMethods = new LinkedHashSet<>();  // Not presently queried ...
    private final Set<String> typeNames = new LinkedHashSet<>();
    private final Set<String> interfaces = new LinkedHashSet<>();  // Not presently queried ...
    private final Set<String> finalClasses = new LinkedHashSet<>();  // Not presently queried ...
    private final Grammar grammar;
    private AppSettings appSettings;
    
    public CodeInjector(Grammar grammar, List<Node> codeInjections) {
        this.grammar = grammar;
        this.appSettings = grammar.getAppSettings();
        for (Node n : codeInjections) {
            if (n instanceof CompilationUnit) {
                inject((CompilationUnit) n);
            } else if (n instanceof CodeInjection) {
                inject((CodeInjection) n);
            } 
        } 
    }
    
    private boolean isInNodePackage(String classname) {
        return !classname.equals(appSettings.getParserClassName())
             && !classname.equals(appSettings.getLexerClassName())
             //&& !classname.equals(baseNodeClassName)
             && !classname.equals("ParseException")
             && !classname.equals("TokenSource")
             && !classname.equals("NonTerminalCall")
             && !classname.equals(appSettings.getBaseTokenClassName())
             && !classname.equals("InvalidToken")
             && !classname.equals("Node");
    }

    public boolean isFinal(String classname) {
        return finalClasses.contains(classname);
    }
    
    private void inject(CompilationUnit jcu) {
        List<ImportDeclaration> importDecls = new ArrayList<>(jcu.getImportDeclarations());
        for (TypeDeclaration dec : jcu.getTypeDeclarations()) {
            String name = dec.getName();
            typeNames.add(name);
            String packageName = isInNodePackage(name) ? appSettings.getNodePackage() : appSettings.getParserPackage();
            if (packageName.length() > 0) {
                name = packageName + "." + name;
            }
            types.put(name, dec);
            if (dec instanceof InterfaceDeclaration) {
                interfaces.add(name);
            }
            if (!importDecls.isEmpty()) {
                Set<ImportDeclaration> injectedImports = injectedImportsMap.computeIfAbsent(name, k -> new LinkedHashSet<>());
                injectedImports.addAll(importDecls);
            }
            List<ObjectType> extendsList = dec.getExtendsList() == null ? new ArrayList<>() : dec.getExtendsList().getTypes();
            List<ObjectType> existingOne = extendsLists.get(name);
            if (existingOne == null) {
                extendsLists.put(name, extendsList);
            } else {
                existingOne.addAll(extendsList);
            }
            List<ObjectType> implementsList = dec.getImplementsList() == null ? new ArrayList<>() : dec.getImplementsList().getTypes();
            List<ObjectType> existing = implementsLists.get(name);
            if (existing == null) {
                implementsLists.put(name, implementsList);
            } else {
                existing.addAll(implementsList);
            }
            TypeParameters typeParameters = dec.getTypeParameters();
            if (typeParameters != null) {
                TypeParameters injectedList = typeParameterLists.get(name);
                if (injectedList == null) {
                    typeParameterLists.put(name, typeParameters);
                } else {
                    injectedList.add(typeParameters);
                }
            }
            List<ClassOrInterfaceBodyDeclaration> injectedCode = new ArrayList<>();
            for (Iterator<Node> it = dec.getBody().iterator(); it.hasNext();) {
                Node n = it.next();
                if (n instanceof ClassOrInterfaceBodyDeclaration) {
                    injectedCode.add((ClassOrInterfaceBodyDeclaration)n);
                }
            }
            List<ClassOrInterfaceBodyDeclaration> existingCode = bodyDeclarations.get(name);
            if (existingCode == null) {
                bodyDeclarations.put(name, injectedCode);
            } else {
                existingCode.addAll(injectedCode);
            }
            for (ClassOrInterfaceBodyDeclaration decl : injectedCode) {
                String key = null;
                if (decl instanceof MethodDeclaration) {
                   key = ((MethodDeclaration) decl).getFullSignature();
                }
                if (key != null) {
                    overriddenMethods.add(key);
                }
            }
        }
    }

    private void addToDependencies(String name, List<ObjectType> listToAdd, Map<String, List<ObjectType>> mapOfExistingLists) {
        List<ObjectType> existingList = mapOfExistingLists.get(name);
        if (existingList == null) {
            mapOfExistingLists.put(name, listToAdd);
        } else {
            for (ObjectType ot : listToAdd) {
                // Don't add duplicates. Maybe it should be a set rather than a list,
                // but order sensitivity might apply
                if (!existingList.contains(ot)) {
                    existingList.add(ot);
                }
            }
        }
    }

    void inject(CodeInjection injection) 
    {
        String name = injection.getName();
        Modifiers mods = injection.firstChildOfType(Modifiers.class);
        typeNames.add(name);
        if (injection.isInterface) {
            assert !injection.isMarkedFinal();
            interfaces.add(name);
        }
        if (injection.isMarkedFinal()) {
            finalClasses.add(name);
        }
        String packageName = isInNodePackage(name) ? appSettings.getNodePackage() : appSettings.getParserPackage();
        if (packageName.length() >0) {
            name = packageName + "." + name;
        }
        List<ImportDeclaration> importDeclarations = injection.childrenOfType(ImportDeclaration.class);
        if (importDeclarations !=null && !importDeclarations.isEmpty()) {
            Set<ImportDeclaration> existingImports = injectedImportsMap.computeIfAbsent(name, k -> new LinkedHashSet<>());
            existingImports.addAll(importDeclarations);
        }
        List<Annotation> annotations = new ArrayList<>();
        if (mods != null){
            annotations.addAll(mods.childrenOfType(Annotation.class));
        }
        annotations.addAll(injection.childrenOfType(Annotation.class));
        if (!annotations.isEmpty()) {
            Set<Annotation> existingAnnotations = injectedAnnotationsMap.computeIfAbsent(name, k -> new LinkedHashSet<>());
            existingAnnotations.addAll(annotations);
        }
        if (injection.extendsList != null) {
            addToDependencies(name, injection.extendsList, extendsLists);
        }
        if (injection.implementsList != null) {
            addToDependencies(name, injection.implementsList, implementsLists);
        }
        List<ClassOrInterfaceBodyDeclaration> existingDecls = bodyDeclarations.computeIfAbsent(name, k -> new ArrayList<>());
        if (injection.body != null) {
        	existingDecls.addAll(injection.body.childrenOfType(ClassOrInterfaceBodyDeclaration.class));
        }
    }    

    public void injectCode(CompilationUnit jcu) {
        String packageName = jcu.getPackageName();
        Set<ImportDeclaration> allInjectedImports = new LinkedHashSet<>();
        for (TypeDeclaration typeDecl : jcu.getTypeDeclarations()) {
            String fullName = typeDecl.getName();
            if (packageName !=null) {
                fullName = packageName + "." + fullName;
            }
            Set<ImportDeclaration> injectedImports = injectedImportsMap.get(fullName);
            if (injectedImports != null) {
                allInjectedImports.addAll(injectedImports);
            }
            List<ObjectType> injectedExtends = extendsLists.get(fullName);
            if (injectedExtends != null) {
                for (ObjectType type : injectedExtends) {
                    typeDecl.addExtends(type);
                }
            }
            List<ObjectType> injectedImplements = implementsLists.get(fullName);
            if (injectedImplements != null) {
                for (ObjectType type : injectedImplements) {
                    typeDecl.addImplements(type);
                }
            }
            TypeParameters injectedTypeParameters = typeParameterLists.get(fullName);
            if (injectedTypeParameters != null) {
                TypeParameters typeParameters = typeDecl.getTypeParameters();
                typeParameters.add(injectedTypeParameters);
            }
            Set<Annotation> annotations = this.injectedAnnotationsMap.get(fullName);
            if (annotations != null) {
            	typeDecl.addAnnotations(annotations);
            }
            List<ClassOrInterfaceBodyDeclaration> injectedCode = bodyDeclarations.get(fullName);
            if (injectedCode != null) {
                typeDecl.addElements(injectedCode);
            }
        }
        injectImportDeclarations(jcu, allInjectedImports);
    }
    
    private void injectImportDeclarations(CompilationUnit jcu, Collection<ImportDeclaration> importDecls) {
        List<ImportDeclaration> importDeclarations = jcu.getImportDeclarations();
        for (ImportDeclaration importDecl : importDecls) {
            if (!importDeclarations.contains(importDecl)) {
                jcu.addImportDeclaration(importDecl);
            }
        }
    }
    
    public boolean hasInjectedCode(String typename) {
        return typeNames.contains(typename);
    }

    /*
     * Helper methods
     */

    public List<ObjectType> getExtendsList(String qualifiedName) {
        return extendsLists.get(qualifiedName);
    }

    public List<ObjectType> getImplementsList(String qualifiedName) {
        return implementsLists.get(qualifiedName);
    }

    public Set<ImportDeclaration> getImportDeclarations(String qualifiedName) {
        return injectedImportsMap.get(qualifiedName);
    }

    public Map<String, List<ClassOrInterfaceBodyDeclaration>> getBodyDeclarations() {
        return bodyDeclarations;
    }

    public List<ClassOrInterfaceBodyDeclaration> getBodyDeclarations(String qualifiedName) {
        return bodyDeclarations.get(qualifiedName);
    }

    public List<String> getParentClasses(String qualifiedName) {
        List<String> result = new ArrayList<>();
        List<ObjectType> extendsList = getExtendsList(qualifiedName);
        List<ObjectType> implementsList = getImplementsList(qualifiedName);
        String name = qualifiedName.substring(qualifiedName.lastIndexOf('.')+1);
        if (extendsList.isEmpty() && implementsList.isEmpty()) {
            if (grammar.nodeIsInterface(name)) {
                result.add("Node");
            }
            else {
                result.add(appSettings.getBaseNodeClassName());
                result.add("Node");
            }
        }
        else {
            if (extendsList.isEmpty()) {
                result.add(appSettings.getBaseNodeClassName());
            }
            else {
                for (ObjectType ot : extendsList) {
                    result.add(ot.toString());
                }
            }
            for (ObjectType ot : implementsList) {
                result.add(ot.toString());
            }
        }
        return result;
    }

    public Map<String, TypeParameters> getTypeParameterLists() {
        return typeParameterLists;
    }
}
