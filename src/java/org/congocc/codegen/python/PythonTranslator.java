package org.congocc.codegen.python;

import java.util.*;

import org.congocc.core.Grammar;
import org.congocc.codegen.Translator;
import org.congocc.codegen.java.CodeInjector;
import org.congocc.parser.CongoCCParser;
import org.congocc.parser.Node;
import org.congocc.parser.ParseException;
import org.congocc.parser.tree.*;

public class PythonTranslator extends Translator {
    public PythonTranslator(Grammar grammar) {
        super(grammar);
        methodIndent = 4;
        fieldIndent = 8;
        includeInitializers = true;
    }

    public String translateOperator(String operator) {
        String result = operator;

        switch (result) {
            case "||":
                result = "or";
                break;
            case "&&":
                result = "and";
                break;
            case "!":
                result = "not";
                break;
        }
        return result;
    }

    private static final Set<String> specialPrefixes = new HashSet<>();

    private static boolean isSpecialPrefix(String ident) {
        boolean result = false;

        for (String p : specialPrefixes) {
            if (ident.startsWith(p)) {
                result = true;
                break;
            }
        }
        return result;
    }

    @Override public String translateIdentifier(String ident, TranslationContext kind) {
        String result = ident;

        if (specialPrefixes.isEmpty()) {
            specialPrefixes.add(appSettings.generateIdentifierPrefix("tokenHook"));
        }
        if (ident.equals("null")) {
            result = "None";
        }
        else if (ident.equals("true")) {
            result = "True";
        }
        else if (ident.equals("false")) {
            result = "False";
        }
        else if (ident.equals("this")) {
            result = "self";
        }
        else if (ident.equals("currentLookaheadToken") ||
                 ident.equals("lastConsumedToken")) {
            result = String.format("self.%s", camelToSnake(ident));
        }
        else if (ident.equals("toString")) {
            result = "__str__";
        }
        else if (ident.startsWith(appSettings.getNodePackage().concat("."))) {
            int prefixLength = appSettings.getNodePackage().length() + 1;
            result = ident.substring(prefixLength);
        }
        else if (Character.isLowerCase(ident.charAt(0)) && !isSpecialPrefix(ident)) {
            result = camelToSnake(result);
        }
        else if (ident.equals("LEXER_CLASS") || (ident.equals(appSettings.getLexerClassName()))) {
            result = "Lexer";
        }
        else if (ident.equals("PARSER_CLASS") || (ident.equals(appSettings.getParserClassName()))) {
            result = "Parser";
        }
        else if (ident.equals("THIS_PRODUCTION")) {
            result = "this_production";
        }
        else if (ident.equals("BASE_TOKEN_CLASS") || (ident.equals(appSettings.getBaseTokenClassName()))) {
            result = "Token";
        }
        else if (ident.startsWith("NODE_PACKAGE.")) {
            result = ident.substring(13);
        }
        else if (ident.equals("DirectiveLine")) {
            // TODO fix this special-casing for the current C# grammar
            result = "parse_DirectiveLine";
        }
        return result;
    }

    public String translateGetter(String getterName) {
        if (getterName.startsWith("is")) {
            return translateIdentifier(getterName, TranslationContext.METHOD);
        }
        else if (!getterName.startsWith("get")) {
            return translateIdentifier(getterName, TranslationContext.METHOD);
        }
        String result = Character.toLowerCase(getterName.charAt(3)) +
                getterName.substring(4);
        return translateIdentifier(result, TranslationContext.METHOD);
    }

    @Override
    protected boolean needsParentheses(ASTExpression expr) {
        boolean result = true;

        if (expr instanceof ASTPrimaryExpression ||
                expr instanceof ASTInstanceofExpression ||
                expr instanceof ASTUnaryExpression) {
            result = false;
        }
        else if (expr instanceof ASTBinaryExpression) {
            String op = ((ASTBinaryExpression) expr).getOp();
            if (op.equals(".") || op.equals("=") || (op.endsWith("=") && ("+-*/|&".indexOf(op.charAt(0)) >= 0))) {
                result = false;
            }
            // Operator precedence might be different, so generally prefer to parenthesize
            // else {
            //     result = (expr.getParent() != null);
            // }
        }
        return result;
    }

    private boolean shouldAddSelf(ASTPrimaryExpression expr) {
        boolean result = true;
        Node parent = expr.getParent();

        if (parent != null) {
            if (parent instanceof ASTBinaryExpression) {
                ASTBinaryExpression be = (ASTBinaryExpression) parent;

                if (be.getOp().equals(".") && (expr == be.getRhs())) {
                    result = false;
                }
            }
        }
        return result;
    }

    @Override protected void translatePrimaryExpression(ASTPrimaryExpression expr, TranslationContext ctx, StringBuilder result) {
        String s = expr.getLiteral();
        String n = expr.getName();
        boolean isName = false;

        if (s == null) {
            s = translateIdentifier(n, TranslationContext.VARIABLE);
            isName = true;
        }
        else {
            switch (s) {
                case "null":
                    s = "None";
                    break;
                case "true":
                    s = "True";
                    break;
                case "false":
                    s = "False";
                    break;
                case "this":
                    s = "self";
                    break;
            }
        }
        if (isName && !isParameterName(n) && (findSymbol(n) == null) && fields.containsKey(n)) {  // must be a field, then
            boolean addSelf = shouldAddSelf(expr);

            if (addSelf) {
                result.append("self.");
            }
            if (properties.containsKey(n)) {
                result.append('_');
            }
        }
        result.append(s);
    }

    @Override protected void translateUnaryExpression(ASTUnaryExpression expr, TranslationContext ctx, StringBuilder result) {
        String xop = translateOperator(expr.getOp());
        boolean parens = needsParentheses(expr);

        if (xop.equals("++") || xop.equals("--")) {
            internalTranslateExpression(expr.getOperand(), TranslationContext.UNKNOWN, result);
            result.append(' ');
            result.append(xop.charAt(0));
            result.append("= 1");
        }
        else {
            if (parens) {
                result.append('(');
            }
            result.append(xop);
            if (xop.equals("not")) {
                result.append(' ');
            }
            internalTranslateExpression(expr.getOperand(), TranslationContext.UNKNOWN, result);
            if (parens) {
                result.append(')');
            }
        }
    }

    @Override protected void translateBinaryExpression(ASTBinaryExpression expr, StringBuilder result) {
        String xop = translateOperator(expr.getOp());
        boolean parens = needsParentheses(expr);
        ASTExpression lhs = expr.getLhs();
        ASTExpression rhs = expr.getRhs();

        if (isNull(rhs)) {
            if (xop.equals("==")) {
                xop = "is";
            }
            else if (xop.equals("!=")) {
                xop = "is not";
            }
        }
        processBinaryExpression(parens, lhs, xop, rhs, result);
    }

    @Override protected void translateInstanceofExpression(ASTInstanceofExpression expr, StringBuilder result) {
        result.append("isinstance(");
        internalTranslateExpression(expr.getInstance(), TranslationContext.UNKNOWN, result);
        result.append(", ");
        internalTranslateExpression(expr.getTypeExpression(), TranslationContext.UNKNOWN, result);
        result.append(')');
    }

    @Override protected void translateArrayAccess(ASTArrayAccess expr, StringBuilder result) {
        internalTranslateExpression(expr.getArray(), TranslationContext.UNKNOWN, result);
        result.append('[');
        internalTranslateExpression(expr.getIndex(), TranslationContext.UNKNOWN, result);
        result.append(']');
    }

    @SuppressWarnings("DuplicatedCode")
    @Override protected void translateTernaryExpression(ASTTernaryExpression expr, StringBuilder result) {
        boolean parens = needsParentheses(expr);
        ASTExpression condition = expr.getCondition();
        ASTExpression trueValue = expr.getTrueValue();
        ASTExpression falseValue = expr.getFalseValue();

        if (parens) {
            result.append('(');
        }
        internalTranslateExpression(trueValue, TranslationContext.UNKNOWN, result);
        result.append(" if ");
        internalTranslateExpression(condition, TranslationContext.UNKNOWN, result);
        result.append(" else ");
        internalTranslateExpression(falseValue, TranslationContext.UNKNOWN, result);
        if (parens) {
            result.append(')');
        }
    }

    void renderReceiver(ASTExpression expr, StringBuilder result) {
        if (expr instanceof ASTBinaryExpression) {
            internalTranslateExpression(((ASTBinaryExpression) expr).getLhs(), TranslationContext.UNKNOWN, result);
        }
        else if (expr instanceof ASTPrimaryExpression) {
            result.append("self");
        }
        else {
            String s = String.format("Cannot render receiver %s", getSimpleName(expr));
            throw new UnsupportedOperationException(s);
        }
    }

    protected static final Set<String> leaveAsMethods = new HashSet<>(Arrays.asList("getIndents", "isConstant"));

    protected boolean treatAsProperty(String methodName) {
        return isGetter(methodName) || methodName.equals("previousCachedToken");
    }

    @Override protected void translateInvocation(ASTInvocation expr, StringBuilder result) {
        String methodName = expr.getMethodName();
        int nargs = expr.getArgCount();
        ASTExpression receiver = expr.getReceiver();
        ASTExpression firstArg = (nargs != 1) ? null : expr.getArguments().get(0);

        if (methodName.equals("equals") && (nargs == 1)) {
            renderReceiver(receiver, result);
            result.append(" == ");
            internalTranslateExpression(firstArg, TranslationContext.UNKNOWN, result);
        }
        else if ((methodName.equals("contains") || methodName.equals("containsKey")) && (nargs == 1)) {
            internalTranslateExpression(firstArg, TranslationContext.UNKNOWN, result);
            result.append(" in ");
            renderReceiver(receiver, result);
        }
        else if (methodName.equals("get") && (nargs == 1)) {
            renderReceiver(receiver, result);
            result.append('[');
            internalTranslateExpression(firstArg, TranslationContext.UNKNOWN, result);
            result.append(']');
        }
        else if (methodName.equals("toString") && (nargs == 0)) {
            result.append("str(");
            renderReceiver(receiver, result);
            result.append(')');
        }
        else if (methodName.equals("getClass") && (nargs == 0)) {
            result.append("type(");
            renderReceiver(receiver, result);
            result.append(')');
        }
        else if (methodName.equals("getSimpleName") && (nargs == 0) && belongsToClass(expr)) {
            renderReceiver(receiver, result);
            result.append(".__name__");
        }
        else if ((methodName.equals("size") || methodName.equals("length")) && (nargs == 0)) {
            result.append("len(");
            renderReceiver(receiver, result);
            result.append(')');
        }
        else if (methodName.equals("charAt") && (nargs == 1)) {
            renderReceiver(receiver, result);
            result.append('[');
            internalTranslateExpression(firstArg, TranslationContext.PARAMETER, result);
            result.append(']');
        }
        else if (methodName.equals("isParserTolerant") && (nargs == 0)) {
            renderReceiver(receiver, result);
            result.append(".is_tolerant");
        }
        else if ((nargs == 0) && treatAsProperty(methodName) && !leaveAsMethods.contains(methodName)) {
            // treat as a property.
            renderReceiver(receiver, result);
            result.append('.');
            result.append(translateGetter(methodName));
        }
        else if (methodName.equals("nodeArity") && (nargs == 0)) {
            renderReceiver(receiver, result);
            result.append(".node_arity");
        }
//        else if (methodName.equals("isUnparsed") && (nargs == 0)) {
//            renderReceiver(receiver, result);
//            result.append(".is_unparsed");
//        }
        else if (methodName.equals("setUnparsed") && (nargs == 1)) {
            renderReceiver(receiver, result);
            result.append(".is_unparsed = ");
            internalTranslateExpression(firstArg, TranslationContext.UNKNOWN, result);
        }
        else if (methodName.equals("of") && isEnumSet(receiver)) {
            result.append("_Set({");
            if (nargs > 0) {
                translateArguments(expr.getArguments(), false, result);
            }
            result.append("})");
        }
        else if (isSetter(methodName) && (nargs == 1)) {
            String s = translateIdentifier(methodName, TranslationContext.METHOD);
            renderReceiver(receiver, result);
            result.append('.');
            result.append(s.substring(4));
            result.append(" = ");
            internalTranslateExpression(firstArg, TranslationContext.UNKNOWN, result);
        }
        else if (expr instanceof ASTAllocation) {
            if (isList(receiver)) {
                result.append("_List");
                translateArguments(expr.getArguments(), true, result);
            }
            else {
                internalTranslateExpression(receiver, TranslationContext.UNKNOWN, result);
                translateArguments(expr.getArguments(), true, result);
            }
        }
        else {
            if (!methodName.equals("newToken")) {
                renderReceiver(receiver, result);
                result.append('.');
            }
            String ident;
            if ((nargs == 1) && (methodName.equals("startsWith") || methodName.equals("endsWith"))) {
                ident = methodName.toLowerCase();
            }
            else {
                ident = translateIdentifier(methodName, TranslationContext.METHOD);
            }
            result.append(ident);
            translateArguments(expr.getArguments(), true, result);
        }
    }

    private boolean shouldIndent(ASTStatement stmt) {
        boolean result = true;

        if (stmt instanceof ASTStatementList) {
            result = ((ASTStatementList) stmt).getStatements() == null;
        }
        else if (stmt instanceof ASTVariableOrFieldDeclaration) {
            ASTVariableOrFieldDeclaration d = (ASTVariableOrFieldDeclaration) stmt;
            result = d.isField() || d.hasInitializer();
        }
        return result;
    }

    protected void translateIf(ASTIfStatement stmt, int indent, boolean first, StringBuilder result) {
        if (first) {
            result.append("if ");
        }
        else {
            addIndent(indent, result);
            result.append("elif ");
        }
        internalTranslateExpression(stmt.getCondition(), TranslationContext.UNKNOWN, result);
        result.append(":\n");
        internalTranslateStatement(stmt.getThenStmts(), indent + 4, result);
        ASTStatement estmt = stmt.getElseStmts();
        if (estmt != null) {
            if (!(estmt instanceof ASTIfStatement)) {
                addIndent(indent, result);
                result.append("else:\n");
                internalTranslateStatement(estmt, indent + 4, result);
            }
            else {
                translateIf((ASTIfStatement) estmt, indent, false, result);
            }
        }
    }

    @Override protected void internalTranslateStatement(ASTStatement stmt, int indent, StringBuilder result) {
        boolean addNewline = false;
        boolean doIndent = shouldIndent(stmt);

        if (doIndent) {
            addIndent(indent, result);
        }
        if (stmt instanceof ASTExpressionStatement) {
            internalTranslateExpression(((ASTExpressionStatement) stmt).getValue(), TranslationContext.UNKNOWN, result);
            addNewline = true;
        }
        else if (stmt instanceof ASTStatementList) {
            List<ASTStatement> statements = ((ASTStatementList) stmt).getStatements();

            if (statements == null) {   // empty block
                result.append("pass\n");
            }
            else {
                for (ASTStatement s : statements) {
                    internalTranslateStatement(s, indent, result);
                }
            }
        }
        else if (stmt instanceof ASTVariableOrFieldDeclaration) {
            ASTVariableOrFieldDeclaration vd = (ASTVariableOrFieldDeclaration) stmt;
            List<ASTPrimaryExpression> names = vd.getNames();
            List<ASTExpression> initializers = vd.getInitializers();
            ASTTypeExpression type = vd.getTypeExpression();
            int n = names.size();
            String defaultInitializer = "None";
            boolean isProperty = vd.hasAnnotation("Property");
            boolean isField = vd.isField();

            if (isProperty || isField) {
                if (type.isNumeric()) {
                    defaultInitializer = "0";
                }
            }
            for (int i = 0; i < n; i++) {
                ASTPrimaryExpression name = names.get(i);
                ASTExpression initializer = initializers.get(i);

                processVariableDeclaration(type, name, isField, isProperty);

                if (isField || (initializer != null)) {
                    internalTranslateExpression(name, TranslationContext.UNKNOWN, result);
                    result.append(" = ");
                    if (initializer == null) {
                        result.append(defaultInitializer);
                    }
                    else {
                        internalTranslateExpression(initializer, TranslationContext.UNKNOWN, result);
                    }
                    if (i < (n - 1)) {
                        result.append("; ");
                    }
                    addNewline = true;
                }
            }
        }
        else if (stmt instanceof ASTReturnStatement) {
            result.append("return");
            ASTExpression value = ((ASTReturnStatement) stmt).getValue();
            if (value != null) {
                result.append(' ');
                internalTranslateExpression(value, TranslationContext.UNKNOWN, result);
            }
            addNewline = true;
        }
        else if (stmt instanceof ASTBreakOrContinueStatement) {
            String s = ((ASTBreakOrContinueStatement) stmt).isBreak() ? "break" : "continue";
            result.append(s);
            addNewline = true;
        }
        else if (stmt instanceof ASTIfStatement) {
            translateIf((ASTIfStatement) stmt, indent, true, result);
        }
        else if (stmt instanceof ASTWhileStatement) {
            ASTWhileStatement s = (ASTWhileStatement) stmt;

            result.append("while ");
            internalTranslateExpression(s.getCondition(), TranslationContext.UNKNOWN, result);
            result.append(":\n");
            internalTranslateStatement(s.getStatements(), indent + 4, result);
        }
        else if (stmt instanceof ASTAssertStatement) {
            ASTAssertStatement s = (ASTAssertStatement) stmt;
            result.append("if not (");
            internalTranslateExpression(s.getCondition(), TranslationContext.UNKNOWN, result);
            result.append("):\n");
            addIndent(indent + 4, result);
            ASTExpression m = s.getMessage();
            if (m == null) {
                result.append("raise AssertionError()\n");
            }
            else {
                result.append("raise AssertionError(str(");
                internalTranslateExpression(m, TranslationContext.UNKNOWN, result);
                result.append("))\n");
            }
        }
        else if (stmt instanceof ASTForStatement) {
            ASTForStatement s = (ASTForStatement) stmt;
            ASTExpression iterable;
            ASTVariableOrFieldDeclaration decl = s.getVariable();

            if ((iterable = s.getIterable()) != null) {
                // iterating for
                ASTVariableOrFieldDeclaration vd = s.getVariable();
                result.append("for ");
                internalTranslateExpression(vd.getNames().get(0), TranslationContext.UNKNOWN, result);
                result.append(" in ");
                internalTranslateExpression(iterable, TranslationContext.UNKNOWN, result);
                result.append(":\n");
                internalTranslateStatement(s.getStatements(), indent + 4, result);
            }
            else {
                // counting for
                List<ASTPrimaryExpression> names = decl.getNames();
                List<ASTExpression> initializers = decl.getInitializers();
                int n = names.size();
                for (int i = 0; i < n; i++) {
                    ASTExpression name = names.get(i);
                    ASTExpression initializer = initializers.get(i);
                    if (initializer != null) {
                        internalTranslateExpression(name, TranslationContext.UNKNOWN, result);
                        result.append(" = ");
                        internalTranslateExpression(initializer, TranslationContext.UNKNOWN, result);
                        if (i < (n - 1)) {
                            result.append("; ");
                        }
                    }
                }
                result.append('\n');
                addIndent(indent, result);
                result.append("while ");
                internalTranslateExpression(s.getCondition(), TranslationContext.UNKNOWN, result);
                result.append(":\n");
                internalTranslateStatement(s.getStatements(), indent + 4, result);
                List<ASTExpression> iteration = s.getIteration();
                if (iteration != null) {
                    processForIteration(iteration, indent + 4, result);
                    result.append('\n');
                }
            }
        }
        else if (stmt instanceof ASTSwitchStatement) {
            ASTSwitchStatement s = (ASTSwitchStatement) stmt;
            String tv = getTempVarName();
            result.append(tv);
            result.append(" = ");
            internalTranslateExpression(s.getVariable(), TranslationContext.UNKNOWN, result);
            result.append('\n');
            boolean useIf = true;
            for (ASTCaseStatement c : s.getCases()) {
                List<ASTExpression> labels = c.getCaseLabels();
                int lc = labels.size();
                addIndent(indent, result);
                if (lc == 0) {
                   result.append("else:\n");
                }
                else {
                    result.append(useIf ? "if " : "elif ");
                    result.append(tv);
                    if (lc == 1) {
                        result.append(" == ");
                        internalTranslateExpression(labels.get(0), TranslationContext.UNKNOWN, result);
                    }
                    else {
                        result.append(" in (");
                        for (int i = 0; i < lc; i++) {
                            internalTranslateExpression(labels.get(i), TranslationContext.UNKNOWN, result);
                            if (i < (lc - 1)) {
                                result.append(", ");
                            }
                        }
                        result.append(')');
                    }
                    result.append(":\n");
                }
                internalTranslateStatement(c.getStatements(), indent + 4, result);
                useIf = !c.hasBreak();
            }
        }
        else if (stmt instanceof ASTMethodDeclaration) {
            ASTMethodDeclaration decl = (ASTMethodDeclaration) stmt;
            String methodName = decl.isConstructor() ? "__init__" : translateIdentifier(decl.getName(), TranslationContext.METHOD);
            List<ASTFormalParameter> formals = decl.getParameters();
            SymbolTable symbols = new SymbolTable();
            boolean isStatic = false;

            pushSymbols(symbols);
            List<String> modifiers = decl.getModifiers();
            if ((modifiers != null) && modifiers.contains("static")) {
                result.append("@staticmethod\n");
                addIndent(indent, result);
                isStatic = true;
            }
            result.append("def ");
            result.append(methodName);
            if (isStatic) {
                result.append('(');
            }
            else {
                result.append("(self");
                if (formals != null) {
                    result.append(", ");
                }
            }
            if (formals != null) {
                translateFormals(formals, symbols, false, false, result);
            }
            result.append("):\n");
            ASTStatementList statements = decl.getStatements();
            if (statements != null) {
                internalTranslateStatement(statements, indent + 4, result);
            }
            else {
                addIndent(indent + 4, result);
                result.append("pass\n");
            }
            result.append('\n');
            popSymbols();
        }
        else if (stmt instanceof ASTTryStatement) {
            ASTTryStatement tryStmt = (ASTTryStatement) stmt;
            result.append("try:\n");
            internalTranslateStatement(tryStmt.getBlock(), indent + 4, result);
            List<ASTExceptionInfo> catchBlocks = tryStmt.getCatchBlocks();
            if (catchBlocks != null) {
                for (ASTExceptionInfo cb: catchBlocks) {
                    addIndent(indent, result);
                    result.append("except ");
                    List<ASTTypeExpression> infos = cb.getExceptionTypes();
                    int n = infos.size();
                    boolean multiple = n > 1;
                    if (multiple) {
                        result.append('(');
                    }
                    for (int i = 0; i < n; i++) {
                        ASTTypeExpression te = infos.get(i);
                        internalTranslateExpression(te, TranslationContext.TYPE, result);
                        if (i < (n - 1)) {
                            result.append(", ");
                        }
                    }
                    if (multiple) {
                        result.append(')');
                    }
                    result.append(" as ");
                    result.append(cb.getVariable());
                    result.append(":\n");
                    internalTranslateStatement(cb.getBlock(), indent + 4, result);
                }
            }
            ASTStatement fb = tryStmt.getFinallyBlock();
            if (fb != null) {
                addIndent(indent, result);
                result.append("finally:\n");
                internalTranslateStatement(fb, indent + 4, result);
            }
        }
        else if (stmt instanceof ASTEnumDeclaration) {
            ASTEnumDeclaration enumDecl = (ASTEnumDeclaration) stmt;

            result.append("@unique\n");
            addIndent(indent, result);
            result.append("class ");
            result.append(enumDecl.getName());
            result.append("(Enum):\n");
            List<String> values = enumDecl.getValues();
            if (values == null) {
                addIndent(indent + 4, result);
                result.append("pass\n\n");
            }
            else {
                for (String s: values) {
                    addIndent(indent + 4, result);
                    result.append(s);
                    result.append(" = auto()\n");
                }
                result.append('\n');
            }
        }
        else if (stmt instanceof ASTClassDeclaration) {
            ASTClassDeclaration classDecl = (ASTClassDeclaration) stmt;
            List<ASTStatement> decls = classDecl.getDeclarations();
            result.append("class ");
            result.append(classDecl.getName());
            result.append(":\n");
            if (decls == null) {
                addIndent(indent + 4, result);
                result.append("pass\n");
            }
            else {
                for (ASTStatement decl: decls) {
                    if (decl instanceof ASTVariableOrFieldDeclaration) {
                        continue;
                    }
                    internalTranslateStatement(decl, indent + 4, result);
                }
            }
        }
        else {
            String s = String.format("Cannot translate statement %s", getSimpleName(stmt));
            throw new UnsupportedOperationException(s);
        }
        if (addNewline) {
            result.append('\n');
        }
    }

    @Override public void translateProperties(String name, int indent, StringBuilder result) {
        super.translateProperties(name, indent, result);
        if (!properties.isEmpty()) {
            for (String prop : properties.keySet()) {
                String s = translateIdentifier(prop, TranslationContext.FIELD);
                addIndent(indent, result);
                result.append("@property\n");
                addIndent(indent, result);
                result.append("def ");
                result.append(s);
                result.append("(self):\n");
                addIndent(indent + 4, result);
                result.append("return self._");
                result.append(s);
                result.append("\n\n");
                addIndent(indent, result);
                result.append('@');
                result.append(s);
                result.append(".setter\n");
                addIndent(indent, result);
                result.append("def ");
                result.append(s);
                result.append("(self, value):\n");
                addIndent(indent + 4, result);
                result.append("self._");
                result.append(s);
                result.append(" = value");
                result.append("\n\n");
            }
        }
    }

    @Override public String translateNonterminalArgs(String args) {
        CongoCCParser parser = new CongoCCParser(String.format("(%s)", args));
        try {
            parser.InvocationArguments();
            Node node = parser.rootNode();
            StringBuilder result = new StringBuilder();

            for (Node child : node) {
                if (child instanceof Expression) {
                    ASTExpression expr = (ASTExpression) transformTree(child);
                    internalTranslateExpression(expr, TranslationContext.UNKNOWN, result);
                    result.append(", ");
                }
            }
            result.setLength(result.length() - 2);  // lose the trailing ", "
            return result.toString();
        } catch (ParseException e) {
            e.printStackTrace(); // TODO handle this better
            return "";
        }
    }

    @Override
    public String translateInjectedClass(CodeInjector injector, String name) {
        StringBuilder result = new StringBuilder();
        String qualifiedName = String.format("%s.%s", appSettings.getNodePackage(), name);
        List<String> nameList = injector.getParentClasses(qualifiedName);
        List<ClassOrInterfaceBodyDeclaration> decls = injector.getBodyDeclarations(qualifiedName);
        // boolean isInterface = grammar.nodeIsInterface(name);
        int n = decls.size();

        nameList.remove("Node");    // don't have the Node interface in Python
        for (String s : new ArrayList<>(nameList)) {
            if (grammar.nodeIsInterface(s)) {
                String q = String.format("%s.%s", appSettings.getNodePackage(), s);
                List<ClassOrInterfaceBodyDeclaration> dl = injector.getBodyDeclarations(q);
                if (dl == null) {
                    nameList.remove(s);
                }
            }
        }
        result.append("class ");
        result.append(name);
        result.append('(');
        result.append(String.join(", ", nameList));
        result.append("):");
        if (n == 0) {
            result.append(" pass\n");
        }
        else {
            result.append('\n');
            // Collect all the field declarations
            List<FieldDeclaration> fieldDecls = new ArrayList<>();
            for (ClassOrInterfaceBodyDeclaration decl : decls) {
                if (decl instanceof FieldDeclaration) {
                    fieldDecls.add((FieldDeclaration) decl);
                }
            }
            clearFields();
            if (!fieldDecls.isEmpty()) {
                result.append("    def __init__(self, input_source=None):\n");
                result.append("        super().__init__(input_source)\n");
                for (FieldDeclaration fd : fieldDecls) {
                    translateStatement(fd, 8, result);
                }
                result.append('\n');
            }
            translateProperties(name, 4, result);
            for (ClassOrInterfaceBodyDeclaration decl : decls) {
                if (decl instanceof FieldDeclaration) {
                    continue;
                }
                if (decl instanceof MethodDeclaration) {
                    translateStatement(decl, 4, result);
                }
                else if (decl instanceof Initializer) {
                    SymbolTable symbols = new SymbolTable();
                    pushSymbols(symbols);
                    for (Node stmt : decl.descendantsOfType(CodeBlock.class)) {
                        translateStatement(stmt, 8, result);
                    }
                    popSymbols();
                }
                else {
                    String s = String.format("Cannot translate %s at %s", getSimpleName(decl), decl.getLocation());
                    throw new UnsupportedOperationException(s);
                }
            }
        }
        return result.toString();
    }

    @Override
    protected void translateCast(ASTTypeExpression cast, StringBuilder result) {
    }

    @Override
    public void translateFormals(List<FormalParameter> formals, SymbolTable symbols, StringBuilder result) {
        translateFormals(transformFormals(formals), symbols, false, false, result);
    }

    @Override
    public void translateImport(String javaName, StringBuilder result) {
        String prefix = String.format("%s.", appSettings.getParserPackage());
        List<String> parts = getImportParts(javaName, prefix);
        String from = null;
        String s = parts.get(0);
        if (Character.isLowerCase(s.charAt(0))) {
            from = String.format("%sparser", s);
            parts.remove(0);
        }
        int n = parts.size();
        if ((n == 0) || (n > 2)) {
            s = String.format("Cannot translate import %s with %d parts", javaName, n);
            throw new UnsupportedOperationException(s);
        }
        s = parts.get(0);
        String suffix = null;

        if (s.endsWith("Parser")) {
            suffix = "Parser";
        }
        else if (s.endsWith("Lexer")) {
            suffix = "Lexer";
        }
        if (n == 1) {
            if (suffix != null) {
                result.append("from ").append(from).append(" import ").append(suffix).append(" as ").append(s).append('\n');
            }
        }
        else {
            result.append("from ").append(from);
            if (suffix != null) {
                result.append('.').append(suffix.toLowerCase());
            }
            result.append(" import ").append(parts.get(1)).append('\n');
        }
    }

    @Override public void endClass(String name, boolean fields, StringBuilder result) {
        if (!fields && nestedDeclarations != null) {
            result.append('\n');
            for (Map.Entry<String, Set<String>> kv: nestedDeclarations.entrySet()) {
                String cn = kv.getKey();
                if (cn.endsWith("Lexer")) {
                    cn = "Lexer";
                }
                else if (cn.endsWith("Parser")) {
                    cn = "Parser";
                }
                for (String s: kv.getValue()) {
                    result.append(s).append(" = ").append(cn).append('.').append(s).append('\n');
                }
            }
        }
        super.endClass(name, fields, result);
    }
}
