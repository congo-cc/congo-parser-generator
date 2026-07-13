[#-- This template generates the various lookahead/predicate routines --]

#import "CommonUtils.inc.ctl" as CU

#var UNLIMITED = 2147483647
#var MULTIPLE_LEXICAL_STATE_HANDLING = lexerData::numLexicalStates > 1
#set MULTIPLE_LEXICAL_STATE_HANDLING = false

#var repetitionIndex = 0

#function lhReturnFalse cardinalitiesVar parentCardVar
   #if cardinalitiesVar?? && (cardinalitiesVar?length > 0)
      #if parentCardVar?? && (parentCardVar?length > 0)
         #return "return " + parentCardVar + ".Commit(false);"
      #else
         #return "return " + cardinalitiesVar + ".Commit(false);"
      #endif
   #else
      #return "return false;"
   #endif
#endfunction

#function lhReturnTrue cardinalitiesVar parentCardVar
   #if cardinalitiesVar?? && (cardinalitiesVar?length > 0)
      #if parentCardVar?? && (parentCardVar?length > 0)
         #return "return " + parentCardVar + ".Commit(true);"
      #else
         #return "return " + cardinalitiesVar + ".Commit(true);"
      #endif
   #else
      #return "return true;"
   #endif
#endfunction

#function lhReturnCommit retExpr cardinalitiesVar parentCardVar
   #if parentCardVar?? && (parentCardVar?length > 0)
      #return "return " + parentCardVar + ".Commit(" + retExpr + ");"
   #elif cardinalitiesVar?? && (cardinalitiesVar?length > 0)
      #return "return " + cardinalitiesVar + ".Commit(" + retExpr + ");"
   #else
      #return "return " + retExpr + ";"
   #endif
#endfunction


#macro Generate
    [@firstSetVars /]
#if settings::faultTolerant
    [@followSetVars /]
#endif
    #if grammar::choicePointExpansions.size() != 0
       [@BuildLookaheads /]
    #endif
#endmacro


#macro firstSetVars
    // ==================================================================
    // EnumSets that represent the various expansions' first set (i.e. the set of tokens with which the expansion can begin)
    // ==================================================================
    #list grammar::expansionsForFirstSet as expansion
          [@CU::firstSetVar expansion/]
    #endlist
#endmacro

#macro finalSetVars
    // ==================================================================
    // EnumSets that represent the various expansions' final set (i.e. the set of tokens with which the expansion can end)
    // ==================================================================
    #list grammar::expansionsForFinalSet as expansion
          [@finalSetVar expansion/]
    #endlist
#endmacro


#macro followSetVars
    // ==================================================================
    // EnumSets that represent the various expansions' follow set (i.e. the set of tokens that can immediately follow this)
    // ==================================================================
    #list grammar::expansionsForFollowSet as expansion
          [@CU::followSetVar expansion/]
    #endlist
#endmacro

#macro BuildLookaheads
#set repetitionIndex = 0
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

#if lexerData::hasContextualTokens
    private bool IsContextualToken(TokenType type) {
      return
         #list lexerData::contextualTokens as ctok
           type == TokenType.${ctok::label}
            ${ctok_has_next ?: "||"}
         #endlist
        ;
    }

    private bool IsIgnoreCase(TokenType type) {
        #if !lexerData::literalsThatDifferInCaseFromDefault
           return ${settings::ignoreCase ?: "true":"false"};
        #else
        return ${settings::ignoreCase ?: "!"}
        (
            #list lexerData::literalsThatDifferInCaseFromDefault as literal
                type == TokenType.${literal::label}
                ${literal_has_next ?: "||"}
            #endlist
        );
        #endif
    }

    private string GetLiteralString(TokenType type) {
        switch(type) {
            #list lexerData::regularExpressions as regexp
               #if regexp::literalString??
                  case TokenType.${regexp::label} : return "${regexp::literalString?j_string}";
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
   #list grammar::choicePointExpansions as expansion
      #if expansion::parent::class::simpleName != "BNFProduction"
${BuildScanRoutine(expansion)}
      #endif
   #endlist
   #list grammar::assertionExpansions as expansion
      ${BuildAssertionRoutine(expansion)}
   #endlist
   #list grammar::expansionsNeedingPredicate as expansion
${BuildPredicateRoutine(expansion)}
   #endlist
   #list grammar::allLookaheads as lookahead
      #if lookahead::nestedExpansion??
${BuildLookaheadRoutine(lookahead)}
     #endif
   #endlist
   #list grammar::allLookBehinds as lookBehind
${BuildLookBehindRoutine(lookBehind)}
   #endlist
   #list grammar::parserProductions as production
${BuildProductionLookaheadMethod(production)}
   #endlist
#endmacro

#macro BuildPredicateRoutine expansion
  #var lookaheadAmount = expansion::lookaheadAmount
  #if lookaheadAmount = 2147483647
     #set lookaheadAmount = "UNLIMITED"
  #endif
  #var cardinalitiesVar = ""
  #if expansion::cardinalityConstrained
    #set cardinalitiesVar = "cardinalities"
  #endif
    // BuildPredicateRoutine: expansion at ${expansion::location}
    private bool ${expansion::predicateMethodName}([#if expansion::cardinalityConstrained]RepetitionCardinality cardinalities[/#if]) {
        _remainingLookahead = ${lookaheadAmount};
        currentLookaheadToken = LastConsumedToken;
        var scanToEnd = false;
        try {
${BuildPredicateCode(expansion, cardinalitiesVar)}
      #if !expansion::hasSeparateSyntacticLookahead && expansion::lookaheadAmount != 0
        #if expansion::cardinalityConstrained
${BuildScanCode(expansion, cardinalitiesVar, "")}
        #else
${BuildScanCode(expansion, "", "")}
        #endif
      #endif
            ${lhReturnTrue(cardinalitiesVar, "")}
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
 #if !expansion::singleTokenLookahead
// scanahead routine for expansion at:
// ${expansion::location}
// BuildScanRoutine macro
#set newVarIndex = 0 in CU
  #var cardinalitiesVar = ""
  #if expansion::cardinalityConstrained
    #set cardinalitiesVar = "cardinalities"
  #endif
private bool ${expansion::scanRoutineName}(bool scanToEnd[#if expansion::cardinalityConstrained], RepetitionCardinality cardinalities[/#if]) {
    #if expansion::hasScanLimit
       var prevPassedPredicateThreshold = _passedPredicateThreshold;
       _passedPredicateThreshold = -1;
    #else
       bool reachedScanCode = false;
       var passedPredicateThreshold = (int) _remainingLookahead - ${expansion::lookaheadAmount};
    #endif
    try {
       _lookaheadRoutineNesting++;
       ${BuildPredicateCode(expansion, cardinalitiesVar)}
      #if !expansion::hasScanLimit
       reachedScanCode = true;
      #endif
      #if expansion::cardinalityConstrained
       ${BuildScanCode(expansion, cardinalitiesVar, "")}
      #else
       ${BuildScanCode(expansion, "", "")}
      #endif
    }
    finally {
       _lookaheadRoutineNesting--;
   #if expansion::hasScanLimit
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
    ${lhReturnTrue(cardinalitiesVar, "")}
}

 #endif
#-- # DBG < BuildScanRoutine
#endmacro

#macro BuildAssertionRoutine expansion
// scanahead routine for assertion at:
// ${expansion::parent::location}
// BuildAssertionRoutine macro
private bool ${expansion::scanRoutineName}() {
#var storeCurrentLookaheadVar = CU::newVarName("currentLookahead"),
      storeRemainingLookahead = CU::newVarName("remainingLookahead")
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
${BuildScanCode(expansion, "", "")}
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
#macro BuildPredicateCode expansion cardinalitiesVar
#-- # DBG > BuildPredicateCode
#if expansion::hasSemanticLookahead && (expansion::lookahead::semanticLookaheadNested || expansion::containingProduction::onlyForLookahead)
if (!(${globals.translateExpression(expansion::semanticLookahead)})) {
    ${lhReturnFalse(cardinalitiesVar, "")}
}
#endif
#if expansion::hasLookBehind
if ([#if !expansion::lookBehind::negated]![/#if]${expansion::lookBehind::routineName}()) {
    ${lhReturnFalse(cardinalitiesVar, "")}
}
#endif
#if expansion::hasSeparateSyntacticLookahead
if (_remainingLookahead <= 0) {
    _passedPredicate = true;
    ${lhReturnCommit("!_hitFailure", cardinalitiesVar, "")}
}
if ([#if !expansion::lookahead::negated]![/#if]${expansion::lookaheadExpansion::scanRoutineName}(true)) {
    ${lhReturnFalse(cardinalitiesVar, "")}
}
#endif
  #if expansion::lookaheadAmount == 0
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
#if lookahead::nestedExpansion??
// lookahead routine for lookahead at:
// ${lookahead::location}
  #var cardinalitiesVar = ""
  #if lookahead::nestedExpansion::cardinalityConstrained
    #set cardinalitiesVar = "cardinalities"
  #endif
private bool ${lookahead::nestedExpansion::scanRoutineName}(bool scanToEnd[#if lookahead::nestedExpansion::cardinalityConstrained], RepetitionCardinality cardinalities[/#if]) {
    var prevRemainingLookahead = _remainingLookahead;
    var prevHitFailure = _hitFailure;
    var prevScanaheadToken = currentLookaheadToken;
    try {
        _lookaheadRoutineNesting++;
  #if lookahead::nestedExpansion::cardinalityConstrained
${BuildScanCode(lookahead::nestedExpansion, cardinalitiesVar, "")}
  #else
${BuildScanCode(lookahead::nestedExpansion, "", "")}
  #endif
        ${lhReturnCommit("!_hitFailure", cardinalitiesVar, "")}
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
private bool ${lookBehind::routineName}() {
    var stackIterator = new ${lookBehind::backward ?: "BackwardIterator" : "ForwardIterator"}<NonTerminalCall>(ParsingStack, _lookaheadStack);
    NonTerminalCall ntc;
#list lookBehind::path as element
  #var elementNegated = (element[0] == "~")
  [#if elementNegated][#set element = element.substring(1)][/#if]
  #if element = "."
    if (!stackIterator.HasNext()) {
        return false;
    }
    stackIterator.Next();
  #elif element = "..."
    #if element_index = lookBehind::path.size()-1
      #if lookBehind::hasEndingSlash
    return !stackIterator.HasNext();
      #else
    return true;
      #endif
    #else
      #var nextElement = lookBehind::path[element_index + 1]
      #var nextElementNegated = (nextElement[0] == "~")
      [#if nextElementNegated][#set nextElement = nextElement.substring(1)][/#if]
    while (stackIterator.HasNext()) {
        ntc = stackIterator.Next();
      #var equalityOp = nextElementNegated ?: "!=" : "=="
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
     #var equalityOp = elementNegated ?: "==" : "!="
    if (ntc.ProductionName ${equalityOp} "${element}") {
        return false;
    }
  #endif
#endlist
#if lookBehind::hasEndingSlash
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
        private bool ${production::lookaheadMethodName}(bool scanToEnd) {
${BuildScanCode(production::expansion, "", "")}
            return true;
        }

#--     # DBG < BuildProductionLookaheadMethod
#endmacro

[#--
   Macro to build the lookahead code for an expansion.
   This macro just delegates to the various sub-macros
   based on the Expansion's class name.
--]
#macro BuildScanCode expansion cardVar parentCardVar
#-- # DBG > BuildScanCode ${expansion.simpleName}
  #var classname = expansion::simpleName
  #if classname != "ExpansionSequence" && classname != "ExpansionWithParentheses"
if (_hitFailure) { ${lhReturnFalse(cardVar, parentCardVar)} }
if (_remainingLookahead <= 0) {
    ${lhReturnTrue(cardVar, parentCardVar)}
}
// Lookahead Code for ${classname} specified at ${expansion::location}
  #endif
  [@CU::HandleLexicalStateChange expansion, true, cardVar!""]
   #if classname = "ExpansionWithParentheses"
      [@BuildScanCode expansion::nestedExpansion, cardVar, parentCardVar /]
   #elif expansion::singleTokenLookahead
${ScanSingleToken(expansion, cardVar)}
   #elif classname = "Assertion" && expansion::appliesInLookahead
${ScanCodeAssertion(expansion, cardVar, parentCardVar)}
   #elif classname = "Failure"
${ScanCodeError(expansion, cardVar)}
   #elif classname = "TokenTypeActivation"
${ScanCodeTokenActivation(expansion)}
   #elif classname = "ExpansionSequence"
${ScanCodeSequence(expansion, cardVar, parentCardVar)}
   #elif classname = "ZeroOrOne"
${ScanCodeZeroOrOne(expansion, cardVar, parentCardVar)}
   #elif classname = "ZeroOrMore"
${ScanCodeZeroOrMore(expansion, cardVar, parentCardVar)}
   #elif classname = "OneOrMore"
${ScanCodeOneOrMore(expansion, cardVar)}
   #elif classname = "NonTerminal"
      [@ScanCodeNonTerminal expansion, cardVar /]
   #elif classname = "TryBlock" || classname = "AttemptBlock"
      [@BuildScanCode expansion::nestedExpansion, cardVar, parentCardVar /]
   #elif classname = "ExpansionChoice"
${ScanCodeChoice(expansion, cardVar, parentCardVar)}
   #elif classname = "CodeBlock"
      #if expansion::appliesInLookahead || expansion::insideLookahead || expansion::containingProduction::onlyForLookahead
${globals.translateCodeBlock(expansion, 12)}
      #endif
   #elif classname = "RawCode"
      #if expansion::appliesInLookahead || expansion::insideLookahead || expansion::containingProduction::onlyForLookahead
         ${expansion}
      #endif
   #endif
  [/@CU.HandleLexicalStateChange]
#-- # DBG < BuildScanCode ${expansion.simpleName}
#endmacro

[#--
   Generates the lookahead code for an ExpansionSequence.
--]
#macro ScanCodeSequence sequence cardVar parentCardVar
#-- # DBG > ScanCodeSequence
   #list sequence::units as sub
       [@BuildScanCode sub, cardVar, parentCardVar /]
       #if sub::scanLimit
         if (!scanToEnd && _lookaheadStack.Count <= 1) {
            if (_lookaheadRoutineNesting == 0) {
              _remainingLookahead = ${sub::scanLimitPlus};
            }
            else if (_lookaheadStack.Count == 1) {
               _passedPredicateThreshold = (int) _remainingLookahead[#if sub::scanLimitPlus > 0] - ${sub::scanLimitPlus}[/#if];
            }
         }
       #endif
   #endlist
#-- # DBG < ScanCodeSequence
#endmacro

[#macro ScanCodeNonTerminal nt cardinalitiesVar]
// NonTerminal ${nt::name} at ${nt::location}
PushOntoLookaheadStack("${nt::containingProduction::name}", "${nt::inputSource?j_string}", ${nt::beginLine}, ${nt::beginColumn});
#var prevScanToEndVarName = "prevScanToEnd" + CU::newID()
bool ${prevScanToEndVarName} = ScanToEnd;
_currentLookaheadProduction = "${nt::production::name}";
try {
    if (!${nt::production::lookaheadMethodName}(${CU::bool(nt::scanToEnd)})) {
        ${lhReturnFalse(cardinalitiesVar, "")}
    }
}
finally {
    PopLookaheadStack();
}
#endmacro

#macro ScanSingleToken expansion cardinalitiesVar
#var firstSet = expansion::firstSet::tokenNames
#-- # DBG > ScanSingleToken
#if firstSet.size() = 1
if (!ScanToken(${CU::TT}${firstSet[0]})) {
    ${lhReturnFalse(cardinalitiesVar, "")}
}
#else
if (!ScanToken(${expansion::firstSetVarName})) {
    ${lhReturnFalse(cardinalitiesVar, "")}
}
#endif
[#-- # DBG < ScanSingleToken --]
#endmacro

#macro ScanCodeAssertion assertion cardinalitiesVar parentCardVar
#-- # DBG > ScanCodeAssertion
#if assertion::lookBehind??
if ([#if !assertion::lookBehind::negated]![/#if]${assertion::lookBehind::routineName}()) {
    _hitFailure = true;
    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
}
#elif assertion::assertionExpression??
if (!(${globals.translateExpression(assertion::assertionExpression)})) {
    _hitFailure = true;
    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
}
#elif assertion::rawCode?? && !assertion::rawCode::wrongLanguageIgnore
if (!${assertion::rawCode}) {
    _hitFailure = true;
    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
}
#endif
#if assertion::expansion??
if ([#if !assertion::expansionNegated]![/#if]${assertion::expansion::scanRoutineName}()) {
    _hitFailure = true;
    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
}
#endif
#if assertion::cardinalityConstraint?? && cardinalitiesVar?? && (cardinalitiesVar?length > 0)
    if (!${cardinalitiesVar}.Choose(${assertion::assertionIndex}, true)) {
        _hitFailure = true;
        ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
    }
#endif
#-- # DBG < ScanCodeAssertion
#endmacro

#macro ScanCodeError expansion cardinalitiesVar
#-- # DBG > ScanCodeError
_hitFailure = true;
${lhReturnFalse(cardinalitiesVar, "")}
#-- # DBG < ScanCodeError
#endmacro

#macro ScanCodeTokenActivation activation
#-- # DBG > ScanCodeTokenActivation
[#if activation::deactivate]Dea[#else]A[/#if]ctivateTokenTypes(
#list activation::tokenNames as name
    ${CU::TT}${name}[#if name_has_next],[/#if]
#endlist
)
#-- # DBG < ScanCodeTokenActivation
#endmacro

#macro ScanCodeChoice choice cardinalitiesVar parentCardVar
#-- # DBG > ScanCodeChoice
var ${CU::newVarName("token")} = currentLookaheadToken;
var remainingLookahead${CU::newVarIndex} = _remainingLookahead;
var hitFailure${CU::newVarIndex} = _hitFailure;
var passedPredicate${CU::newVarIndex} = _passedPredicate;
try {
  #list choice::choices as subseq
    _passedPredicate = false;
    if (!${CheckExpansion(subseq, cardinalitiesVar, parentCardVar)}) {
        currentLookaheadToken = token${CU::newVarIndex};
        _remainingLookahead = remainingLookahead${CU::newVarIndex};
        _hitFailure = hitFailure${CU::newVarIndex};
     #if !subseq_has_next
        ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
     #else
        if (_passedPredicate && !_legacyGlitchyLookahead) { ${lhReturnFalse(cardinalitiesVar, parentCardVar)} }
     #endif
  #endlist
  [#list choice::choices as unused] } [/#list]
}
finally {
    _passedPredicate = passedPredicate${CU::newVarIndex};
}
#-- # DBG < ScanCodeChoice
#endmacro

#macro ScanCodeZeroOrOne zoo cardVar parentCardVar
#-- # DBG > ScanCodeZeroOrOne
var ${CU::newVarName("token")} = currentLookaheadToken;
var passedPredicate${CU::newVarIndex} = _passedPredicate;
_passedPredicate = false;
try {
    if (!${CheckExpansion(zoo::nestedExpansion, cardVar, parentCardVar)}) {
        if (_passedPredicate && !_legacyGlitchyLookahead) { ${lhReturnFalse(cardVar, "")} }
        currentLookaheadToken = token${CU::newVarIndex};
        _hitFailure = false;
    }
}
finally {
    _passedPredicate = passedPredicate${CU::newVarIndex};
}
#-- # DBG < ScanCodeZeroOrOne
#endmacro

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
#macro ScanCodeZeroOrMore zom cardVar parentCardVar
#-- # DBG > ScanCodeZeroOrMore
  #var zomCardVar = cardVar!""
  #if zom::cardinalityContainer && (!cardVar?? || cardVar == "")
    #set zomCardVar = "cardinality" + repetitionIndex
    #set repetitionIndex = repetitionIndex + 1
    RepetitionCardinality ${zomCardVar} = new RepetitionCardinality(${CU::BuildCardinalities(zom::cardinalityConstraints)});
  #endif
var ${CU::newVarName("passedPredicate")} = _passedPredicate;
try {
    while (_remainingLookahead > 0 && !_hitFailure) {
        var token${CU::newVarIndex} = currentLookaheadToken;
        _passedPredicate = false;
        if (!${CheckExpansion(zom::nestedExpansion, zomCardVar, parentCardVar)}) {
            if (_passedPredicate && !_legacyGlitchyLookahead) { ${lhReturnFalse(parentCardVar, "")} }
            currentLookaheadToken = token${CU::newVarIndex};
            break;
        }
  #if zom::cardinalityContainer
        ${zomCardVar}.CommitIteration(false);
  #endif
    }
  #if zom::minCardinalityConstrained && zom::cardinalityContainer
    if (!${zomCardVar}.CheckCardinality(true)) { ${lhReturnFalse(parentCardVar, "")} }
  #endif
}
finally {
    _passedPredicate = passedPredicate${CU::newVarIndex};
}
_hitFailure = false;
#-- # DBG < ScanCodeZeroOrMore
#endmacro

[#--
   Generates lookahead code for a OneOrMore construct
--]
#macro ScanCodeOneOrMore oom cardinalitiesVar
#-- # DBG > ScanCodeOneOrMore
  #var oomCardVar = ""
  #if oom::cardinalityContainer
    #set oomCardVar = "cardinality" + repetitionIndex
    #set repetitionIndex = repetitionIndex + 1
    RepetitionCardinality ${oomCardVar} = new RepetitionCardinality(${CU::BuildCardinalities(oom::cardinalityConstraints)});
  #endif
[@BuildScanCode oom::nestedExpansion, oomCardVar, cardinalitiesVar /]
#if oom::cardinalityContainer
${oomCardVar}.CommitIteration(false);
#endif
[@ScanCodeZeroOrMore oom, oomCardVar, cardinalitiesVar /]
#-- # DBG < ScanCodeOneOrMore
#endmacro

#macro CheckExpansion expansion cardinalitiesVar parentCardVar
   #if expansion::singleTokenLookahead
     #if expansion::firstSet::tokenNames.size() = 1
      ScanToken(${CU::TT}${expansion::firstSet::tokenNames[0]})
     #else
      ScanToken(${expansion::firstSetVarName})
     #endif
   #else
     #if grammar::assertionExpansions?? && grammar::assertionExpansions.contains(expansion)
      ${expansion::scanRoutineName}()[#t]
     #else
      ${expansion::scanRoutineName}(false[#if expansion::cardinalityConstrained && cardinalitiesVar?? && (cardinalitiesVar?length > 0)], ${cardinalitiesVar}[/#if])[#t]
     #endif
   #endif
#endmacro


