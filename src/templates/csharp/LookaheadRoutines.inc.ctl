[#-- This template generates the various lookahead/predicate routines --]

#import "CommonUtils.inc.ctl" as CU

#var UNLIMITED = 2147483647
#var MULTIPLE_LEXICAL_STATE_HANDLING = lexerData.numLexicalStates > 1
#set MULTIPLE_LEXICAL_STATE_HANDLING = false


#macro Generate
    [@firstSetVars /]
#if settings.faultTolerant
    [@followSetVars /]
#endif
    #if grammar.choicePointExpansions?size != 0
       [@BuildLookaheads /]
    #endif
#endmacro


#macro firstSetVars
    // ==================================================================
    // EnumSets that represent the various expansions' first set (i.e. the set of tokens with which the expansion can begin)
    // ==================================================================
    #list grammar.expansionsForFirstSet as expansion
          [@CU.firstSetVar expansion/]
    #endlist
#endmacro

#macro finalSetVars
    // ==================================================================
    // EnumSets that represent the various expansions' final set (i.e. the set of tokens with which the expansion can end)
    // ==================================================================
    #list grammar.expansionsForFinalSet as expansion
          [@finalSetVar expansion/]
    #endlist
#endmacro


#macro followSetVars
    // ==================================================================
    // EnumSets that represent the various expansions' follow set (i.e. the set of tokens that can immediately follow this)
    // ==================================================================
    #list grammar.expansionsForFollowSet as expansion
          [@CU.followSetVar expansion/]
    #endlist
#endmacro

#macro BuildLookaheads
        internal bool ScanToken(params TokenType[] types) {
            Token peekedToken = NextToken(currentLookaheadToken);
            bool foundMatch = false;
            foreach (TokenType tt in types) {
                if (TypeMatches(tt, peekedToken)) {
                    foundMatch = true;
                    break;
                }
            }
            if (!foundMatch) return false;
            _remainingLookahead--;
            currentLookaheadToken = peekedToken;
            return true;
        }

        internal bool ScanToken(HashSet<TokenType> types) {
            Token peekedToken = NextToken(currentLookaheadToken);
            if (!HasMatch(types, peekedToken)) {
                return false;
            }
            _remainingLookahead--;
            currentLookaheadToken = peekedToken;
            return true;
        }

#if lexerData.hasContextualTokens
    private bool IsContextualToken(TokenType type) {
      return
         #list lexerData.contextualTokens as ctok
           type == TokenType.${ctok.label}
            ${ctok_has_next ?: "||"}
         #endlist
        ;
    }

    private bool IsIgnoreCase(TokenType type) {
        #if !lexerData.literalsThatDifferInCaseFromDefault
           return ${settings.ignoreCase ?: "true":"false"};
        #else
        return ${settings.ignoreCase ?: "!"}
        (
            #list lexerData.literalsThatDifferInCaseFromDefault as literal
                type == TokenType.${literal.label}
                ${literal_has_next ?: "||"}
            #endlist
        );
        #endif
    }

    private string GetLiteralString(TokenType type) {
        switch(type) {
            #list lexerData.regularExpressions as regexp
               #if regexp.literalString??
                  case TokenType.${regexp.label} : return "${regexp.literalString?j_string}";
               #endif
            #endlist
            default : return null;
        }
    }

  internal bool TypeMatches(TokenType type, Token tok) {
     if (tok.Type == type) return true;
     if (IsContextualToken(type)) {
         return IsIgnoreCase(type) ?
        (String.Compare(GetLiteralString(type), tok.ToString(), StringComparison.OrdinalIgnoreCase) == 0)
         : GetLiteralString(type).Equals(tok.ToString());
     }
     return false;
  }

  internal bool HasMatch(HashSet<TokenType> types, Token tok) {
      if (types.Contains(tok.Type)) return true;
      foreach (TokenType tt in types) {
         if (IsContextualToken(tt)) {
            if (TypeMatches(tt, tok)) return true;
         }
      }
      return false;
  }
#else
  internal bool TypeMatches(TokenType type, Token tok) {
      return tok.Type == type;
  }
  internal bool HasMatch(HashSet<TokenType> types, Token tok) {
      return types.Contains(tok.Type);
  }
#endif


// ====================================
// Lookahead Routines
// ====================================
   #list grammar.choicePointExpansions as expansion
      #if expansion.parent.class.simpleName != "BNFProduction"
${BuildScanRoutine(expansion)}
      #endif
   #endlist
   #list grammar.assertionExpansions as expansion
      ${BuildAssertionRoutine(expansion)}
   #endlist
   #list grammar.expansionsNeedingPredicate as expansion
${BuildPredicateRoutine(expansion)}
   #endlist
   #list grammar.allLookaheads as lookahead
      #if lookahead.nestedExpansion??
${BuildLookaheadRoutine(lookahead)}
     #endif
   #endlist
   #list grammar.allLookBehinds as lookBehind
${BuildLookBehindRoutine(lookBehind)}
   #endlist
   #list grammar.parserProductions as production
${BuildProductionLookaheadMethod(production)}
   #endlist
#endmacro

#macro BuildPredicateRoutine expansion
  #var lookaheadAmount = expansion.lookaheadAmount
  #if lookaheadAmount = 2147483647
     #set lookaheadAmount = "UNLIMITED"
  #endif
    // BuildPredicateRoutine: expansion at ${expansion.location}
    private bool ${expansion.predicateMethodName}() {
        _remainingLookahead = ${lookaheadAmount};
        currentLookaheadToken = LastConsumedToken;
        var scanToEnd = false;
        try {
${BuildPredicateCode(expansion)}
      #if !expansion.hasSeparateSyntacticLookahead && expansion.lookaheadAmount != 0
${BuildScanCode(expansion)}
      #endif
            return true;
        }
        finally {
            _lookaheadRoutineNesting = 0;
            currentLookaheadToken = null;
            _hitFailure = false;
        }
    }

#endmacro

#macro BuildScanRoutine expansion
#-- # DBG > BuildScanRoutine
 #if !expansion.singleTokenLookahead
// scanahead routine for expansion at:
// ${expansion.location}
// BuildScanRoutine macro
#set newVarIndex = 0 in CU
private bool ${expansion.scanRoutineName}(bool scanToEnd) {
    #if expansion.hasScanLimit
       var prevPassedPredicateThreshold = _passedPredicateThreshold;
       _passedPredicateThreshold = -1;
    #else
       bool reachedScanCode = false;
       var passedPredicateThreshold = (int) _remainingLookahead - ${expansion.lookaheadAmount};
    #endif
    try {
       _lookaheadRoutineNesting++;
       ${BuildPredicateCode(expansion)}
      #if !expansion.hasScanLimit
       reachedScanCode = true;
      #endif
       ${BuildScanCode(expansion)}
    }
    finally {
       _lookaheadRoutineNesting--;
   #if expansion.hasScanLimit
       if (_remainingLookahead <= _passedPredicateThreshold) {
         _passedPredicate = true;
         _passedPredicateThreshold = prevPassedPredicateThreshold;
       }
   #else
       if (reachedScanCode && _remainingLookahead <= passedPredicateThreshold) {
         _passedPredicate = true;
       }
   #endif
    }
    _passedPredicate = false;
    return true;
}

 #endif
#-- # DBG < BuildScanRoutine
#endmacro

#macro BuildAssertionRoutine expansion
// scanahead routine for assertion at:
// ${expansion.parent.location}
// BuildAssertionRoutine macro
private bool ${expansion.scanRoutineName}() {
#var storeCurrentLookaheadVar = CU.newVarName("currentLookahead"),
      storeRemainingLookahead = CU.newVarName("remainingLookahead")
#set newVarIndex = 0 in CU
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
#endmacro

#-- Build the code for checking semantic lookahead, lookbehind, and/or syntactic lookahead
#macro BuildPredicateCode expansion
#-- # DBG > BuildPredicateCode
#if expansion.hasSemanticLookahead && (expansion.lookahead.semanticLookaheadNested || expansion.containingProduction.onlyForLookahead)
if (!(${globals::translateExpression(expansion.semanticLookahead)})) {
    return false;
}
#endif
#if expansion.hasLookBehind
if ([#if !expansion.lookBehind.negated]![/#if]${expansion.lookBehind.routineName}()) {
    return false;
}
#endif
#if expansion.hasSeparateSyntacticLookahead
if (_remainingLookahead <= 0) {
    _passedPredicate = true;
    return !_hitFailure;
}
if ([#if !expansion.lookahead.negated]![/#if]${expansion.lookaheadExpansion.scanRoutineName}(true)) {
    return false;
}
#endif
  #if expansion.lookaheadAmount == 0
    _passedPredicate = true;
  #endif
#-- # DBG < BuildPredicateCode
#endmacro

[#--
   Generates the routine for an explicit lookahead
   that is used in a nested lookahead.
 --]
#macro BuildLookaheadRoutine lookahead
#-- # DBG > BuildLookaheadRoutine
#if lookahead.nestedExpansion??
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
#endif
#-- # DBG < BuildLookaheadRoutine
#endmacro

#macro BuildLookBehindRoutine lookBehind
#-- # DBG > BuildLookBehindRoutine
private bool ${lookBehind.routineName}() {
    var stackIterator = new ${lookBehind.backward?string("BackwardIterator", "ForwardIterator")}<NonTerminalCall>(ParsingStack, _lookaheadStack);
    NonTerminalCall ntc;
#list lookBehind.path as element
  #var elementNegated = (element[0] == "~")
  [#if elementNegated][#set element = element?substring(1)][/#if]
  #if element = "."
    if (!stackIterator.HasNext()) {
        return false;
    }
    stackIterator.Next();
  #elif element = "..."
    #if element_index = lookBehind.path?size-1
      #if lookBehind.hasEndingSlash
    return !stackIterator.HasNext();
      #else
    return true;
      #endif
    #else
      #var nextElement = lookBehind.path[element_index + 1]
      #var nextElementNegated = (nextElement[0] == "~")
      [#if nextElementNegated][#set nextElement = nextElement?substring(1)][/#if]
    while (stackIterator.HasNext()) {
        ntc = stackIterator.Next();
      #var equalityOp = nextElementNegated?string("!=", "==")
        if (ntc.ProductionName ${equalityOp} "${nextElement}") {
            stackIterator.Previous();
            break;
        }
        if (!stackIterator.HasNext()) {
            return false;
        }
    }
    #endif
  #else
    if (!stackIterator.HasNext()) {
        return false;
    }
    ntc = stackIterator.Next();
     #var equalityOp = elementNegated?string("==", "!=")
    if (ntc.ProductionName ${equalityOp} "${element}") {
        return false;
    }
  #endif
#endlist
#if lookBehind.hasEndingSlash
    return !stackIterator.HasNext();
#else
    return true;
#endif
}
#-- # DBG < BuildLookBehindRoutine
#endmacro

#macro BuildProductionLookaheadMethod production
#--     # DBG > BuildProductionLookaheadMethod
        // BuildProductionLookaheadMethod macro
        private bool ${production.lookaheadMethodName}(bool scanToEnd) {
${BuildScanCode(production.expansion)}
            return true;
        }

#--     # DBG < BuildProductionLookaheadMethod
#endmacro

[#--
   Macro to build the lookahead code for an expansion.
   This macro just delegates to the various sub-macros
   based on the Expansion's class name.
--]
#macro BuildScanCode expansion
#-- # DBG > BuildScanCode ${expansion.simpleName}
  #var classname = expansion.simpleName
  #if classname != "ExpansionSequence" && classname != "ExpansionWithParentheses"
if (_hitFailure) return false;
if (_remainingLookahead <= 0) {
    return true;
}
// Lookahead Code for ${classname} specified at ${expansion.location}
  #endif
  [@CU.HandleLexicalStateChange expansion, true]
   #if classname = "ExpansionWithParentheses"
      [@BuildScanCode expansion.nestedExpansion /]
   #elif expansion.singleTokenLookahead
${ScanSingleToken(expansion)}
   #elif classname = "Assertion" && expansion.appliesInLookahead
${ScanCodeAssertion(expansion)}
   #elif classname = "Failure"
${ScanCodeError(expansion)}
   #elif classname = "TokenTypeActivation"
${ScanCodeTokenActivation(expansion)}
   #elif classname = "ExpansionSequence"
${ScanCodeSequence(expansion)}
   #elif classname = "ZeroOrOne"
${ScanCodeZeroOrOne(expansion)}
   #elif classname = "ZeroOrMore"
${ScanCodeZeroOrMore(expansion)}
   #elif classname = "OneOrMore"
${ScanCodeOneOrMore(expansion)}
   #elif classname = "NonTerminal"
      [@ScanCodeNonTerminal expansion /]
   #elif classname = "TryBlock" || classname = "AttemptBlock"
      [@BuildScanCode expansion.nestedExpansion /]
   #elif classname = "ExpansionChoice"
${ScanCodeChoice(expansion)}
   #elif classname = "CodeBlock"
      #if expansion.appliesInLookahead || expansion.insideLookahead || expansion.containingProduction.onlyForLookahead
${globals::translateCodeBlock(expansion, 12)}
      #endif
   #elif classname = "RawCode"
      #if expansion.appliesInLookahead || expansion.insideLookahead || expansion.containingProduction.onlyForLookahead
         ${expansion}
      #endif
   #endif
  [/@CU.HandleLexicalStateChange]
#-- # DBG < BuildScanCode ${expansion.simpleName}
#endmacro

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
#macro ScanCodeSequence sequence
#-- # DBG > ScanCodeSequence
   #list sequence.units as sub
       [@BuildScanCode sub /]
       #if sub.scanLimit
         if (!scanToEnd && _lookaheadStack.Count <= 1) {
            if (_lookaheadRoutineNesting == 0) {
              _remainingLookahead = ${sub.scanLimitPlus};
            }
            else if (_lookaheadStack.Count == 1) {
               _passedPredicateThreshold = (int) _remainingLookahead[#if sub.scanLimitPlus > 0] - ${sub.scanLimitPlus}[/#if];
            }
         }
       #endif
   #endlist
#-- # DBG < ScanCodeSequence
#endmacro

[#--
  Generates the lookahead code for a non-terminal.
  It (trivially) just delegates to the code for
  checking the production's nested expansion
--]
#macro ScanCodeNonTerminal nt
// NonTerminal ${nt.name} at ${nt.location}
PushOntoLookaheadStack("${nt.containingProduction.name}", "${nt.inputSource?j_string}", ${nt.beginLine}, ${nt.beginColumn});
#var prevScanToEndVarName = "prevScanToEnd" + CU.newID()
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
#endmacro

#macro ScanSingleToken expansion
#var firstSet = expansion.firstSet.tokenNames
#-- # DBG > ScanSingleToken
#if firstSet?size = 1
if (!ScanToken(${CU.TT}${firstSet[0]})) {
    return false;
}
#else
if (!ScanToken(${expansion.firstSetVarName})) {
    return false;
}
#endif
[#-- # DBG < ScanSingleToken --]
#endmacro

#macro ScanCodeAssertion assertion
#-- # DBG > ScanCodeAssertion
#if assertion.assertionExpression??
if (!(${globals::translateExpression(assertion.assertionExpression)})) {
    _hitFailure = true;
    return false;
}
#endif
#if assertion.expansion??
if ([#if !assertion.expansionNegated]![/#if]${assertion.expansion.scanRoutineName}()) {
    _hitFailure = true;
    return false;
}
#endif
#-- # DBG < ScanCodeAssertion
#endmacro

#macro ScanCodeError expansion
#-- # DBG > ScanCodeError
_hitFailure = true;
return false;
#-- # DBG < ScanCodeError
#endmacro

#macro ScanCodeTokenActivation activation
#-- # DBG > ScanCodeTokenActivation
[#if activation.deactivate]Dea[#else]A[/#if]ctivateTokenTypes(
#list activation.tokenNames as name
    ${CU.TT}${name}[#if name_has_next],[/#if]
#endlist
)
#-- # DBG < ScanCodeTokenActivation
#endmacro

#macro ScanCodeChoice choice
#-- # DBG > ScanCodeChoice
var ${CU.newVarName("token")} = currentLookaheadToken;
var remainingLookahead${CU.newVarIndex} = _remainingLookahead;
var hitFailure${CU.newVarIndex} = _hitFailure;
var passedPredicate${CU.newVarIndex} = _passedPredicate;
try {
  #list choice.choices as subseq
    _passedPredicate = false;
    if (!${CheckExpansion(subseq)}) {
        currentLookaheadToken = token${CU.newVarIndex};
        _remainingLookahead = remainingLookahead${CU.newVarIndex};
        _hitFailure = hitFailure${CU.newVarIndex};
     #if !subseq_has_next
        return false;
     #else
        if (_passedPredicate && !_legacyGlitchyLookahead) return false;
     #endif
  #endlist
  [#list choice.choices as unused] } [/#list]
}
finally {
    _passedPredicate = passedPredicate${CU.newVarIndex};
}
#-- # DBG < ScanCodeChoice
#endmacro

#macro ScanCodeZeroOrOne zoo
#-- # DBG > ScanCodeZeroOrOne
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
#-- # DBG < ScanCodeZeroOrOne
#endmacro

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
#macro ScanCodeZeroOrMore zom
#-- # DBG > ScanCodeZeroOrMore
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
#-- # DBG < ScanCodeZeroOrMore
#endmacro

[#--
   Generates lookahead code for a OneOrMore construct
   It generates the code for checking a single occurrence
   and then the same code as a ZeroOrMore
--]
#macro ScanCodeOneOrMore oom
#-- # DBG > ScanCodeOneOrMore
[#--
if (!(${CheckExpansion(oom.nestedExpansion)})) {
    return false;
}--]
[@BuildScanCode oom.nestedExpansion /]
[@ScanCodeZeroOrMore oom /]
#-- # DBG < ScanCodeOneOrMore
#endmacro

#macro CheckExpansion expansion
   #if expansion.singleTokenLookahead
     #if expansion.firstSet.tokenNames?size = 1
      ScanToken(${CU.TT}${expansion.firstSet.tokenNames[0]})
     #else
      ScanToken(${expansion.firstSetVarName})
     #endif
   #else
      ${expansion.scanRoutineName}(false)
   #endif
#endmacro


