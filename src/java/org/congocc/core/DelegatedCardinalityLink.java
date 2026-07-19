package org.congocc.core;

import java.util.Collections;
import java.util.List;

import org.congocc.parser.tree.Assertion;
import org.congocc.parser.tree.ExpansionWithParentheses;

/**
 * A Phase-1 delegated-cardinality binding: orphan RCAs in callee production
 * {@code C} attach to iterating expansion {@code L} via call site {@code NT}.
 */
public final class DelegatedCardinalityLink {
    private final ExpansionWithParentheses loop;
    private final NonTerminal callSite;
    private final BNFProduction callee;
    private final List<Assertion> delegatedAssertions;

    public DelegatedCardinalityLink(ExpansionWithParentheses loop,
                                    NonTerminal callSite,
                                    BNFProduction callee,
                                    List<Assertion> delegatedAssertions) {
        this.loop = loop;
        this.callSite = callSite;
        this.callee = callee;
        this.delegatedAssertions = Collections.unmodifiableList(delegatedAssertions);
    }

    public ExpansionWithParentheses getLoop() {
        return loop;
    }

    public NonTerminal getCallSite() {
        return callSite;
    }

    public BNFProduction getCallee() {
        return callee;
    }

    public List<Assertion> getDelegatedAssertions() {
        return delegatedAssertions;
    }
}
