[#-- This template generates the various lookahead/predicate routines --]

#import "common_utils.inc.ctl" as CU

#var UNLIMITED = 2147483647
[#-- var MULTIPLE_LEXICAL_STATE_HANDLING = lexerData.numLexicalStates > 1 --]
[#var MULTIPLE_LEXICAL_STATE_HANDLING = false]
[#var repetitionIndex = 0]

[#-- Lookahead cardinality: mirror Java LookaheadRoutines returnFalse/returnTrue/return(commit) --]
[#function lhReturnFalse cardinalitiesVar parentCardVar]
  [#if cardinalitiesVar?? && (cardinalitiesVar.length() > 0)]
    [#if parentCardVar?? && (parentCardVar.length() > 0)]
      [#return "return " + parentCardVar + ".commit(False)"/]
    [#else]
      [#return "return " + cardinalitiesVar + ".commit(False)"/]
    [/#if]
  [#else]
    [#return "return False"/]
  [/#if]
[/#function]
[#function lhReturnTrue cardinalitiesVar parentCardVar]
  [#if cardinalitiesVar?? && (cardinalitiesVar.length() > 0)]
    [#if parentCardVar?? && (parentCardVar.length() > 0)]
      [#return "return " + parentCardVar + ".commit(True)"/]
    [#else]
      [#return "return " + cardinalitiesVar + ".commit(True)"/]
    [/#if]
  [#else]
    [#return "return True"/]
  [/#if]
[/#function]
[#function lhReturnCommit retExpr cardinalitiesVar parentCardVar]
  [#if parentCardVar?? && (parentCardVar.length() > 0)]
    [#return "return " + parentCardVar + ".commit(" + retExpr + ")"/]
  [#elif cardinalitiesVar?? && (cardinalitiesVar.length() > 0)]
    [#return "return " + cardinalitiesVar + ".commit(" + retExpr + ")"/]
  [#else]
    [#return "return " + retExpr/]
  [/#if]
[/#function]


#macro Generate
    [@firstSetVars /]
  #if settings::faultTolerant
    [@followSetVars /]
  #endif
  #if grammar::choicePointExpansions.size() != 0
    [@BuildLookaheads 4 /]
  #endif
#endmacro


#macro firstSetVars
    # ==================================================================
    # EnumSets that represent the various expansions' first set (i.e. the set of tokens with which the expansion can begin)
    # ==================================================================
  #list grammar::expansionsForFirstSet as expansion
          [@CU::firstSetVar expansion/]
  #endlist
#endmacro

[#--
[#macro finalSetVars]
    # ==================================================================
    # EnumSets that represent the various expansions' final set (i.e. the set of tokens with which the expansion can end)
    # ==================================================================
    [#list grammar.expansionsForFinalSet as expansion]
          [@finalSetVar expansion/]
    [/#list]
[/#macro]
--]

#macro followSetVars
    # ==================================================================
    # EnumSets that represent the various expansions' follow set (i.e. the set of tokens that can immediately follow this)
    # ==================================================================
  #list grammar::expansionsForFollowSet as expansion
          [@CU::followSetVar expansion/]
  #endlist
#endmacro

[#--
  scan_token tends to be a big source of time spent in the parser,
  so we try to optimize it into two versions if optimize_scan_token is
  true - one for one type and one for many.
 --]
#var optimize_scan_token = true

[#macro BuildLookaheads indent]
  #if !optimize_scan_token
    def scan_token(self, expected_type_or_types):
        is_set = isinstance(expected_type_or_types, (set, frozenset))
        peeked_token = self.next_token(self.current_lookahead_token)
        tt = peeked_token.type
        if not is_set:
            no_match = tt != expected_type_or_types
        else:
            no_match = tt not in expected_type_or_types
        if no_match:
            return False
        self.remaining_lookahead -= 1
        self.current_lookahead_token = peeked_token
        return True

  #else
    def scan_token_one(self, expected_type):
        peeked_token = self.next_token(self.current_lookahead_token)
        if not self.type_matches(expected_type, peeked_token):
            return False
        self.remaining_lookahead -= 1
        self.current_lookahead_token = peeked_token
        return True

    def scan_token_many(self, expected_types):
        peeked_token = self.next_token(self.current_lookahead_token)
        if not self.has_match(expected_types, peeked_token):
            return False
        self.remaining_lookahead -= 1
        self.current_lookahead_token = peeked_token
        return True

    pass

#endif

# explicitdedent:on

#if lexerData::hasContextualTokens

    def IsContextualToken(self, type) :
      return (
         #list lexerData::contextualTokens as ctok
           type == TokenType.${ctok::label}
            ${ctok_has_next ? "or"}
         #endlist
      )
    <-

    def IsIgnoreCase(self, type) :
        #if !lexerData::literalsThatDifferInCaseFromDefault
           return ${settings::ignoreCase ? "True":"False"};
        #else
        return ${settings::ignoreCase ? " not "} (
            #list lexerData::literalsThatDifferInCaseFromDefault as literal
                type == TokenType.${literal::label}
                ${literal_has_next ? " or "}
            #endlist
        );
        #endif
    <-

    def GetLiteralString(self, type) :
        #list lexerData::regularExpressions as regexp
           ${regexp_index==0 ? " if " : " elif "} (type == TokenType.${regexp::label}) :
               #if regexp::literalString??
                  return "${regexp::literalString.j_string}"
               #else
                  return None
               #endif
           <-
        #endlist
        return None
    <-

    def type_matches(self, type, tok) :
      if tok.type == type :
         return True;
      <-
      if (self.IsContextualToken(type)) :
         if self.IsIgnoreCase(type) :
             return self.GetLiteralString(type).lower() == tok.__str__().lower()
         <-
         else :
             return self.GetLiteralString(type) == tok.__str__()
         <-
      <-
      return False;
    <-

    def has_match(self, types, tok) :
      if tok.type in types :
         return True
      <-
      for tt in types :
         if self.IsContextualToken(tt) :
            if self.type_matches(tt, tok) :
               return True
            <-
         <-
      <-
      return False
    <-

#else
    def type_matches(self, type, tok) :
      return tok.type == type
    <-

    def has_match(self, types, tok) :
       return tok.type in types
    <-
#endif
# explicitdedent:restore


# ====================================
# Lookahead Routines
# ====================================
   [#set repetitionIndex = 0]
   [#list grammar::choicePointExpansions as expansion]
      [#if expansion::parent::class::simpleName != "BNFProduction"]
${BuildScanRoutine(expansion, indent)}
      [/#if]
   [/#list]
   [#list grammar::assertionExpansions as expansion]
${BuildAssertionRoutine(expansion, indent)}
   [/#list]   [#list grammar::expansionsNeedingPredicate as expansion]
${BuildPredicateRoutine(expansion, indent)}
   [/#list]
   [#list grammar::allLookaheads as lookahead]
      [#if lookahead::nestedExpansion??]
${BuildLookaheadRoutine(lookahead, indent)}
     [/#if]
   [/#list]
   [#list grammar::allLookBehinds as lookBehind]
${BuildLookBehindRoutine(lookBehind, indent)}
   [/#list]
   [#list grammar::parserProductions as production]
${BuildProductionLookaheadMethod(production, indent)}
   [/#list]
[/#macro]

[#macro BuildPredicateRoutine expansion indent]
  [#var lookaheadAmount = expansion::lookaheadAmount]
  [#if lookaheadAmount = 2147483647][#set lookaheadAmount = "UNLIMITED"][/#if]
  [#var inner = indent + 8]
  [#var cardinalitiesVar = ""]
  [#if expansion::cardinalityConstrained]
    [#set cardinalitiesVar = "cardinalities"]
  [/#if]
    # BuildPredicateRoutine: expansion at ${expansion::location}
    def ${expansion::predicateMethodName}(self[#if expansion::cardinalityConstrained], cardinalities[/#if]):
        self.remaining_lookahead = ${lookaheadAmount}
        self.current_lookahead_token = self.last_consumed_token
        scan_to_end = False
        try:
${BuildPredicateCode(expansion, inner, cardinalitiesVar)}
      [#if !expansion::hasSeparateSyntacticLookahead && expansion::lookaheadAmount != 0]
        [#if expansion::cardinalityConstrained]
${BuildScanCode(expansion, inner, cardinalitiesVar, "")}
        [#else]
${BuildScanCode(expansion, inner, "", "")}
        [/#if]
      [/#if]
            ${lhReturnTrue(cardinalitiesVar, "")}
        finally:
            self.lookahead_routine_nesting = 0
            self.current_lookahead_token = None
            self.hit_failure = False
[/#macro]

[#macro BuildScanRoutine expansion indent]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > BuildScanRoutine ${indent} --]
#if !expansion::singleTokenLookahead || expansion::requiresPredicateMethod
${is}# scanahead routine for expansion at:
${is}# ${expansion::location}
${is}# BuildScanRoutine macro
  [#var cardinalitiesVar = ""]
  [#if expansion::cardinalityConstrained]
    [#set cardinalitiesVar = "cardinalities"]
  [/#if]
${is}def ${expansion::scanRoutineName}(self, scan_to_end[#if expansion::cardinalityConstrained], cardinalities[/#if]):
${is}    # import pdb; pdb.set_trace()
  [#var inner = indent + 4]
  #if expansion::hasScanLimit
${is}    prev_passed_predicate_threshold = self.passed_predicate_threshold
${is}    self.passed_predicate_threshold = -1
  #else
${is}    reached_scan_code = False
${is}    passed_predicate_threshold = self.remaining_lookahead - ${expansion::lookaheadAmount}
  /#if
${is}    try:
${is}        self.lookahead_routine_nesting += 1
${BuildPredicateCode(expansion, inner + 4, cardinalitiesVar)}
  #if !expansion::hasScanLimit
${is}        reached_scan_code = True
  /#if
  [#if expansion::cardinalityConstrained]
${BuildScanCode(expansion, inner + 4, cardinalitiesVar, "")}
  [#else]
${BuildScanCode(expansion, inner + 4, "", "")}
  [/#if]
${is}    finally:
${is}        self.lookahead_routine_nesting -= 1
  #if expansion::hasScanLimit
${is}        if self.remaining_lookahead <= self.passed_predicate_threshold:
${is}            self.passed_predicate = True
${is}            self.passed_predicate_threshold = prev_passed_predicate_threshold
  #else
${is}        if reached_scan_code and self.remaining_lookahead <= passed_predicate_threshold:
${is}            self.passed_predicate = True
  /#if
${is}    self.passed_predicate = False
${is}    ${lhReturnTrue(cardinalitiesVar, "")}
/#if
[#-- ${is}# DBG < BuildScanRoutine ${indent} --]
[/#macro]

[#macro BuildAssertionRoutine expansion indent]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > BuildAssertionRoutine ${indent} --]
${is}# scanahead routine for assertion at:
${is}# ${expansion::parent::location}
${is}# BuildAssertionRoutine macro
${is}def ${expansion::scanRoutineName}(self):
${is}    # import pdb; pdb.set_trace()
  [#var storeCurrentLookaheadVar = CU::newVarName("currentLookahead")
        storeRemainingLookahead = CU::newVarName("remainingLookahead")]
  [#set newVarIndex = 0 in CU]
${is}    ${storeRemainingLookahead} = self.remaining_lookahead
${is}    self.remaining_lookahead = UNLIMITED
${is}    ${storeCurrentLookaheadVar} = self.current_lookahead_token
${is}    prev_hit_failure = self.hit_failure
${is}    if self.current_lookahead_token is None:
${is}        self.current_lookahead_token = self.last_consumed_token
${is}    try:
${is}        self.lookahead_routine_nesting += 1
${BuildScanCode(expansion, indent + 8, "", "")}
${is}        return True
${is}    finally:
${is}        self.lookahead_routine_nesting -= 1
${is}        self.current_lookahead_token = ${storeCurrentLookaheadVar}
${is}        self.remaining_lookahead = ${storeRemainingLookahead}
${is}        self.hit_failure = prev_hit_failure
[#-- ${is}# DBG < BuildAssertionRoutine ${indent} --]
[/#macro]

[#-- Build the code for checking semantic lookahead, lookbehind, and/or syntactic lookahead --]
#macro BuildPredicateCode expansion indent cardinalitiesVar
#var is = "".right_pad(indent)
[#-- ${is}# DBG > BuildPredicateCode ${indent} --]
#if expansion::hasSemanticLookahead && (expansion::lookahead::semanticLookaheadNested || expansion::containingProduction::onlyForLookahead)
${is}if not (${globals.translateExpression(expansion::semanticLookahead)}):
${is}    ${lhReturnFalse(cardinalitiesVar, "")}
/#if
#if expansion::hasLookBehind
${is}if [#if !expansion::lookBehind::negated]not [/#if]self.${expansion::lookBehind::routineName}():
${is}    ${lhReturnFalse(cardinalitiesVar, "")}
/#if
#if expansion::hasSeparateSyntacticLookahead
${is}if self.remaining_lookahead <= 0:
${is}    self.passed_predicate = True
${is}    ${lhReturnCommit("not self.hit_failure", cardinalitiesVar, "")}
${is}if [#if !expansion::lookahead::negated]not [/#if]self.${expansion::lookaheadExpansion::scanRoutineName}(True):
${is}    ${lhReturnFalse(cardinalitiesVar, "")}
/#if
#if expansion::lookaheadAmount == 0
${is}self.passed_predicate = True
/#if
[#-- ${is}# DBG < BuildPredicateCode ${indent} --]
/#macro


[#--
   Generates the routine for an explicit lookahead
   that is used in a nested lookahead.
 --]
[#macro BuildLookaheadRoutine lookahead indent]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > BuildLookaheadRoutine ${indent} --]
[#if lookahead::nestedExpansion??]
${is}# lookahead routine for lookahead at:
${is}# ${lookahead::location}
  [#var inner = indent + 4]
  [#var cardinalitiesVar = ""]
  [#if lookahead::nestedExpansion::cardinalityConstrained]
    [#set cardinalitiesVar = "cardinalities"]
  [/#if]
${is}def ${lookahead::nestedExpansion::scanRoutineName}(self, scan_to_end[#if lookahead::nestedExpansion::cardinalityConstrained], cardinalities[/#if]):
${is}    prev_remaining_lookahead = self.remaining_lookahead
${is}    prev_hit_failure = self.hit_failure
${is}    prev_scanahead_token = self.current_lookahead_token
${is}    try:
${is}        self.lookahead_routine_nesting += 1
  [#if lookahead::nestedExpansion::cardinalityConstrained]
${BuildScanCode(lookahead::nestedExpansion, inner + 4, cardinalitiesVar, "")}
  [#else]
${BuildScanCode(lookahead::nestedExpansion, inner + 4, "", "")}
  [/#if]
${is}        ${lhReturnCommit("not self.hit_failure", cardinalitiesVar, "")}
${is}    finally:
${is}        self.lookahead_routine_nesting -= 1
${is}        self.current_lookahead_token = prev_scanahead_token
${is}        self.remaining_lookahead = prev_remaining_lookahead
${is}        self.hit_failure = prev_hit_failure
[/#if]
[#-- ${is}# DBG < BuildLookaheadRoutine ${indent} --]
[/#macro]

[#macro BuildLookBehindRoutine lookBehind indent]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > BuildLookBehindRoutine ${indent} --]
${is}# Look behind
${is}def ${lookBehind::routineName}(self):
${is}    stack_iterator = self.${lookBehind::backward ? "stack_iterator_backward" : "stack_iterator_forward"}()
[#list lookBehind::path as element]
  [#var elementNegated = (element[0] == "~")]
  [#if elementNegated][#set element = element.substring(1)][/#if]
  [#if element = "."]
${is}    if not stack_iterator.has_next:
${is}        return False
${is}    stack_iterator.next
  [#elif element = "..."]
    [#if element_index = lookBehind::path.size() - 1]
      [#if lookBehind::hasEndingSlash]
${is}    return not stack_iterator.has_next
      [#else]
${is}    return True
      [/#if]
    [#else]
      [#var nextElement = lookBehind::path[element_index + 1]]
      [#var nextElementNegated = (nextElement[0]=="~")]
      [#if nextElementNegated][#set nextElement = nextElement.substring(1)][/#if]
${is}    while stack_iterator.has_next:
${is}        ntc = stack_iterator.next
      #var equalityOp = nextElementNegated ? "!=" : "=="
${is}        if ntc.production_name ${equalityOp} "${nextElement}":
${is}            stack_iterator.previous
${is}            break
${is}        if not stack_iterator.has_next:
${is}            return False
    [/#if]
  [#else]
${is}    if not stack_iterator.has_next:
${is}        return False
${is}    ntc = stack_iterator.next
     #var equalityOp = elementNegated ? "==" : "!="
${is}    if ntc.production_name ${equalityOp} "${element}":
${is}        return False
  [/#if]
[/#list]
[#if lookBehind::hasEndingSlash]
${is}    return not stack_iterator.has_next
[#else]
${is}    return True
[/#if]
[#-- ${is}# DBG < BuildLookBehindRoutine ${indent} --]
[/#macro]

[#macro BuildProductionLookaheadMethod production indent]
[#var is = "".right_pad(indent)]
[#--     # DBG > BuildProductionLookaheadMethod ${indent} --]
    # BuildProductionLookaheadMethod
    def ${production::lookaheadMethodName}(self, scan_to_end):
        # import pdb; pdb.set_trace()
${BuildScanCode(production::expansion, 8, "", "")}
        return True

[#--     # DBG < BuildProductionLookaheadMethod ${indent} --]
[/#macro]

[#--
   Macro to build the lookahead code for an expansion.
   This macro just delegates to the various sub-macros
   based on the Expansion's class name.
--]
[#macro BuildScanCode expansion indent cardVar parentCardVar]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > BuildScanCode ${indent} ${expansion.simpleName} --]
  [#var classname = expansion::simpleName]
  [#if classname != "ExpansionSequence" && classname != "ExpansionWithParentheses"]
${is}if self.hit_failure:
${is}    ${lhReturnFalse(cardVar, parentCardVar)}
${is}if self.remaining_lookahead <= 0:
${is}    ${lhReturnTrue(cardVar, parentCardVar)}
${is}# Lookahead Code for ${classname} specified at ${expansion::location}
  [/#if]
  [@CU::HandleLexicalStateChange expansion, true, indent, cardVar!""; indent]
  [#--
${is}# Building scan code for: ${classname}
${is}# at: ${expansion.location}
  --]
   [#if classname = "ExpansionWithParentheses"]
      [@BuildScanCode expansion::nestedExpansion, indent, cardVar, parentCardVar /]
   [#elif expansion::singleTokenLookahead]
${ScanSingleToken(expansion, indent, cardVar)}
   [#elif classname = "Assertion" && expansion::appliesInLookahead]
${ScanCodeAssertion(expansion, indent, cardVar, parentCardVar)}
   [#elif classname = "Failure"]
${ScanCodeError(expansion, indent, cardVar)}
   [#elif classname = "UncacheTokens"]
${is}self.uncache_tokens()
   [#elif classname = "ExpansionSequence"]
${ScanCodeSequence(expansion, indent, cardVar, parentCardVar)}
   [#elif classname = "ZeroOrOne"]
${ScanCodeZeroOrOne(expansion, indent, cardVar, parentCardVar)}
   [#elif classname = "ZeroOrMore"]
${ScanCodeZeroOrMore(expansion, indent, cardVar, parentCardVar)}
   [#elif classname = "OneOrMore"]
${ScanCodeOneOrMore(expansion, indent, cardVar)}
   [#elif classname = "NonTerminal"]
      [@ScanCodeNonTerminal expansion, indent, cardVar /]
   [#elif classname = "TryBlock" || classname = "AttemptBlock"]
      [@BuildScanCode expansion::nestedExpansion, indent, cardVar, parentCardVar /]
   [#elif classname = "ExpansionChoice"]
${ScanCodeChoice(expansion, indent, cardVar, parentCardVar)}
   [#elif classname = "CodeBlock"]
      [#if expansion::appliesInLookahead || expansion::insideLookahead || expansion::containingProduction::onlyForLookahead]
${globals.translateCodeBlock(expansion, indent)}
      [/#if]
   [/#if]
  [/@CU.HandleLexicalStateChange]
[#-- ${is}# DBG < BuildScanCode ${indent} ${expansion.simpleName} --]
[/#macro]

[#--
   Generates the lookahead code for an ExpansionSequence.
--]
#macro ScanCodeSequence sequence indent cardVar parentCardVar
#var is = "".right_pad(indent)
[#-- ${is}# DBG > ScanCodeSequence ${indent} --]
#list sequence::units as sub
       [@BuildScanCode sub, indent, cardVar, parentCardVar /]
  #if sub::scanLimit
${is}if not scan_to_end and (len(self.lookahead_stack) <= 1):
${is}    if self.lookahead_routine_nesting == 0:
${is}        self.remaining_lookahead = ${sub::scanLimitPlus}
${is}    elif len(self.lookahead_stack) == 1:
${is}        self.passed_predicate_threshold = self.remaining_lookahead[#if sub::scanLimitPlus > 0] - ${sub::scanLimitPlus}[/#if]
  /#if
/#list
[#-- ${is}# DBG < ScanCodeSequence ${indent} --]
/#macro

[#macro ScanCodeNonTerminal nt indent cardinalitiesVar]
[#var is = "".right_pad(indent)]
${is}# NonTerminal ${nt::name} at ${nt::location}
${is}self.push_onto_lookahead_stack('${nt::containingProduction::name}', '${nt::inputSource.j_string}', ${nt::beginLine}, ${nt::beginColumn})
${is}self.current_lookahead_production = '${nt::production::name}'
${is}try:
${is}    if not self.${nt::production::lookaheadMethodName}(${CU::bool(nt::scanToEnd)}):
${is}        ${lhReturnFalse(cardinalitiesVar, "")}
${is}finally:
${is}    self.pop_lookahead_stack()
[/#macro]

[#macro ScanSingleToken expansion indent cardinalitiesVar]
[#var is = "".right_pad(indent)]
[#var firstSet = expansion::firstSet::tokenNames]
[#-- ${is}# DBG > ScanSingleToken ${indent} --]
[#if firstSet.size() = 1]
[#if optimize_scan_token]
${is}if not self.scan_token_one(${firstSet[0]}):
[#else]
${is}if not self.scan_token(${firstSet[0]}):
[/#if]
${is}    ${lhReturnFalse(cardinalitiesVar, "")}
[#else]
[#if optimize_scan_token]
${is}if not self.scan_token_many(self.${expansion::firstSetVarName}):
[#else]
${is}if not self.scan_token(self.${expansion::firstSetVarName}):
[/#if]
${is}    ${lhReturnFalse(cardinalitiesVar, "")}
[/#if]
[#-- ${is}# DBG < ScanSingleToken ${indent} --]
[/#macro]

[#macro ScanCodeAssertion assertion indent cardinalitiesVar parentCardVar]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > ScanCodeAssertion ${indent} --]
[#if assertion::lookBehind??]
${is}if [#if !assertion::lookBehind::negated]not [/#if]self.${assertion::lookBehind::routineName}():
${is}    self.hit_failure = True
${is}    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
[#elif assertion::assertionExpression??]
${is}if not (${globals.translateExpression(assertion::assertionExpression)}):
${is}    self.hit_failure = True
${is}    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
[#elif assertion::rawCode?? && !assertion::rawCode::wrongLanguageIgnore]
${is}if not (${assertion::assertionExpression}):
${is}    self.hit_failure = True
${is}    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
[/#if]
[#if assertion::expansion??]
${is}if [#if !assertion::expansionNegated]not [/#if]self.${assertion::expansion::scanRoutineName}():
${is}    self.hit_failure = True
${is}    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
[/#if]
[#if assertion::cardinalityConstraint?? && cardinalitiesVar?? && (cardinalitiesVar.length() > 0)]
${is}if not ${cardinalitiesVar}.choose(${assertion::assertionIndex}, True):
${is}    self.hit_failure = True
${is}    ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
[/#if]
[#-- ${is}# DBG < ScanCodeAssertion ${indent} --]
[/#macro]

[#macro ScanCodeError expansion indent cardinalitiesVar]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > ScanCodeError ${indent} --]
${is}self.hit_failure = True
${is}${lhReturnFalse(cardinalitiesVar, "")}
[#-- ${is}# DBG < ScanCodeError ${indent} --]
[/#macro]

#macro ScanCodeChoice choice indent cardinalitiesVar parentCardVar
#var is = "".right_pad(indent)
[#-- ${is}# DBG > ScanCodeChoice ${indent} --]
${is}${CU::newVarName("token")} = self.current_lookahead_token
${is}remaining_lookahead${CU::newVarIndex} = self.remaining_lookahead
${is}hit_failure${CU::newVarIndex} = self.hit_failure
${is}passed_predicate${CU::newVarIndex} = self.passed_predicate
${is}try:
#list choice::choices as subseq
${is}    self.passed_predicate = False
${is}    if not (${CheckExpansion(subseq, cardinalitiesVar, parentCardVar)}):
${is}        self.current_lookahead_token = token${CU::newVarIndex}
${is}        self.remaining_lookahead = remaining_lookahead${CU::newVarIndex}
${is}        self.hit_failure = hit_failure${CU::newVarIndex}
  #if !subseq_has_next
${is}        ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
  #else
${is}        if self.passed_predicate and not self.legacy_glitchy_lookahead:
${is}            ${lhReturnFalse(cardinalitiesVar, parentCardVar)}
  /#if
[#-- bump up the indentation, as the items in the list are recursive
     levels
--]
  #set is = is + "    "
/#list
[#list choice::choices as unused][#set is = is[4..]][/#list]
${is}finally:
${is}    self.passed_predicate = passed_predicate${CU::newVarIndex}
[#-- ${is}# DBG < ScanCodeChoice ${indent} --]
/#macro

#macro ScanCodeZeroOrOne zoo indent cardVar parentCardVar
#var is = "".right_pad(indent)
[#-- ${is}# DBG > ScanCodeZeroOrOne ${indent} --]
${is}${CU::newVarName("token")} = self.current_lookahead_token
${is}passed_predicate${CU::newVarIndex} = self.passed_predicate
${is}self.passed_predicate = False
${is}try:
${is}    if not (${CheckExpansion(zoo::nestedExpansion, cardVar, parentCardVar)}):
${is}        if self.passed_predicate and not self.legacy_glitchy_lookahead:
${is}            ${lhReturnFalse(cardVar, "")}
${is}        self.current_lookahead_token = token${CU::newVarIndex}
${is}        self.hit_failure = False
${is}finally:
${is}    self.passed_predicate = passed_predicate${CU::newVarIndex}
[#-- ${is}# DBG < ScanCodeZeroOrOne ${indent} --]
/#macro

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
#macro ScanCodeZeroOrMore zom indent cardVar parentCardVar
#var is = "".right_pad(indent)
[#var zomCardVar = cardVar!""]
[#if zom::cardinalityContainer && (!cardVar?? || cardVar == "")]
[#set zomCardVar = "cardinality" + repetitionIndex][#set repetitionIndex = repetitionIndex + 1]
${is}${zomCardVar} = RepetitionCardinality([@CU::BuildCardinalities zom::cardinalityConstraints, ""/])
[/#if]
#var prevPassedPredicateVarName = CU::newVarName("passed_predicate")
#var prevTokenName = CU::newVarName("token")
${is}${prevPassedPredicateVarName} = self.passed_predicate
${is}try:
[#-- ${is}# DBG > ScanCodeZeroOrMore ${indent} --]
${is}    while self.remaining_lookahead > 0 and not self.hit_failure:
${is}        ${prevTokenName} = self.current_lookahead_token
${is}        self.passed_predicate = False
${is}        if not (${CheckExpansion(zom::nestedExpansion, zomCardVar, parentCardVar)}):
${is}            if self.passed_predicate and not self.legacy_glitchy_lookahead:
${is}                ${lhReturnFalse(parentCardVar, "")}
${is}            self.current_lookahead_token = ${prevTokenName}
${is}            break
[#if zom::cardinalityContainer]
${is}        ${zomCardVar}.commit_iteration(False)
[/#if]
[#if zom::minCardinalityConstrained && zom::cardinalityContainer]
${is}    if not ${zomCardVar}.check_cardinality(True):
${is}        ${lhReturnFalse(parentCardVar, "")}
[/#if]
${is}finally:
${is}    self.passed_predicate = ${prevPassedPredicateVarName}
${is}self.hit_failure = False
[#-- ${is}# DBG < ScanCodeZeroOrMore ${indent} --]
/#macro

[#macro ScanCodeOneOrMore oom indent cardinalitiesVar]
[#var is = "".right_pad(indent)]
[#-- ${is}# DBG > ScanCodeOneOrMore ${indent} --]
[#var oomCardVar = ""]
[#if oom::cardinalityContainer]
[#set oomCardVar = "cardinality" + repetitionIndex][#set repetitionIndex = repetitionIndex + 1]
${is}${oomCardVar} = RepetitionCardinality([@CU::BuildCardinalities oom::cardinalityConstraints, ""/])
[/#if]
[@BuildScanCode oom::nestedExpansion, indent, oomCardVar, cardinalitiesVar /]
[#if oom::cardinalityContainer]
${is}${oomCardVar}.commit_iteration(False)
[/#if]
[@ScanCodeZeroOrMore oom, indent, oomCardVar, cardinalitiesVar /]
[#-- ${is}# DBG < ScanCodeOneOrMore ${indent} --]
[/#macro]


#macro CheckExpansion expansion cardinalitiesVar parentCardVar
#if expansion::singleTokenLookahead
  #if expansion::firstSet::tokenNames.size() = 1
    #if optimize_scan_token
      self.scan_token_one(${expansion::firstSet::tokenNames[0]})[#t]
    #else
      self.scan_token(${expansion::firstSet::tokenNames[0]})[#t]
    #endif
  #else
    #if optimize_scan_token
      self.scan_token_many(self.${expansion::firstSetVarName})[#t]
    #else
      self.scan_token(self.${expansion::firstSetVarName})[#t]
    #endif
  #endif
#else
  [#if grammar::assertionExpansions?? && grammar::assertionExpansions.contains(expansion)]
      self.${expansion::scanRoutineName}()[#t]
  [#else]
  [#if expansion::cardinalityConstrained && cardinalitiesVar?? && (cardinalitiesVar.length() > 0)]
      self.${expansion::scanRoutineName}(False, ${cardinalitiesVar})[#t]
  [#else]
      self.${expansion::scanRoutineName}(False)[#t]
  [/#if]
  [/#if]
#endif
#endmacro


