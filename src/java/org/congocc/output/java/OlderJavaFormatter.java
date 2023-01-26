package org.congocc.output.java;

import static org.congocc.parser.CongoCCConstants.TokenType;
import static org.congocc.parser.CongoCCConstants.TokenType.*;

import org.congocc.parser.*;
import org.congocc.parser.tree.*;

import java.util.List;

/**
 * This is an older version of the JavaFormatter class to pretty-print java source code.
 * It was originally written in 2008.
 * The newer version, #JavaFormatter, was refactored to use the Node.Visitor pattern
 * @author revusky
 */

public class OlderJavaFormatter {
    
    private Token currentToken, lastToken;
    private BaseNode parent;
    private StringBuilder buf = new StringBuilder();
    private String indent = "    ";
    private String currentIndent = "";
    private String eol = "\n";
    
    public String format(BaseNode code) {
        buf = new StringBuilder();
        List<Token> allTokens = code.getAllTokens(true);
        for (Token t :  allTokens) {
            if (t instanceof Whitespace) {
                continue;
            }
            lastToken = currentToken;
            currentToken = t;
            parent = (BaseNode) t.getParent();
            handleToken();
        }
        return buf.toString();
    }

    private void startNewLineIfNecessary() {
        if (buf.length() == 0) {
            return;
        }
        int lastNL = buf.lastIndexOf(eol);
        if (lastNL + eol.length() == buf.length()) {
            return;
        }
        String line = buf.substring(lastNL+ eol.length());
        if (line.trim().length() ==0) {
            buf.setLength(lastNL+eol.length());
        } else {
            buf.append(eol);
        }
    }
    
    private void newLine() {
        startNewLineIfNecessary();
        buf.append(currentIndent);
    }
    
    private void handleToken() {
        switch (currentToken.getType()) {
            case LBRACE :
                handleOpenBrace();
                break;
            case RBRACE :
                handleCloseBrace();
                break;
            case COLON :
                if ((parent instanceof ConditionalOrExpression) || (parent instanceof ForStatement)) {
                    buf.append(" : ");
                } else {
                    buf.append(':');
                    newLine();
                }
                break;
            case SEMICOLON :
                buf.append(';');
                if (parent instanceof PackageDeclaration) {
                    buf.append(eol);
                    buf.append(eol);
                }
                else if (parent instanceof ForStatement) {
                	if (parent.getChild(parent.getChildCount()-1) != currentToken) {
                		buf.append(" ");
                	} else {
                		newLine();
                	}
                }
                else {
                    newLine();
                }
                break;
            case RPAREN :
                buf.append(')');
                if (parent instanceof Annotation) {
                    newLine();
                }
                break;
            case MULTI_LINE_COMMENT :
                newLine();
                buf.append(currentToken);
                newLine();
                break;
            case SINGLE_LINE_COMMENT : 
                handleSingleLineComment();
                break;
            case ELSE :
                buf.append("else ");
                break;
            case FOR : 
            	buf.append("for ");
            	break;
            case AT :
            	newLine();
            	buf.append("@");
            	break;
            case COMMA :
                buf.append(", ");
                break;
            default:
                if (buf.length() > 0 && currentToken.getType() != EOF) {
                    int lastChar = buf.codePointBefore(buf.length());
                    int thisChar = currentToken.toString().codePointAt(0);
                    if ((Character.isJavaIdentifierPart(lastChar) || lastChar == ')' || lastChar == ']') 
                            && Character.isJavaIdentifierPart(thisChar)) {
                        buf.append(' ');
                    }
                }
                buf.append(currentToken);
                TokenType type = currentToken.getType();
                if (type == IF || type == WHILE || type == GT || type == EQ || type == ASSIGN) {
                    buf.append(' ');
                }
                if (type == IDENTIFIER && parent instanceof Annotation && parent.indexOf(currentToken) == parent.getChildCount()-1) {
                    newLine();
                }
        }
    }
    
    private void handleSingleLineComment() {
        if (lastToken !=null && lastToken.getEndLine() == currentToken.getBeginLine()) {
            int lastNL = buf.indexOf(eol);
            if (lastNL >=0 && buf.substring(lastNL).trim().length() == 0) {
                buf.setLength(lastNL);
            }
        }
        buf.append(currentToken);
        newLine();
    }
    
    
    private void handleOpenBrace() {
        if (parent instanceof ArrayInitializer) {
            buf.append('{');
            return;
        }
        buf.append(' ');
        buf.append('{');
        currentIndent += indent;
        newLine();
    }
    
    private void handleCloseBrace() {
        if (parent == null) {
            return; //REVISIT
        }
        if (parent instanceof ArrayInitializer) {
            buf.append('}');
            return;
        }
        if (currentIndent.length() >= indent.length()) {
            currentIndent = currentIndent.substring(0, currentIndent.length() -indent.length());
        }
        newLine();
        buf.append('}');
        if (parent instanceof TypeDeclaration 
            || parent instanceof ConstructorDeclaration
            || parent.getParent() instanceof MethodDeclaration)
        {
            buf.append(eol);
            buf.append(eol);
        }
        newLine();
    }
}
