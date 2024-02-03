[#-- This template generates the various lookahead/predicate routines --]

[#import "CommonUtils.inc.ftl" as CU]

[#var UNLIMITED=2147483647]
[#var MULTIPLE_LEXICAL_STATE_HANDLING = lexerData.numLexicalStates > 1]
[#set MULTIPLE_LEXICAL_STATE_HANDLING = false]


[#macro Generate]
    [@firstSetVars /]
#if settings.faultTolerant
    [@followSetVars /]
/#if
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
[#-- # DBG > BuildScanRoutine ${indent} --]
 [#if !expansion.singleTokenLookahead || expansion.requiresPredicateMethod]
// scanahead routine for expansion at:
// ${expansion.location}
// BuildScanRoutine macro
private bool ${expansion.scanRoutineName}() {
    try {
        _lookaheadRoutineNesting++;
${BuildPredicateCode(expansion, indent + 8)}
${BuildScanCode(expansion, indent + 8)}
        return true;
    }
    finally {
        _lookaheadRoutineNesting--;
    }
}
 [/#if]
[#-- # DBG < BuildScanRoutine ${indent} --]
[/#macro]

[#macro BuildAssertionRoutine expansion indent]
[#var is=""?right_pad(indent)]
// scanahead routine for assertion at:
// ${expansion.parent.location}
// BuildAssertionRoutine macro
private bool ${expansion.scanRoutineName}() {
[#var storeCurrentLookaheadVar = CU.newVarName("currentLookahead")]
    _remainingLookahead = UNLIMITED;
    ScanToEnd = true;
    Token ${storeCurrentLookaheadVar} = currentLookaheadToken;
    if (currentLookaheadToken == null) {
        currentLookaheadToken = LastConsumedToken;
    }
    try {
        _lookaheadRoutineNesting++;
${BuildScanCode(expansion, indent + 4)}
        return true;
    }
    finally {
        _lookaheadRoutineNesting--;
        currentLookaheadToken = ${storeCurrentLookaheadVar};
    }
}
[/#macro]

[#-- Build the code for checking semantic lookahead, lookbehind, and/or syntactic lookahead --]
[#macro BuildPredicateCode expansion indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > BuildPredicateCode ${indent} --]
[#if expansion.hasSemanticLookahead && (expansion.lookahead.semanticLookaheadNested || expansion.containingProduction.onlyForLookahead)]
if (!(${globals::translateExpression(expansion.semanticLookahead)})) {
    return false;
}
[/#if]
[#if expansion.hasLookBehind]
if ([#if !expansion.lookBehind.negated]![/#if]${expansion.lookBehind.routineName}()) {
    return false;
}
[/#if]
[#if expansion.hasSeparateSyntacticLookahead]
if (_remainingLookahead <= 0) {
    return !_hitFailure;
}
if ([#if !expansion.lookahead.negated]![/#if]${expansion.lookaheadExpansion.scanRoutineName}()) {
  [#if expansion.lookahead.negated]
    return false;
  [#else]
    return false;
  [/#if]
}
[/#if]
[#-- # DBG < BuildPredicateCode ${indent} --]
[/#macro]

[#--
   Generates the routine for an explicit lookahead
   that is used in a nested lookahead.
 --]
[#macro BuildLookaheadRoutine lookahead indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > BuildLookaheadRoutine ${indent} --]
[#if lookahead.nestedExpansion??]
// lookahead routine for lookahead at:
// ${lookahead.location}
private bool ${lookahead.nestedExpansion.scanRoutineName}() {
    var prevRemainingLookahead = _remainingLookahead;
    var prevHitFailure = _hitFailure;
    var prevScanaheadToken = currentLookaheadToken;
    try {
        _lookaheadRoutineNesting++;
${BuildScanCode(lookahead.nestedExpansion, indent + 8)}
        return !_hitFailure;
    }
    finally {
        _lookaheadRoutineNesting--;
        currentLookaheadToken = prevScanaheadToken;
        _remainingLookahead = prevRemainingLookahead;
        _hitFailure = prevHitFailure;
    }
}

[/#if]
[#-- # DBG < BuildLookaheadRoutine ${indent} --]
[/#macro]

[#macro BuildLookBehindRoutine lookBehind indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > BuildLookBehindRoutine ${indent} --]
private bool ${lookBehind.routineName}() {
    var stackIterator = new ${lookBehind.backward?string("BackwardIterator", "ForwardIterator")}<NonTerminalCall>(ParsingStack, _lookaheadStack);
    NonTerminalCall ntc;
[#list lookBehind.path as element]
  [#var elementNegated = (element[0] == "~")]
  [#if elementNegated][#set element = element?substring(1)][/#if]
  [#if element = "."]
    if (!stackIterator.HasNext()) {
        return false;
    }
    stackIterator.Next();
  [#elseif element = "..."]
    [#if element_index = lookBehind.path?size-1]
      [#if lookBehind.hasEndingSlash]
    return !stackIterator.HasNext();
      [#else]
    return true;
      [/#if]
    [#else]
      [#var nextElement = lookBehind.path[element_index+1]]
      [#var nextElementNegated = (nextElement[0]=="~")]
      [#if nextElementNegated][#set nextElement=nextElement?substring(1)][/#if]
    while (stackIterator.HasNext()) {
        ntc = stackIterator.Next();
      [#var equalityOp = nextElementNegated?string("!=", "==")]
        if (ntc.ProductionName ${equalityOp} "${nextElement}") {
            stackIterator.Previous();
            break;
        }
        if (!stackIterator.HasNext()) {
            return false;
        }
    }
    [/#if]
  [#else]
    if (!stackIterator.HasNext()) {
        return false;
    }
    ntc = stackIterator.Next();
     [#var equalityOp = elementNegated?string("==", "!=")]
    if (ntc.ProductionName ${equalityOp} "${element}") {
        return false;
    }
  [/#if]
[/#list]
[#if lookBehind.hasEndingSlash]
    return !stackIterator.HasNext();
[#else]
    return true;
[/#if]
}
[#-- # DBG < BuildLookBehindRoutine ${indent} --]
[/#macro]

[#macro BuildProductionLookaheadMethod production indent]
[#var is=""?right_pad(indent)]
[#--     # DBG > BuildProductionLookaheadMethod ${indent} --]
        // BuildProductionLookaheadMethod macro
        private bool ${production.lookaheadMethodName}() {
[#if production.javaCode?? && production.javaCode.appliesInLookahead]
${globals::translateCodeBlock(production.javaCode, 12)}
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
[#-- # DBG > BuildScanCode ${indent} ${expansion.simpleName} --]
  [#var classname=expansion.simpleName]
  [#if classname != "ExpansionSequence" && classname != "ExpansionWithParentheses"]
if (_hitFailure || _remainingLookahead <= 0) {
    return !_hitFailure;
}
// Lookahead Code for ${classname} specified at ${expansion.location}
  [/#if]
  [@CU.HandleLexicalStateChange expansion true indent; indent]
   [#if classname = "ExpansionWithParentheses"]
      [@BuildScanCode expansion.nestedExpansion indent /]
   [#elseif expansion.singleTokenLookahead]
${ScanSingleToken(expansion, indent)}
   [#elseif classname = "Assertion"]
${ScanCodeAssertion(expansion, indent)}
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
${globals::translateCodeBlock(expansion, indent)}
      [/#if]
   [/#if]
  [/@CU.HandleLexicalStateChange]
[#-- # DBG < BuildScanCode ${indent} ${expansion.simpleName} --]
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
[#-- # DBG > ScanCodeSequence ${indent} --]
   [#list sequence.units as sub]
       [@BuildScanCode sub indent /]
       [#if sub.scanLimit]
if (!ScanToEnd && _lookaheadStack.Count <=1 && _lookaheadRoutineNesting == 0) {
    _remainingLookahead = ${sub.scanLimitPlus};
}
       [/#if]
   [/#list]
[#-- # DBG < ScanCodeSequence ${indent} --]
[/#macro]

[#--
  Generates the lookahead code for a non-terminal.
  It (trivially) just delegates to the code for
  checking the production's nested expansion
--]
[#macro ScanCodeNonTerminal nt indent]
[#var is=""?right_pad(indent)]
// NonTerminal ${nt.name} at ${nt.location}
PushOntoLookaheadStack("${nt.containingProduction.name}", "${nt.inputSource?j_string}", ${nt.beginLine}, ${nt.beginColumn});
[#var prevScanToEndVarName = "prevScanToEnd" + CU.newID()]
bool ${prevScanToEndVarName} = ScanToEnd;
_currentLookaheadProduction = "${nt.production.name}";
ScanToEnd = ${CU.bool(nt.scanToEnd)};
try {
    if (!${nt.production.lookaheadMethodName}()) {
        return false;
    }
}
finally {
    PopLookaheadStack();
    ScanToEnd = ${prevScanToEndVarName};
}
[/#macro]

[#macro ScanSingleToken expansion indent]
[#var is=""?right_pad(indent)]
[#var firstSet = expansion.firstSet.tokenNames]
[#-- # DBG > ScanSingleToken ${indent} --]
[#if firstSet?size = 1]
if (!ScanToken(${CU.TT}${firstSet[0]})) {
    return false;
}
[#else]
if (!ScanToken(${expansion.firstSetVarName})) {
    return false;
}
[/#if]
[#-- # DBG < ScanSingleToken ${indent} --]
[/#macro]

[#macro ScanCodeAssertion assertion indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeAssertion ${indent} --]
[#if assertion.assertionExpression?? && (assertion.insideLookahead || assertion.semanticLookaheadNested || assertion.containingProduction.onlyForLookahead)]
if (!(${globals::translateExpression(assertion.assertionExpression)})) {
    _hitFailure = true;
    return false;
}
[/#if]
[#if assertion.expansion??]
if ([#if !assertion.expansionNegated]![/#if]${assertion.expansion.scanRoutineName}()) {
    _hitFailure = true;
    return false;
}
[/#if]
[#-- # DBG < ScanCodeAssertion ${indent} --]
[/#macro]

[#macro ScanCodeError expansion indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeError ${indent} --]
_hitFailure = true;
return false;
[#-- # DBG < ScanCodeError ${indent} --]
[/#macro]

[#macro ScanCodeTokenActivation activation indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeTokenActivation ${indent} --]
[#if activation.deactivate]Dea[#else]A[/#if]ctivateTokenTypes(
[#list activation.tokenNames as name]
    ${CU.TT}${name}[#if name_has_next],[/#if]
[/#list]
)
[#-- # DBG < ScanCodeTokenActivation ${indent} --]
[/#macro]]

[#macro ScanCodeChoice choice indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeChoice ${indent} --]
var ${CU.newVarName("token")} = currentLookaheadToken;
var remainingLookahead${CU.newVarIndex} = _remainingLookahead;
var hitFailure${CU.newVarIndex} = _hitFailure;
  [#list choice.choices as subseq]
if (!${CheckExpansion(subseq)}) {
    currentLookaheadToken = token${CU.newVarIndex};
    _remainingLookahead = remainingLookahead${CU.newVarIndex};
    _hitFailure = hitFailure${CU.newVarIndex};
     [#if !subseq_has_next]
    return false;
     [/#if]
[#-- bump up the indentation, as the items in the list are recursive
     levels
--]
[#set is = is + "    "]
  [/#list]
[#list 1..choice.choices?size as i]
[#set is = ""?right_pad(4 * (choice.choices?size - i + 3))]
}
[/#list]
[#-- # DBG < ScanCodeChoice ${indent} --]
[/#macro]

[#macro ScanCodeZeroOrOne zoo indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeZeroOrOne ${indent} --]
var ${CU.newVarName("token")} = currentLookaheadToken;
if (!(${CheckExpansion(zoo.nestedExpansion)})) {
    currentLookaheadToken = token${CU.newVarIndex};
    _hitFailure = false;
}
[#-- # DBG < ScanCodeZeroOrOne ${indent} --]
[/#macro]

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
[#macro ScanCodeZeroOrMore zom indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeZeroOrMore ${indent} --]
while (_remainingLookahead > 0 && ! _hitFailure) {
    var ${CU.newVarName("token")} = currentLookaheadToken;
    if (!(${CheckExpansion(zom.nestedExpansion)})) {
        currentLookaheadToken = token${CU.newVarIndex};
        break;
    }
    _hitFailure = false;
}
[#-- # DBG < ScanCodeZeroOrMore ${indent} --]
[/#macro]

[#--
   Generates lookahead code for a OneOrMore construct
   It generates the code for checking a single occurrence
   and then the same code as a ZeroOrMore
--]
[#macro ScanCodeOneOrMore oom indent]
[#var is=""?right_pad(indent)]
[#-- # DBG > ScanCodeOneOrMore ${indent} --]
[#--
if (!(${CheckExpansion(oom.nestedExpansion)})) {
    return false;
}--]
[@BuildScanCode oom.nestedExpansion indent /]
[@ScanCodeZeroOrMore oom indent /]
[#-- # DBG < ScanCodeOneOrMore ${indent} --]
[/#macro]


[#macro CheckExpansion expansion]
   [#if expansion.singleTokenLookahead && !expansion.requiresPredicateMethod]
     [#if expansion.firstSet.tokenNames?size = 1]
      ScanToken(${CU.TT}${expansion.firstSet.tokenNames[0]})
     [#else]
      ScanToken(${expansion.firstSetVarName})
     [/#if]
   [#else]
      ${expansion.scanRoutineName}()
   [/#if]
[/#macro]


