package org.congocc.core;

import org.congocc.parser.Token;
import org.congocc.parser.Token.TokenType;
import static org.congocc.parser.Token.TokenType.*;

import java.util.Set;

import org.congocc.parser.tree.*;

public class BNFProduction extends Expansion {
    private Expansion expansion;
    private Expansion recoveryExpansion;
    private String lexicalState, name;
    private final String leadingComments = "";
    private boolean implicitReturnType;
    
    public Expansion getExpansion() {
        return expansion;
    }

    public void setExpansion(Expansion expansion) {
        this.expansion = expansion;
    }

    public Expansion getRecoveryExpansion() {return recoveryExpansion;}

    public void setRecoveryExpansion(Expansion recoveryExpansion) {this.recoveryExpansion = recoveryExpansion;}

    public String getLexicalState() {
        return lexicalState;
    }

    public void setLexicalState(String lexicalState) { 
        this.lexicalState = lexicalState; 
    }

    public String getName() {
        return name;
    }

    public String getFirstSetVarName() {
        return getName() + "_FIRST_SET";
    }
    
    public void setName(String name) {
        this.name = name;
    }

    public boolean isImplicitReturnType() {return implicitReturnType;}

    public void setImplicitReturnType(boolean implicitReturnType) {
        this.implicitReturnType = implicitReturnType;
    }

    public TreeBuildingAnnotation getTreeNodeBehavior() {
        return firstChildOfType(TreeBuildingAnnotation.class);
    }

    public TreeBuildingAnnotation getTreeBuildingAnnotation() {
        return firstChildOfType(TreeBuildingAnnotation.class);
    }

    public boolean getHasExplicitLookahead() {
        return expansion.getLookahead() != null;
    }

    public Lookahead getLookahead() {
        return expansion.getLookahead();
    }

    public CodeBlock getJavaCode() {
       return firstChildOfType(CodeBlock.class);
    }

    public boolean isOnlyForLookahead() {
        TreeBuildingAnnotation tba = getTreeBuildingAnnotation();
        return tba!=null && "scan".equals(tba.getNodeName());
    }

    public String getLookaheadMethodName() {
        return getAppSettings().generateIdentifierPrefix("check") + name;
    }

    public String getNodeName() {
        TreeBuildingAnnotation tba = getTreeBuildingAnnotation();
        if (tba != null) {
             String nodeName = tba.getNodeName();
             if (nodeName != null && !nodeName.equals("abstract") 
                 && !nodeName.equals("interface")
                 && !nodeName.equals("void")
                 && !nodeName.equals("scan")) {
                return nodeName;
             }
        }
        return this.getName();
    }

    public ThrowsList getThrowsList() {
        return firstChildOfType(ThrowsList.class);
    }
    
    public FormalParameters getParameterList() {
        return firstChildOfType(FormalParameters.class);
    }

    public String getLeadingComments() {
        return leadingComments;
    }


    public String getReturnType() {
        if (isImplicitReturnType()) {
            return getNodeName();
        }
        ReturnType rt = firstChildOfType(ReturnType.class);
        return rt == null ? "void" : rt.toString();
    }

    // used in templates
    public String getAccessModifier() {
        for (Token t : childrenOfType(Token.class)) {
           TokenType type = t.getType();
           if (type == PRIVATE) {
               return "private";
           }
           else if (type == PROTECTED) {
               return "protected";
           }
           else if (type == PACKAGE) {
               return "";
           }
        }
        return "public";
    }

    
    public void adjustFirstToken(Token t) {
        //FIXME later. Not very urgent.
/*        
        Token firstToken = firstChildOfType(Token.class);
        if (firstToken != t) {

        }
        if (firstChildOfType(Token.class) !== t)
        this.leadingComments = t.getLeadingComments();
*/
    }

    /**
     * Does this production potentially have left recursion?
     */
    public boolean isLeftRecursive() {
        return getExpansion().potentiallyStartsWith(getName());
    }
    
    @Override
    public Expansion getNestedExpansion() {
    	return expansion;
    }

	@Override
	public TokenSet getFirstSet() {
		return expansion.getFirstSet();
	}

	@Override
	public TokenSet getFinalSet() {
		return expansion.getFinalSet();
	}

	@Override
	protected int getMinimumSize(Set<String> visitedNonTerminals) {
		return expansion.getMinimumSize(visitedNonTerminals);
	}

	@Override
	protected int getMaximumSize(Set<String> visitedNonTermiinals) {
		return expansion.getMaximumSize(visitedNonTermiinals);
	}
}