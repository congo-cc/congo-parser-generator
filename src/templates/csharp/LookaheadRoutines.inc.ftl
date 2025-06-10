[#-- This template generates the various lookahead/predicate routines --]

[#import "CommonUtils.inc.ftl" as CU]

[#var UNLIMITED = 2147483647]
[#var MULTIPLE_LEXICAL_STATE_HANDLING = lexerData.numLexicalStates > 1]
[#set MULTIPLE_LEXICAL_STATE_HANDLING = false]


[#macro Generate]
    [@firstSetVars /]
#if settings.faultTolerant
    [@followSetVars /]
/#if
    [#if grammar.choicePointExpansions?size != 0]
       [@BuildLookaheads /]
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

[#macro BuildLookaheads]
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
${BuildScanRoutine(expansion)}
      [/#if]
   [/#list]
   [#list grammar.assertionExpansions as expansion]
      ${BuildAssertionRoutine(expansion)}
   [/#list]
   [#list grammar.expansionsNeedingPredicate as expansion]
${BuildPredicateRoutine(expansion)}
   [/#list]
   [#list grammar.allLookaheads as lookahead]
      [#if lookahead.nestedExpansion??]
${BuildLookaheadRoutine(lookahead)}
     [/#if]
   [/#list]
   [#list grammar.allLookBehinds as lookBehind]
${BuildLookBehindRoutine(lookBehind)}
   [/#list]
   [#list grammar.parserProductions as production]
${BuildProductionLookaheadMethod(production)}
   [/#list]
[/#macro]

[#macro BuildPredicateRoutine expansion]
  [#var lookaheadAmount = expansion.lookaheadAmount]
  [#if lookaheadAmount = 2147483647][#set lookaheadAmount = "UNLIMITED"][/#if]
    // BuildPredicateRoutine: expansion at ${expansion.location}
    private bool ${expansion.predicateMethodName}() {
        _remainingLookahead = ${lookaheadAmount};
        currentLookaheadToken = LastConsumedToken;
        var scanToEnd = false;
        try {
${BuildPredicateCode(expansion)}
      [#if !expansion.hasSeparateSyntacticLookahead && expansion.lookaheadAmount != 0]
${BuildScanCode(expansion)}
      [/#if]
            return true;
        }
        finally {
            _lookaheadRoutineNesting = 0;
            currentLookaheadToken = null;
            _hitFailure = false;
        }
    }

[/#macro]

[#macro BuildScanRoutine expansion]
[#-- # DBG > BuildScanRoutine --]
 [#if !expansion.singleTokenLookahead]
// scanahead routine for expansion at:
// ${expansion.location}
// BuildScanRoutine macro
[#set newVarIndex = 0 in CU]
private bool ${expansion.scanRoutineName}(bool scanToEnd) {
    [#if expansion.hasScanLimit]
       var prevPassedPredicateThreshold = _passedPredicateThreshold;
       _passedPredicateThreshold = -1;
    [#else]
       bool reachedScanCode = false;
       var passedPredicateThreshold = (int) _remainingLookahead - ${expansion.lookaheadAmount};
    [/#if]
    try {
       _lookaheadRoutineNesting++;
       ${BuildPredicateCode(expansion)}
      [#if !expansion.hasScanLimit]
       reachedScanCode = true;
      [/#if]
       ${BuildScanCode(expansion)}
    }
    finally {
       _lookaheadRoutineNesting--;
   [#if expansion.hasScanLimit]
       if (_remainingLookahead <= _passedPredicateThreshold) {
         _passedPredicate = true;
         _passedPredicateThreshold = prevPassedPredicateThreshold;
       }
   [#else]
       if (reachedScanCode && _remainingLookahead <= passedPredicateThreshold) {
         _passedPredicate = true;
       }
   [/#if]
    }
    _passedPredicate = false;
    return true;
}

 [/#if]
[#-- # DBG < BuildScanRoutine --]
[/#macro]

[#macro BuildAssertionRoutine expansion]
// scanahead routine for assertion at:
// ${expansion.parent.location}
// BuildAssertionRoutine macro
private bool ${expansion.scanRoutineName}() {
[#var storeCurrentLookaheadVar = CU.newVarName("currentLookahead")
      storeRemainingLookahead = CU.newVarName("remainingLookahead")]
[#set newVarIndex = 0 in CU]
    var ${storeRemainingLookahead} = _remainingLookahead;
    _remainingLookahead = UNLIMITED;
    var ${storeCurrentLookaheadVar} = currentLookaheadToken;
    var prevHitFailure = _hitFailure;
    if (currentLookaheadToken == null) {
        currentLookaheadToken = LastConsumedToken;
    }
    try {
        _lookaheadRoutineNesting++;
${BuildScanCode(expansion)}
        return true;
    }
    finally {
        _lookaheadRoutineNesting--;
        currentLookaheadToken = ${storeCurrentLookaheadVar};
        _remainingLookahead = ${storeRemainingLookahead};
        _hitFailure = prevHitFailure;
    }
}
[/#macro]

[#-- Build the code for checking semantic lookahead, lookbehind, and/or syntactic lookahead --]
[#macro BuildPredicateCode expansion]
[#-- # DBG > BuildPredicateCode --]
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
    _passedPredicate = true;
    return !_hitFailure;
}
if ([#if !expansion.lookahead.negated]![/#if]${expansion.lookaheadExpansion.scanRoutineName}(true)) {
    return false;
}
[/#if]
  #if expansion.lookaheadAmount == 0
    _passedPredicate = true;
  /#if
[#-- # DBG < BuildPredicateCode --]
[/#macro]

[#--
   Generates the routine for an explicit lookahead
   that is used in a nested lookahead.
 --]
[#macro BuildLookaheadRoutine lookahead]
[#-- # DBG > BuildLookaheadRoutine --]
[#if lookahead.nestedExpansion??]
// lookahead routine for lookahead at:
// ${lookahead.location}
private bool ${lookahead.nestedExpansion.scanRoutineName}(bool scanToEnd) {
    var prevRemainingLookahead = _remainingLookahead;
    var prevHitFailure = _hitFailure;
    var prevScanaheadToken = currentLookaheadToken;
    try {
        _lookaheadRoutineNesting++;
${BuildScanCode(lookahead.nestedExpansion)}
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
[#-- # DBG < BuildLookaheadRoutine --]
[/#macro]

[#macro BuildLookBehindRoutine lookBehind]
[#-- # DBG > BuildLookBehindRoutine --]
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
      [#var nextElement = lookBehind.path[element_index + 1]]
      [#var nextElementNegated = (nextElement[0] == "~")]
      [#if nextElementNegated][#set nextElement = nextElement?substring(1)][/#if]
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
[#-- # DBG < BuildLookBehindRoutine --]
[/#macro]

[#macro BuildProductionLookaheadMethod production]
[#--     # DBG > BuildProductionLookaheadMethod --]
        // BuildProductionLookaheadMethod macro
        private bool ${production.lookaheadMethodName}(bool scanToEnd) {
${BuildScanCode(production.expansion)}
            return true;
        }

[#--     # DBG < BuildProductionLookaheadMethod --]
[/#macro]

[#--
   Macro to build the lookahead code for an expansion.
   This macro just delegates to the various sub-macros
   based on the Expansion's class name.
--]
[#macro BuildScanCode expansion]
[#-- # DBG > BuildScanCode ${expansion.simpleName} --]
  [#var classname = expansion.simpleName]
  [#if classname != "ExpansionSequence" && classname != "ExpansionWithParentheses"]
if (_hitFailure) return false;
if (_remainingLookahead <= 0) {
    return true;
}
// Lookahead Code for ${classname} specified at ${expansion.location}
  [/#if]
  [@CU.HandleLexicalStateChange expansion, true]
   [#if classname = "ExpansionWithParentheses"]
      [@BuildScanCode expansion.nestedExpansion /]
   [#elseif expansion.singleTokenLookahead]
${ScanSingleToken(expansion)}
   [#elseif classname = "Assertion" && expansion.appliesInLookahead]
${ScanCodeAssertion(expansion)}
   [#elseif classname = "Failure"]
${ScanCodeError(expansion)}
   [#elseif classname = "TokenTypeActivation"]
${ScanCodeTokenActivation(expansion)}
   [#elseif classname = "ExpansionSequence"]
${ScanCodeSequence(expansion)}
   [#elseif classname = "ZeroOrOne"]
${ScanCodeZeroOrOne(expansion)}
   [#elseif classname = "ZeroOrMore"]
${ScanCodeZeroOrMore(expansion)}
   [#elseif classname = "OneOrMore"]
${ScanCodeOneOrMore(expansion)}
   [#elseif classname = "NonTerminal"]
      [@ScanCodeNonTerminal expansion /]
   [#elseif classname = "TryBlock" || classname = "AttemptBlock"]
      [@BuildScanCode expansion.nestedExpansion /]
   [#elseif classname = "ExpansionChoice"]
${ScanCodeChoice(expansion)}
   [#elseif classname = "CodeBlock"]
      [#if expansion.appliesInLookahead || expansion.insideLookahead || expansion.containingProduction.onlyForLookahead]
${globals::translateCodeBlock(expansion, 12)}
      [/#if]
   [/#if]
  [/@CU.HandleLexicalStateChange]
[#-- # DBG < BuildScanCode ${expansion.simpleName} --]
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
[#macro ScanCodeSequence sequence]
[#-- # DBG > ScanCodeSequence --]
   [#list sequence.units as sub]
       [@BuildScanCode sub /]
       [#if sub.scanLimit]
         if (!scanToEnd && _lookaheadStack.Count <= 1) {
            if (_lookaheadRoutineNesting == 0) {
              _remainingLookahead = ${sub.scanLimitPlus};
            }
            else if (_lookaheadStack.Count == 1) {
               _passedPredicateThreshold = (int) _remainingLookahead[#if sub.scanLimitPlus > 0] - ${sub.scanLimitPlus}[/#if];
            }
         }
       [/#if]
   [/#list]
[#-- # DBG < ScanCodeSequence --]
[/#macro]

[#--
  Generates the lookahead code for a non-terminal.
  It (trivially) just delegates to the code for
  checking the production's nested expansion
--]
[#macro ScanCodeNonTerminal nt]
// NonTerminal ${nt.name} at ${nt.location}
PushOntoLookaheadStack("${nt.containingProduction.name}", "${nt.inputSource?j_string}", ${nt.beginLine}, ${nt.beginColumn});
[#var prevScanToEndVarName = "prevScanToEnd" + CU.newID()]
bool ${prevScanToEndVarName} = ScanToEnd;
_currentLookaheadProduction = "${nt.production.name}";
try {
    if (!${nt.production.lookaheadMethodName}(${CU.bool(nt.scanToEnd)})) {
        return false;
    }
}
finally {
    PopLookaheadStack();
}
[/#macro]

[#macro ScanSingleToken expansion]
[#var firstSet = expansion.firstSet.tokenNames]
[#-- # DBG > ScanSingleToken --]
[#if firstSet?size = 1]
if (!ScanToken(${CU.TT}${firstSet[0]})) {
    return false;
}
[#else]
if (!ScanToken(${expansion.firstSetVarName})) {
    return false;
}
[/#if]
[#-- # DBG < ScanSingleToken --]
[/#macro]

[#macro ScanCodeAssertion assertion]
[#-- # DBG > ScanCodeAssertion --]
#if assertion.assertionExpression??
if (!(${globals::translateExpression(assertion.assertionExpression)})) {
    _hitFailure = true;
    return false;
}
#endif
[#if assertion.expansion??]
if ([#if !assertion.expansionNegated]![/#if]${assertion.expansion.scanRoutineName}()) {
    _hitFailure = true;
    return false;
}
[/#if]
[#-- # DBG < ScanCodeAssertion --]
[/#macro]

[#macro ScanCodeError expansion]
[#-- # DBG > ScanCodeError --]
_hitFailure = true;
return false;
[#-- # DBG < ScanCodeError --]
[/#macro]

[#macro ScanCodeTokenActivation activation]
[#-- # DBG > ScanCodeTokenActivation --]
[#if activation.deactivate]Dea[#else]A[/#if]ctivateTokenTypes(
[#list activation.tokenNames as name]
    ${CU.TT}${name}[#if name_has_next],[/#if]
[/#list]
)
[#-- # DBG < ScanCodeTokenActivation --]
[/#macro]]

[#macro ScanCodeChoice choice]
[#-- # DBG > ScanCodeChoice --]
var ${CU.newVarName("token")} = currentLookaheadToken;
var remainingLookahead${CU.newVarIndex} = _remainingLookahead;
var hitFailure${CU.newVarIndex} = _hitFailure;
var passedPredicate${CU.newVarIndex} = _passedPredicate;
try {
  [#list choice.choices as subseq]
    _passedPredicate = false;
    if (!${CheckExpansion(subseq)}) {
        currentLookaheadToken = token${CU.newVarIndex};
        _remainingLookahead = remainingLookahead${CU.newVarIndex};
        _hitFailure = hitFailure${CU.newVarIndex};
     [#if !subseq_has_next]
        return false;
     [#else]
        if (_passedPredicate && !_legacyGlitchyLookahead) return false;
     [/#if]
  [/#list]
  [#list choice.choices as unused] } [/#list]
}
finally {
    _passedPredicate = passedPredicate${CU.newVarIndex};
}
[#-- # DBG < ScanCodeChoice --]
[/#macro]

[#macro ScanCodeZeroOrOne zoo]
[#-- # DBG > ScanCodeZeroOrOne --]
var ${CU.newVarName("token")} = currentLookaheadToken;
var passedPredicate${CU.newVarIndex} = _passedPredicate;
_passedPredicate = false;
try {
    if (!${CheckExpansion(zoo.nestedExpansion)}) {
        if (_passedPredicate && !_legacyGlitchyLookahead) return false;
        currentLookaheadToken = token${CU.newVarIndex};
        _hitFailure = false;
    }
}
finally {
    _passedPredicate = passedPredicate${CU.newVarIndex};
}
[#-- # DBG < ScanCodeZeroOrOne --]
[/#macro]

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
[#macro ScanCodeZeroOrMore zom]
[#-- # DBG > ScanCodeZeroOrMore --]
var ${CU.newVarName("passedPredicate")} = _passedPredicate;
try {
    while (_remainingLookahead > 0 && !_hitFailure) {
        var token${CU.newVarIndex} = currentLookaheadToken;
        _passedPredicate = false;
        if (!${CheckExpansion(zom.nestedExpansion)}) {
            if (_passedPredicate && !_legacyGlitchyLookahead) return false;
            currentLookaheadToken = token${CU.newVarIndex};
            break;
        }
    }
}
finally {
    _passedPredicate = passedPredicate${CU.newVarIndex};
}
_hitFailure = false;
[#-- # DBG < ScanCodeZeroOrMore --]
[/#macro]

[#--
   Generates lookahead code for a OneOrMore construct
   It generates the code for checking a single occurrence
   and then the same code as a ZeroOrMore
--]
[#macro ScanCodeOneOrMore oom]
[#-- # DBG > ScanCodeOneOrMore --]
[#--
if (!(${CheckExpansion(oom.nestedExpansion)})) {
    return false;
}--]
[@BuildScanCode oom.nestedExpansion /]
[@ScanCodeZeroOrMore oom /]
[#-- # DBG < ScanCodeOneOrMore --]
[/#macro]


[#macro CheckExpansion expansion]
   [#if expansion.singleTokenLookahead]
     [#if expansion.firstSet.tokenNames?size = 1]
      ScanToken(${CU.TT}${expansion.firstSet.tokenNames[0]})
     [#else]
      ScanToken(${expansion.firstSetVarName})
     [/#if]
   [#else]
      ${expansion.scanRoutineName}(false)
   [/#if]
[/#macro]


