[#ftl strict_vars=true]

[#-- This template generates the various lookahead/predicate routines --]

[#import "CommonUtils.inc.ftl" as CU]

[#var UNLIMITED=2147483647]
[#var MULTIPLE_LEXICAL_STATE_HANDLING = grammar.lexerData.numLexicalStates > 1]
[#set MULTIPLE_LEXICAL_STATE_HANDLING = false]


[#macro Generate]
    [@firstSetVars /]
    [@followSetVars /]
    [#if grammar.choicePointExpansions?size != 0]
       [@BuildLookaheads 8 /]
     [/#if]
[/#macro]


[#macro firstSetVars]
    // ==================================================================
    // EnumSets that represent the various expansions' first set (i.e. the set of tokens with which the expansion can begin)
    // ==================================================================
    [#list grammar.expansionsForFirstSet as expansion]
          [@CU.firstSetVar expansion/]
    [/#list]
[/#macro]

[#macro finalSetVars]
    // ==================================================================
    // EnumSets that represent the various expansions' final set (i.e. the set of tokens with which the expansion can end)
    // ==================================================================
    [#list grammar.expansionsForFinalSet as expansion]
          [@finalSetVar expansion/]
    [/#list]
[/#macro]


[#macro followSetVars]
    // ==================================================================
    // EnumSets that represent the various expansions' follow set (i.e. the set of tokens that can immediately follow this)
    // ==================================================================
    [#list grammar.expansionsForFollowSet as expansion]
          [@CU.followSetVar expansion/]
    [/#list]
[/#macro]

[#macro BuildLookaheads indent]
        internal bool ScanToken(params TokenType[] types) {
            Token peekedToken = NextToken(currentLookaheadToken);
            TokenType tt = peekedToken.Type;
            if (System.Array.FindIndex<TokenType>(types, t => t == tt) < 0) {
                return false;
            }
            _remainingLookahead--;
            currentLookaheadToken = peekedToken;
            return true;
        }

        internal bool ScanToken(HashSet<TokenType> types) {
            Token peekedToken = NextToken(currentLookaheadToken);
            TokenType tt = peekedToken.Type;
            if (!types.Contains(tt)) {
                return false;
            }
            _remainingLookahead--;
            currentLookaheadToken = peekedToken;
            return true;
        }

// ====================================
// Lookahead Routines
// ====================================
   [#list grammar.choicePointExpansions as expansion]
      [#if expansion.parent.class.simpleName != "BNFProduction"]
${BuildScanRoutine(expansion, indent)}
      [/#if]
   [/#list]
   [#list grammar.assertionExpansions as expansion]
      ${BuildAssertionRoutine(expansion, indent)}
   [/#list]
   [#list grammar.expansionsNeedingPredicate as expansion]
${BuildPredicateRoutine(expansion, indent)}
   [/#list]
   [#list grammar.allLookaheads as lookahead]
      [#if lookahead.nestedExpansion??]
${BuildLookaheadRoutine(lookahead, indent)}
     [/#if]
   [/#list]
   [#list grammar.allLookBehinds as lookBehind]
${BuildLookBehindRoutine(lookBehind, indent)}
   [/#list]
   [#list grammar.parserProductions as production]
${BuildProductionLookaheadMethod(production, indent)}
   [/#list]
[/#macro]

[#macro BuildPredicateRoutine expansion indent]
  [#var lookaheadAmount = expansion.lookaheadAmount]
  [#if lookaheadAmount = 2147483647][#set lookaheadAmount = "UNLIMITED"][/#if]
    // BuildPredicateRoutine: expansion at ${expansion.location}
    private bool ${expansion.predicateMethodName}() {
        _remainingLookahead = ${lookaheadAmount};
        currentLookaheadToken = LastConsumedToken;
        try {
${BuildPredicateCode(expansion, 12)}
      [#if !expansion.hasSeparateSyntacticLookahead && expansion.lookaheadAmount != 0]
${BuildScanCode(expansion, 12)}
      [/#if]
            return true;
        }
        finally {
            _lookaheadRoutineNesting = 0;
            currentLookaheadToken = null;
            _hitFailure = false;
            ScanToEnd = false;
        }
    }

[/#macro]

[#macro BuildScanRoutine expansion indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > BuildScanRoutine ${indent} --]
 [#if !expansion.singleTokenLookahead || expansion.requiresPredicateMethod]
${is}// scanahead routine for expansion at:
${is}// ${expansion.location}
${is}// BuildScanRoutine macro
${is}private bool ${expansion.scanRoutineName}() {
${is}    try {
${is}        _lookaheadRoutineNesting++;
${BuildPredicateCode(expansion, indent + 8)}
${BuildScanCode(expansion, indent + 8)}
${is}        return true;
${is}    }
${is}    finally {
${is}        _lookaheadRoutineNesting--;
${is}    }
${is}}
 [/#if]
[#-- ${is}# DBG < BuildScanRoutine ${indent} --]
[/#macro]

[#macro BuildAssertionRoutine expansion indent]
[#var is=""?right_pad(indent)]
${is}// scanahead routine for assertion at: 
${is}// ${expansion.parent.location}
${is}// BuildAssertionRoutine macro
${is}private bool ${expansion.scanRoutineName}() {
[#var storeCurrentLookaheadVar = CU.newVarName("currentLookahead")]
${is}    _remainingLookahead = UNLIMITED;
${is}    ScanToEnd = true;
${is}    Token ${storeCurrentLookaheadVar} = currentLookaheadToken;
${is}    if (currentLookaheadToken == null) {
${is}        currentLookaheadToken = LastConsumedToken;
${is}    }
${is}    try {
${is}        _lookaheadRoutineNesting++;
${BuildScanCode(expansion, indent + 4)}
${is}        return true;
${is}    }
${is}    finally {
${is}        _lookaheadRoutineNesting--;
${is}        currentLookaheadToken = ${storeCurrentLookaheadVar};
${is}    }
${is}}
[/#macro]

[#-- Build the code for checking semantic lookahead, lookbehind, and/or syntactic lookahead --]
[#macro BuildPredicateCode expansion indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > BuildPredicateCode ${indent} --]
[#if expansion.hasSemanticLookahead && (expansion.lookahead.semanticLookaheadNested || expansion.containingProduction.onlyForLookahead)]
${is}if (!(${globals.translateExpression(expansion.semanticLookahead)})) {
${is}    return false;
${is}}
[/#if]
[#if expansion.hasLookBehind]
${is}if ([#if !expansion.lookBehind.negated]![/#if]${expansion.lookBehind.routineName}()) {
${is}    return false;
${is}}
[/#if]
[#if expansion.hasSeparateSyntacticLookahead]
${is}if (_remainingLookahead <= 0) {
${is}    return !_hitFailure;
${is}}
${is}if ([#if !expansion.lookahead.negated]![/#if]${expansion.lookaheadExpansion.scanRoutineName}()) {
  [#if expansion.lookahead.negated]
${is}    return false;
  [#else]
${is}    return false;
  [/#if]
${is}}
[/#if]
[#-- ${is}# DBG < BuildPredicateCode ${indent} --]
[/#macro]

[#--
   Generates the routine for an explicit lookahead
   that is used in a nested lookahead.
 --]
[#macro BuildLookaheadRoutine lookahead indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > BuildLookaheadRoutine ${indent} --]
[#if lookahead.nestedExpansion??]
${is}// lookahead routine for lookahead at:
${is}// ${lookahead.location}
${is}private bool ${lookahead.nestedExpansion.scanRoutineName}() {
${is}    var prevRemainingLookahead = _remainingLookahead;
${is}    var prevHitFailure = _hitFailure;
${is}    var prevScanaheadToken = currentLookaheadToken;
${is}    try {
${is}        _lookaheadRoutineNesting++;
${BuildScanCode(lookahead.nestedExpansion, indent + 8)}
${is}        return !_hitFailure;
${is}    }
${is}    finally {
${is}        _lookaheadRoutineNesting--;
${is}        currentLookaheadToken = prevScanaheadToken;
${is}        _remainingLookahead = prevRemainingLookahead;
${is}        _hitFailure = prevHitFailure;
${is}    }
${is}}

[/#if]
[#-- ${is}# DBG < BuildLookaheadRoutine ${indent} --]
[/#macro]

[#macro BuildLookBehindRoutine lookBehind indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > BuildLookBehindRoutine ${indent} --]
${is}private bool ${lookBehind.routineName}() {
${is}    var stackIterator = new ${lookBehind.backward?string("BackwardIterator", "ForwardIterator")}<NonTerminalCall>(ParsingStack, _lookaheadStack);
${is}    NonTerminalCall ntc;
[#list lookBehind.path as element]
  [#var elementNegated = (element[0] == "~")]
  [#if elementNegated][#set element = element?substring(1)][/#if]
  [#if element = "."]
${is}    if (!stackIterator.HasNext()) {
${is}        return false;
${is}    }
${is}    stackIterator.Next();
  [#elseif element = "..."]
    [#if element_index = lookBehind.path?size-1]
      [#if lookBehind.hasEndingSlash]
${is}    return !stackIterator.HasNext();
      [#else]
${is}    return true;
      [/#if]
    [#else]
      [#var nextElement = lookBehind.path[element_index+1]]
      [#var nextElementNegated = (nextElement[0]=="~")]
      [#if nextElementNegated][#set nextElement=nextElement?substring(1)][/#if]
${is}    while (stackIterator.HasNext()) {
${is}        ntc = stackIterator.Next();
      [#var equalityOp = nextElementNegated?string("!=", "==")]
${is}        if (ntc.ProductionName ${equalityOp} "${nextElement}") {
${is}            stackIterator.Previous();
${is}            break;
${is}        }
${is}        if (!stackIterator.HasNext()) {
${is}            return false;
${is}        }
${is}    }
    [/#if]
  [#else]
${is}    if (!stackIterator.HasNext()) {
${is}        return false;
${is}    }
${is}    ntc = stackIterator.Next();
     [#var equalityOp = elementNegated?string("==", "!=")]
${is}    if (ntc.ProductionName ${equalityOp} "${element}") {
${is}        return false;
${is}    }
  [/#if]
[/#list]
[#if lookBehind.hasEndingSlash]
${is}    return !stackIterator.HasNext();
[#else]
${is}    return true;
[/#if]
${is}}
[#-- ${is}# DBG < BuildLookBehindRoutine ${indent} --]
[/#macro]

[#macro BuildProductionLookaheadMethod production indent]
[#var is=""?right_pad(indent)]
[#--     # DBG > BuildProductionLookaheadMethod ${indent} --]
        // BuildProductionLookaheadMethod macro
        private bool ${production.lookaheadMethodName}() {
[#if production.javaCode?? && production.javaCode.appliesInLookahead]
${globals.translateCodeBlock(production.javaCode, 12)}
[/#if]
${BuildScanCode(production.expansion, 12)}
            return true;
        }

[#--     # DBG < BuildProductionLookaheadMethod ${indent} --]
[/#macro]

[#--
   Macro to build the lookahead code for an expansion.
   This macro just delegates to the various sub-macros
   based on the Expansion's class name.
--]
[#macro BuildScanCode expansion indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > BuildScanCode ${indent} ${expansion.simpleName} --]
  [#var classname=expansion.simpleName]
  [#if classname != "ExpansionSequence" && classname != "ExpansionWithParentheses"]
${is}if (_hitFailure || _remainingLookahead <= 0) {
${is}    return !_hitFailure;
${is}}
${is}// Lookahead Code for ${classname} specified at ${expansion.location}
  [/#if]
  [@CU.HandleLexicalStateChange expansion true indent; indent]
   [#if classname = "ExpansionWithParentheses"]
      [@BuildScanCode expansion.nestedExpansion indent /]
   [#elseif expansion.singleTokenLookahead]
${ScanSingleToken(expansion, indent)}
   [#elseif classname = "Assertion"]
${ScanCodeAssertion(expansion, indent)}
   [#elseif classname = "LexicalStateSwitch"]
       ${ScanCodeLexicalStateSwitch(expansion)}
   [#elseif classname = "Failure"]
${ScanCodeError(expansion, indent)}
   [#elseif classname = "TokenTypeActivation"]
${ScanCodeTokenActivation(expansion, indent)}
   [#elseif classname = "ExpansionSequence"]
${ScanCodeSequence(expansion, indent)}
   [#elseif classname = "ZeroOrOne"]
${ScanCodeZeroOrOne(expansion, indent)}
   [#elseif classname = "ZeroOrMore"]
${ScanCodeZeroOrMore(expansion, indent)}
   [#elseif classname = "OneOrMore"]
${ScanCodeOneOrMore(expansion, indent)}
   [#elseif classname = "NonTerminal"]
      [@ScanCodeNonTerminal expansion indent /]
   [#elseif classname = "TryBlock" || classname="AttemptBlock"]
      [@BuildScanCode expansion.nestedExpansion indent /]
   [#elseif classname = "ExpansionChoice"]
${ScanCodeChoice(expansion, indent)}
   [#elseif classname = "CodeBlock"]
      [#if expansion.appliesInLookahead || expansion.insideLookahead || expansion.containingProduction.onlyForLookahead]
${globals.translateCodeBlock(expansion, indent)}
      [/#if]
   [/#if]
  [/@CU.HandleLexicalStateChange]
[#-- ${is}# DBG < BuildScanCode ${indent} ${expansion.simpleName} --]
[/#macro]

[#--
   Generates the lookahead code for an ExpansionSequence.
   In legacy JavaCC there was some quite complicated logic so as
   not to generate unnecessary code. They actually had a longstanding bug
   there, which was the topic of this blog post: https://congocc.com/2020/10/28/a-bugs-life/
   I very much doubt that this kind of space optimization is worth
   the candle nowadays and it just really complicated the code. Also, the ability
   to scan to the end of an expansion strike me as quite useful in general,
   particularly for fault-tolerant.
--]
[#macro ScanCodeSequence sequence indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeSequence ${indent} --]
   [#list sequence.units as sub]
       [@BuildScanCode sub indent /]
       [#if sub.scanLimit]
${is}if (!ScanToEnd && _lookaheadStack.Count <=1 && _lookaheadRoutineNesting == 0) {
${is}    _remainingLookahead = ${sub.scanLimitPlus};
${is}}
       [/#if]
   [/#list]
[#-- ${is}# DBG < ScanCodeSequence ${indent} --]
[/#macro]

[#--
  Generates the lookahead code for a non-terminal.
  It (trivially) just delegates to the code for
  checking the production's nested expansion
--]
[#macro ScanCodeNonTerminal nt indent]
[#var is=""?right_pad(indent)]
${is}// NonTerminal ${nt.name} at ${nt.location}
${is}PushOntoLookaheadStack("${nt.containingProduction.name}", "${nt.inputSource?j_string}", ${nt.beginLine}, ${nt.beginColumn});
[#var prevScanToEndVarName = "prevScanToEnd" + CU.newID()]
${is}bool ${prevScanToEndVarName} = ScanToEnd;
${is}_currentLookaheadProduction = "${nt.production.name}";
${is}ScanToEnd = ${CU.bool(nt.scanToEnd)};
${is}try {
${is}    if (!${nt.production.lookaheadMethodName}()) {
${is}        return false;
${is}    }
${is}}
${is}finally {
${is}    PopLookaheadStack();
${is}    ScanToEnd = ${prevScanToEndVarName};
${is}}
[/#macro]

[#macro ScanSingleToken expansion indent]
[#var is=""?right_pad(indent)]
[#var firstSet = expansion.firstSet.tokenNames]
[#-- ${is}# DBG > ScanSingleToken ${indent} --]
[#if firstSet?size = 1]
${is}if (!ScanToken(${CU.TT}${firstSet[0]})) {
${is}    return false;
${is}}
[#else]
${is}if (!ScanToken(${expansion.firstSetVarName})) {
${is}    return false;
${is}}
[/#if]
[#-- ${is}# DBG < ScanSingleToken ${indent} --]
[/#macro]

[#macro ScanCodeAssertion assertion indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeAssertion ${indent} --]
[#if assertion.assertionExpression?? && (assertion.insideLookahead || assertion.semanticLookaheadNested || assertion.containingProduction.onlyForLookahead)]
${is}if (!(${globals.translateExpression(assertion.assertionExpression)})) {
${is}    _hitFailure = true;
${is}    return false;
${is}}
[/#if]
[#if assertion.expansion??]
${is}if ([#if !assertion.expansionNegated]![/#if]${assertion.expansion.scanRoutineName}()) {
${is}    _hitFailure = true;
${is}    return false;
${is}}
[/#if]
[#-- ${is}# DBG < ScanCodeAssertion ${indent} --]
[/#macro]

[#macro ScanCodeError expansion indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeError ${indent} --]
${is}_hitFailure = true;
${is}return false;
[#-- ${is}# DBG < ScanCodeError ${indent} --]
[/#macro]

[#macro ScanCodeTokenActivation activation indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeTokenActivation ${indent} --]
${is}[#if activation.deactivate]Dea[#else]A[/#if]ctivateTokenTypes(
[#list activation.tokenNames as name]
${is}    ${CU.TT}${name}[#if name_has_next],[/#if]
[/#list]
${is})
[#-- ${is}# DBG < ScanCodeTokenActivation ${indent} --]
[/#macro]]

[#macro ScanCodeChoice choice indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeChoice ${indent} --]
${is}var ${CU.newVarName("token")} = currentLookaheadToken;
${is}var remainingLookahead${CU.newVarIndex} = _remainingLookahead;
${is}var hitFailure${CU.newVarIndex} = _hitFailure;
  [#list choice.choices as subseq]
${is}if (!${CheckExpansion(subseq)}) {
${is}    currentLookaheadToken = token${CU.newVarIndex};
${is}    _remainingLookahead = remainingLookahead${CU.newVarIndex};
${is}    _hitFailure = hitFailure${CU.newVarIndex};
     [#if !subseq_has_next]
${is}    return false;
     [/#if]
[#-- bump up the indentation, as the items in the list are recursive
     levels
--]
[#set is = is + "    "]
  [/#list]
[#list 1..choice.choices?size as i]
[#set is = ""?right_pad(4 * (choice.choices?size - i + 3))]
${is}}
[/#list]
[#-- ${is}# DBG < ScanCodeChoice ${indent} --]
[/#macro]

[#macro ScanCodeZeroOrOne zoo indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeZeroOrOne ${indent} --]
${is}var ${CU.newVarName("token")} = currentLookaheadToken;
${is}if (!(${CheckExpansion(zoo.nestedExpansion)})) {
${is}    currentLookaheadToken = token${CU.newVarIndex};
${is}    _hitFailure = false;
${is}}
[#-- ${is}# DBG < ScanCodeZeroOrOne ${indent} --]
[/#macro]

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
[#macro ScanCodeZeroOrMore zom indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeZeroOrMore ${indent} --]
${is}while (_remainingLookahead > 0 && ! _hitFailure) {
${is}    var ${CU.newVarName("token")} = currentLookaheadToken;
${is}    if (!(${CheckExpansion(zom.nestedExpansion)})) {
${is}        currentLookaheadToken = token${CU.newVarIndex};
${is}        break;
${is}    }
${is}    _hitFailure = false;
${is}}
[#-- ${is}# DBG < ScanCodeZeroOrMore ${indent} --]
[/#macro]

[#--
   Generates lookahead code for a OneOrMore construct
   It generates the code for checking a single occurrence
   and then the same code as a ZeroOrMore
--]
[#macro ScanCodeOneOrMore oom indent]
[#var is=""?right_pad(indent)]
[#-- ${is}# DBG > ScanCodeOneOrMore ${indent} --]
[#--
${is}if (!(${CheckExpansion(oom.nestedExpansion)})) {
${is}    return false;
${is}}--]
[@BuildScanCode oom.nestedExpansion indent /]
[@ScanCodeZeroOrMore oom indent /]
[#-- ${is}# DBG < ScanCodeOneOrMore ${indent} --]
[/#macro]


[#macro CheckExpansion expansion]
   [#if expansion.singleTokenLookahead && !expansion.requiresPredicateMethod]
     [#if expansion.firstSet.tokenNames?size = 1]
      ScanToken(${CU.TT}${expansion.firstSet.tokenNames[0]})[#t]
     [#else]
      ScanToken(${expansion.firstSetVarName})[#t]
     [/#if]
   [#else]
      ${expansion.scanRoutineName}()[#t]
   [/#if]
[/#macro]


