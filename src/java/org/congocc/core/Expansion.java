package org.congocc.core;

import java.util.*;

import org.congocc.parser.Node;
import org.congocc.parser.tree.*;

/**
 * Describes expansions - entities that may occur on the right hand sides of
 * productions. This is the abstract base class of a bunch of other more specific
 * classes.
 */
abstract public class Expansion extends BaseNode {

    private TreeBuildingAnnotation treeNodeBehavior;

    protected TokenSet firstSet;

    public int getIndex() {
        return getParent().indexOf(this);
    }

    public final BNFProduction getContainingProduction() {
    	if (this instanceof BNFProduction) return (BNFProduction) this;
        return firstAncestorOfType(BNFProduction.class);
    }

    private String scanRoutineName, firstSetVarName;

    private boolean tolerantParsing;

    /**
     * If we hit a parsing error in this expansion, do we try to recover? This is
     * only used in fault-tolerant mode, of course!
     */
    public boolean isTolerantParsing() {
        return tolerantParsing;
    }

    public void setTolerantParsing(boolean tolerantParsing) {
        this.tolerantParsing = tolerantParsing;
    }

    public String toString() {
        String result = "[" + getSimpleName() + " on line " + getBeginLine() + ", column " + getBeginColumn();
        String inputSource = getInputSource();
        if (inputSource != null) {
            result += " of ";
            result += inputSource;
        }
        return result + "]";
    }

    public Expansion getNestedExpansion() {
        return null;
    }

    public TreeBuildingAnnotation getTreeNodeBehavior() { //NOTE: removed "pull-down" of BNFProduction TBA
        if (this.getParent() instanceof ExpansionWithParentheses) {
            return null;
        }
    	return treeNodeBehavior;
    }

    public void setTreeNodeBehavior(TreeBuildingAnnotation treeNodeBehavior) {
        if (getGrammar().getAppSettings().getTreeBuildingEnabled()) {
            this.treeNodeBehavior = treeNodeBehavior;
            if (treeNodeBehavior != null) {
                getGrammar().addNodeType(null, treeNodeBehavior.getNodeName());
            }
        }
    }

    /**
     * This method is a bit hairy because of the need to deal with 
     * superfluous parentheses.
     * @return Is this expansion at a choice point?
     */
    public final boolean isAtChoicePoint() {
        if (!(this instanceof ExpansionChoice || this instanceof ExpansionSequence)) return false;
        Node parent = getParent();
        if (parent instanceof ExpansionChoice 
            || parent instanceof OneOrMore 
            || parent instanceof ZeroOrMore
            || parent instanceof ZeroOrOne 
            || parent instanceof BNFProduction) return true;
        if (!(parent instanceof ExpansionWithParentheses)) {
            return false;
        }
        if (parent.getParent() instanceof AttemptBlock) return false;
        ExpansionSequence grandparent = (ExpansionSequence) parent.getParent();
        return grandparent.childrenOfType(Expansion.class).get(0) == parent && grandparent.isAtChoicePoint();
    }

    /**
     * @return the first ancestor that is not (directly) inside superfluous
     *         parentheses. (Yes, this is a bit hairy and I'm not 100% sure it's
     *         correct!) I really need to take a good look at all this handling of
     *         expansions inside parentheses.
     */

    final Node getNonSuperfluousParent() {
        Node parent = getParent();
        if (!(parent instanceof Expansion) || !((Expansion) parent).superfluousParentheses()) {
            return parent;
        }
        ExpansionSequence grandparent = (ExpansionSequence) parent.getParent();
        return grandparent.getNonSuperfluousParent();
    }

    /**
     * @return the lexical state to switch into to parse this expansion. 
     */
    public String getSpecifiedLexicalState() {
        Node parent = getParent();
        if (parent instanceof BNFProduction) {
            return ((BNFProduction) parent).getLexicalState();
        }
        return null;
    }

    public TokenActivation getTokenActivation() {
        return firstChildOfType(TokenActivation.class);
    }

    private CodeBlock customErrorRecoveryBlock;

    public CodeBlock getCustomErrorRecoveryBlock() {
        return customErrorRecoveryBlock;
    }

    public void setCustomErrorRecoveryBlock(CodeBlock customErrorRecoveryBlock) {
        this.customErrorRecoveryBlock = customErrorRecoveryBlock;
    }

    /**
     * Is this expansion superfluous parentheses?
     */
    public final boolean superfluousParentheses() {
        return this.getClass() == ExpansionWithParentheses.class && firstChildOfType(ExpansionSequence.class) != null;
    }
 
    public boolean isInsideLookahead() {
        return firstAncestorOfType(Lookahead.class) != null;
    }

    public boolean isInsideAssertion() {
        return firstAncestorOfType(Assertion.class) != null;
    }

    public boolean getHasNumericalLookahead() {
        return false;
    }

    public boolean getHasExplicitScanLimit() {
        return false;
    }

    /**
     * Does this expansion have a separate lookahead expansion?
     */
    public boolean getHasSeparateSyntacticLookahead() {
        return false; // can only be true for an ExpansionSequence
    }

    private boolean scanLimit;
    private int scanLimitPlus;

    public final boolean isScanLimit() {
        return scanLimit;
    }

    public final void setScanLimit(boolean scanLimit) {
        this.scanLimit = scanLimit;
    }

    public final int getScanLimitPlus() {
        return scanLimitPlus;
    }

    public final void setScanLimitPlus(int scanLimitPlus) {
        this.scanLimitPlus = scanLimitPlus;

    }

    public Lookahead getLookahead() {return null;}

    public Expansion getLookaheadExpansion() { return this; }

    boolean getHasImplicitSyntacticLookahead() {return false;}

    public boolean startsWithGlobalCodeAction() {return false;}
    public boolean startsWithLexicalChange() {return false;}


    // This should only be used if we already know the expansion consumes at most a single
    // token. Otherwise, we're bound to get into infinite loops nesting into the nonterminals.
    // But with a maximum size of 1, given that we don't support left recursion, we should
    // be okay.
    private boolean hasGlobalSemanticActions() {
        assert this.getMaximumSize() <= 1;
        return descendants(CodeBlock.class).stream().anyMatch(CodeBlock::isAppliesInLookahead)
            || descendants(NonTerminal.class).stream().anyMatch(nt->nt.getNestedExpansion().hasGlobalSemanticActions());
    }

    public int getLookaheadAmount() {
         return 1;
    }

    public boolean getRequiresPredicateMethod() {
        return false;
    }

    public boolean getHasSemanticLookahead() {
        return false;
    }

    public boolean getHasScanLimit() {
        return false; // Only an ExpansionSequence or a NonTerminal can have a scan limit.
    }

    public Expression getSemanticLookahead() {
        return null;
    }

    public boolean getHasLookBehind() {
        return getLookBehind() != null;
    }

    public LookBehind getLookBehind() {
        return null;
    }

    public boolean isNegated() {
        return false;
    }

    public String getFirstSetVarName() {
        if (firstSetVarName == null) {
            if (this.getParent() instanceof BNFProduction) {
                firstSetVarName = ((BNFProduction) getParent()).getFirstSetVarName();
            } else {
                String prefix = getAppSettings().generateIdentifierPrefix("first_set");

                firstSetVarName = getAppSettings().generateUniqueIdentifier(prefix, this);
            }
        }
        return firstSetVarName;
    }

    public String getFinalSetVarName() {
        String result = getFirstSetVarName();
        String prefix = getAppSettings().generateIdentifierPrefix("first_set");

        if (result.startsWith(prefix)) {
            return result.replaceFirst("first", "final");
        }
        return result.replace("_FIRST_SET", "_FINAL_SET");
    }

    public String getFollowSetVarName() {
        String result = getFirstSetVarName();
        String prefix = getAppSettings().generateIdentifierPrefix("first_set");

        if (result.startsWith(prefix)) {
            return result.replaceFirst("first", "follow");
        }
        return result.replace("_FIRST_SET", "_FOLLOW_SET");
    }

    public String getScanRoutineName() {
        if (scanRoutineName == null) {
            if (this.getParent() instanceof BNFProduction) {
                BNFProduction prod = (BNFProduction) getParent();
                scanRoutineName = prod.getLookaheadMethodName();
            } else {
                String prefix = getAppSettings().generateIdentifierPrefix("check");
                if (this.getParent() instanceof Assertion) {
                    prefix = prefix.replace("check", "assert");
                }
                scanRoutineName = getAppSettings().generateUniqueIdentifier(prefix, this);
            }
        }
        return scanRoutineName;
    }

    public String getPredicateMethodName() {
        String checkPrefix = getAppSettings().generateIdentifierPrefix("check");
        String scanPrefix = getAppSettings().generateIdentifierPrefix("scan");
        return getScanRoutineName().replace(checkPrefix, scanPrefix);
    }

    public String getRecoverMethodName() {
        String checkPrefix = getAppSettings().generateIdentifierPrefix("check");
        String recoverPrefix = getAppSettings().generateIdentifierPrefix("recover");
        return getScanRoutineName().replace(checkPrefix, recoverPrefix);
    }

    public String getRecoverToMethodName() {
        String checkPrefix = getAppSettings().generateIdentifierPrefix("check");
        String recoverToPrefix = getAppSettings().generateIdentifierPrefix("recover_to");
        return getScanRoutineName().replace(checkPrefix, recoverToPrefix);
    }

    public int getFinalSetSize() {
        return getFinalSet().cardinality();
    }

    abstract public TokenSet getFirstSet();

    abstract public TokenSet getFinalSet();

    public boolean getHasFullFollowSet() {
        return !getFollowSet().isIncomplete();
    }

    /**
     * @return Can we do a short-cut and scan this expansion 
     * as a single token (using the scanToken method)
     */
    public boolean isSingleTokenLookahead() {
        return !isPossiblyEmpty() 
            && getMaximumSize() == 1 
            && !getHasScanLimit() && getLookahead() == null 
            && !hasGlobalSemanticActions() 
            && !startsWithLexicalChange();
    }

    /**
     * @return Can this expansion be matched by the empty string.
     */
    public boolean isPossiblyEmpty() {
        return getMinimumSize() == 0;
    }

    public boolean isEnteredUnconditionally() {
        if (getRequiresPredicateMethod()) return false;
        if (getHasSemanticLookahead()) return false;
        if (getHasNumericalLookahead() && getLookahead().getAmount() >0) return false;
        return isPossiblyEmpty();
    }

    /**
     * @return the minimum number of tokens that this expansion consumes.
     */
    abstract protected int getMinimumSize(Set<String> visitedNonTerminals);

    /**
     * @return the maximum number of tokens that this expansion consumes.
     */
    abstract protected int getMaximumSize(Set<String> visitedNonTermiinals);

    protected int minSize=-1, maxSize=-1;

    /**
     * @return the maximum number of tokens that this expansion consumes.
     */
    final public int getMaximumSize() {
        if (maxSize == -1) {
            maxSize = getMaximumSize(new HashSet<>());
        }
        return maxSize;
    }

    /**
     * @return the minimum number of tokens that this expansion consumes.
     */
    final public int getMinimumSize() {
        if (minSize == -1) {
            minSize = getMinimumSize(new HashSet<>());
        }
        return minSize;
    }


    /**
     * @return whether this expansion consumes exactly one token
     */
    final public boolean isSingleToken() {
        return getMinimumSize() == 1 && getMaximumSize() == 1;
    }

    private Expansion getPreceding() {
        Node parent = getParent();
        if (parent instanceof ExpansionSequence) {
            List<Expansion> siblings = parent.childrenOfType(Expansion.class);
            int index = siblings.indexOf(this);
            while (index > 0) {
                Expansion exp = siblings.get(index - 1);
                if (exp.getMaximumSize() > 0) {
                    return exp;
                }
                index--;
            }
        }
        return null;
    }

    public Expansion getFollowingExpansion() {
        Node parent = getParent();
        if (parent instanceof ExpansionSequence) {
            List<Expansion> siblings = parent.childrenOfType(Expansion.class);
            int index = siblings.indexOf(this);
            if (index < siblings.size() - 1)
                return siblings.get(index + 1);
        }
        if (parent instanceof Expansion) {
            return ((Expansion) parent).getFollowingExpansion();
        }
        return null;
    }

    public TokenSet getFollowSet() {
        TokenSet result = new TokenSet(getGrammar());
        if (isAtEndOfLoop()) {
            result.or(firstLoopAncestor().getFirstSet());
        }
        Expansion following = this;
        do {
            following = following.getFollowingExpansion();
            if (following == null) {
                result.setIncomplete(true);
                break;
            }
            result.or(following.getFirstSet());
        } while (following.isPossiblyEmpty());
        return result;
    }

    private boolean isAtEndOfLoop() {
        if (this instanceof ZeroOrMore || this instanceof OneOrMore)
            return true;
        Node parent = getParent();
        if (parent instanceof ExpansionSequence) {
            List<Expansion> siblings = parent.childrenOfType(Expansion.class);
            int index = siblings.indexOf(this);
            for (int i = index + 1; i < siblings.size(); i++) {
                if (!siblings.get(i).isPossiblyEmpty())
                    return false;
            }
        }
        if (parent instanceof Expansion) {
            return ((Expansion) parent).isAtEndOfLoop();
        }
        return false;
    }

    private Expansion firstLoopAncestor() {
        Expansion result = this;
        while (!(result instanceof ZeroOrMore || result instanceof OneOrMore)) {
            Node parent = result.getParent();
            if (parent instanceof Expansion)
                result = (Expansion) parent;
            else
                return null;
        }
        return result;
    }

    public Boolean getBeforeLexicalStateSwitch() {
        // We return a null if we don't have full info.
        Expansion following = this;
        do {
            following = following.getFollowingExpansion();
            if (following == null)
                return null;
            if (following.startsWithLexicalChange())
                return true;
        } while (following.isPossiblyEmpty());
        return false;
    }

    public boolean getRequiresRecoverMethod() {
        if (isInsideLookahead()) {
            return false;
        }
        if (getContainingProduction() != null && getContainingProduction().isOnlyForLookahead()) {
            return false;
        }
        if (isTolerantParsing() || getParent() instanceof BNFProduction) {
            return true;
        }
        Expansion preceding = getPreceding();
        return preceding != null && preceding.isTolerantParsing() && !(preceding instanceof Terminal);
    }

    /**
     * Whether this expansion can start with a given production
     * This is the default implementation that always returns false.
     */
    public boolean potentiallyStartsWith(String productionName, Set<String> alreadyVisited) {
        return false;
    }

    final public boolean potentiallyStartsWith(String productionName) {
        return potentiallyStartsWith(productionName, new HashSet<>());
    }

    final public boolean isTerminal() {
        return this instanceof Terminal;
    }

    /*
     * This section indicates whether this expansion has a child name associated with it,
     * and whether that relates to a single value or a list of values.
     */
    private String childName;
    private boolean multipleChildren;

    public String getChildName() { return childName; }
    public void setChildName(String name) { childName = name; }
    public boolean isMultipleChildren() { return multipleChildren; }
    public void setMultipleChildren(boolean multiple) { multipleChildren = multiple; }
}
