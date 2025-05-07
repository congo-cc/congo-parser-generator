#-- This template generates the various lookahead/predicate routines

#var repetitionIndex = 0

#macro Generate
    ${firstSetVars()}
#if settings.faultTolerant
    ${followSetVars()}
#endif
    #if grammar.choicePointExpansions
       ${BuildLookaheads()}
    #endif
#endmacro

#macro firstSetVars
    //=================================
     // EnumSets that represent the various expansions' first set (i.e. the set of tokens with which the expansion can begin)
     //=================================
    #list grammar.expansionsForFirstSet as expansion
          ${CU.firstSetVar(expansion)} 
    #endlist
#endmacro

#macro finalSetVars
    //=================================
     // EnumSets that represent the various expansions' final set (i.e. the set of tokens with which the expansion can end)
     //=================================
    #list grammar.expansionsForFinalSet as expansion
          ${finalSetVar(expansion)}
    #endlist
#endmacro


#macro followSetVars
    //=================================
     // EnumSets that represent the various expansions' follow set (i.e. the set of tokens that can immediately follow this)
     //=================================
    #list grammar.expansionsForFollowSet as expansion
          ${CU.followSetVar(expansion)}
    #endlist
#endmacro


#macro BuildLookaheads
  private boolean scanToken(TokenType expectedType, TokenType... additionalTypes) {
     ${settings.baseTokenClassName} peekedToken = nextToken(currentLookaheadToken);
     TokenType type = peekedToken.getType();
     if (type != expectedType) {
       boolean matched = false;
       for (TokenType tt : additionalTypes) {
         if (type == tt) {
            matched = true;
            break;
         }
       }
       if (!matched) return false;
     }
     --remainingLookahead;
     currentLookaheadToken = peekedToken;
     return true;
  }

  private boolean scanToken(EnumSet<TokenType> types) {
     ${settings.baseTokenClassName} peekedToken = nextToken(currentLookaheadToken);
     TokenType type = peekedToken.getType();
     if (!types.contains(type)) return false;
     --remainingLookahead;
     currentLookaheadToken = peekedToken;
     return true; 
  }

//====================================
 // Lookahead Routines
 //====================================
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

#function returnFalse cardinalitiesVar parentCardVar
   #if cardinalitiesVar??
      #if parentCardVar??
         #return "return ${parentCardVar}.commit(false)" 
      #else
         #return "return ${cardinalitiesVar}.commit(false)"
      #endif
   #else
      #return "return false"
   #endif
#endfunction

#function returnTrue cardinalitiesVar parentCardVar
   #if cardinalitiesVar??
      #if parentCardVar??
         #return "return ${parentCardVar}.commit(true)" 
      #else
         #return "return ${cardinalitiesVar}.commit(true)"
      #endif
   #else
      #return "return true"
   #endif
#endfunction

#function return retVal cardinalitiesVar parentCardVar
   #if cardinalitiesVar??
      #if parentCardVar??
         #return "return ${parentCardVar}.commit(${retVal})" 
      #else
         #return "return ${cardinalitiesVar}.commit(${retVal})"
      #endif
   #else
      #return "return ${retVal}"
   #endif
#endfunction

#macro BuildPredicateRoutine expansion
  #var lookaheadAmount = expansion.lookaheadAmount == 2147483647 ?: "UNLIMITED" : expansion.lookaheadAmount
  #set CU.newVarIndex = 0
  #var cardinalitiesVar = null
  #if expansion.cardinalityConstrained
      #set cardinalitiesVar = "cardinalities"
  #endif
  // BuildPredicateRoutine: ${expansion.simpleName} at ${expansion.location}
   private boolean ${expansion.predicateMethodName}([#if cardinalitiesVar??]RepetitionCardinality ${cardinalitiesVar}[/#if]) {
     remainingLookahead = ${lookaheadAmount};
     currentLookaheadToken = lastConsumedToken;
     final boolean scanToEnd = false;
     try {
      ${BuildPredicateCode(expansion cardinalitiesVar)}
      #if !expansion.hasSeparateSyntacticLookahead && expansion.lookaheadAmount > 0
        #if expansion.cardinalityConstrained
          ${BuildScanCode(expansion cardinalitiesVar)}
        #else
          ${BuildScanCode(expansion)}
        #endif
      #endif
      ${returnTrue(cardinalitiesVar)};
      }
      finally {
         lookaheadRoutineNesting = 0;
         currentLookaheadToken = null;
         hitFailure = false;
     }
   }
#endmacro

#macro BuildScanRoutine expansion
 #-- // DBG > createNode --
 #if !expansion.singleTokenLookahead
  // scanahead routine for expansion at:
  // ${expansion.location}
  // BuildScanRoutine macro
  #set newVarIndex = 0 in CU
  #var cardinalitiesVar = null
  #if expansion.cardinalityConstrained
    #set cardinalitiesVar = "cardinalities"
  #endif
  private boolean ${expansion.scanRoutineName}(boolean scanToEnd[#if expansion.cardinalityConstrained], RepetitionCardinality cardinalities[/#if]) { 
    #if expansion.hasScanLimit
       int prevPassedPredicateThreshold = this.passedPredicateThreshold;
       this.passedPredicateThreshold = -1;
    #else
       boolean reachedScanCode = false;
       int passedPredicateThreshold = remainingLookahead - ${expansion.lookaheadAmount};
    /#if
    try {
       lookaheadRoutineNesting++;
       ${BuildPredicateCode(expansion cardinalitiesVar)}
      #if !expansion.hasScanLimit
       reachedScanCode = true;
      #endif
      #if expansion.cardinalityConstrained
       ${BuildScanCode(expansion cardinalitiesVar)}
      #else
       ${BuildScanCode(expansion)}
      #endif 
    }
    finally {
       lookaheadRoutineNesting--;
   #if expansion.hasScanLimit
       if (remainingLookahead <= this.passedPredicateThreshold) {
         passedPredicate = true;
         this.passedPredicateThreshold = prevPassedPredicateThreshold;
       }
   #else
       if (reachedScanCode && remainingLookahead <= passedPredicateThreshold) {
         passedPredicate = true;
       }
   #endif
    }
    passedPredicate = false;
    ${returnTrue(cardinalitiesVar)};
  }
 #endif
#endmacro

#macro BuildAssertionRoutine expansion
  // scanahead routine for assertion at:
  // ${expansion.parent.location}
  // BuildAssertionRoutine macro
  #var storeCurrentLookaheadVar = CU.newVarName("currentLookahead"),
        storeRemainingLookahead = CU.newVarName("remainingLookahead")
  #set newVarIndex = 0 in CU
    private boolean ${expansion.scanRoutineName}() {
       final boolean scanToEnd = true;
       int ${storeRemainingLookahead} = remainingLookahead;
       remainingLookahead = UNLIMITED;
       ${settings.baseTokenClassName} ${storeCurrentLookaheadVar} = currentLookaheadToken;
       boolean prevHitFailure = hitFailure;
       if (currentLookaheadToken == null) {
          currentLookaheadToken = lastConsumedToken;
       }
       try {
          lookaheadRoutineNesting++;
          #-- REVISIT: does this ever need a cardinalitiesVar arg? [JB] --
          ${BuildScanCode(expansion)}
          return true;
       }
       finally {
          lookaheadRoutineNesting--;
          currentLookaheadToken = ${storeCurrentLookaheadVar};
          remainingLookahead = ${storeRemainingLookahead};
          hitFailure = prevHitFailure;
       }
    }
#endmacro

[#-- Build the code for checking semantic lookahead, lookbehind, and/or syntactic lookahead --]
#macro BuildPredicateCode expansion cardinalitiesVar
    // BuildPredicateCode macro
  #if expansion.hasSemanticLookahead && (expansion.lookahead.semanticLookaheadNested || expansion.containingProduction.onlyForLookahead)
       if (!(${expansion.semanticLookahead})) ${returnFalse(cardinalitiesVar!null)};
  #endif
  #if expansion.hasLookBehind
       if (
         ${!expansion.lookBehind.negated ?: "!"}
         ${expansion.lookBehind.routineName}()
       ) ${returnFalse(cardinalitiesVar!null)};
  #endif
  #if expansion.hasSeparateSyntacticLookahead
       if (remainingLookahead <= 0) {
        passedPredicate = true;
        ${return("!hitFailure", cardinalitiesVar!null)};
       }
       if (
         ${!expansion.lookahead.negated ?: "!"}
         ${expansion.lookaheadExpansion.scanRoutineName}(true)
       ) ${returnFalse(cardinalitiesVar!null)};
  #endif
  #if expansion.lookaheadAmount == 0
       passedPredicate = true;
  #endif
    // End BuildPredicateCode macro
#endmacro


[#--
   Generates the routine for an explicit lookahead
   that is used in a nested lookahead.
 --]
#macro BuildLookaheadRoutine lookahead
     // lookahead routine for lookahead at:
     // ${lookahead.location}
  #set newVarIndex = 0 in CU
  #var cardinalitiesVar = null
  #if lookahead.nestedExpansion.cardinalityConstrained
   #set cardinalitiesVar = "cardinalities"
  #endif
     private boolean ${lookahead.nestedExpansion.scanRoutineName}(boolean scanToEnd[#if lookahead.nestedExpansion.cardinalityConstrained], RepetitionCardinality ${cardinalitiesVar}[/#if]) {
        int prevRemainingLookahead = remainingLookahead;
        boolean prevHitFailure = hitFailure;
        ${settings.baseTokenClassName} prevScanAheadToken = currentLookaheadToken;
        try {
          lookaheadRoutineNesting++;
          #if lookahead.nestedExpansion.cardinalityConstrained
            ${BuildScanCode(lookahead.nestedExpansion cardinalitiesVar)}
          #else
            ${BuildScanCode(lookahead.nestedExpansion)}
          #endif
          ${return("!hitFailure", cardinalitiesVar)};
        }
        finally {
           lookaheadRoutineNesting--;
           currentLookaheadToken = prevScanAheadToken;
           remainingLookahead = prevRemainingLookahead;
           hitFailure = prevHitFailure;
        }
     }
#endmacro

#macro BuildLookBehindRoutine lookBehind
  #set newVarIndex = 0 in CU
    private boolean ${lookBehind.routineName}() {
       ListIterator<NonTerminalCall> stackIterator = ${lookBehind.backward?string("stackIteratorBackward", "stackIteratorForward")}();
       NonTerminalCall ntc = null;
       #list lookBehind.path as element
          #var elementNegated = (element[0] == "~")
          [#if elementNegated][#set element = element?substring(1)][/#if]
          #if element = "."
              if (!stackIterator.hasNext()) {
                 ${returnFalse(cardinalitiesVar!null)};
              }
              stackIterator.next();
          #elif element = "..."
             #if element_index = lookBehind.path?size - 1
                 #if lookBehind.hasEndingSlash
                      return !stackIterator.hasNext();
                 #else
                      return true;
                 #endif
             #else
                 #var nextElement = lookBehind.path[element_index + 1]
                 #var nextElementNegated = (nextElement[0] == "~")
                 [#if nextElementNegated][#set nextElement = nextElement?substring(1)][/#if]
                 while (stackIterator.hasNext()) {
                    ntc = stackIterator.next();
                    #var equalityOp = nextElementNegated?string("!=", "==")
                    if (ntc.productionName ${equalityOp} "${nextElement}") {
                       stackIterator.previous();
                       break;
                    }
                    if (!stackIterator.hasNext()) ${returnFalse(cardinalitiesVar!null)};
                 }
             #endif
          #else
             if (!stackIterator.hasNext()) ${returnFalse(cardinalitiesVar!null)};
             ntc = stackIterator.next();
             #var equalityOp = elementNegated?string("==", "!=")
               if (ntc.productionName ${equalityOp} "${element}") ${returnFalse(cardinalitiesVar!null)};
          #endif
       #endlist
       #if lookBehind.hasEndingSlash
           return !stackIterator.hasNext();
       #else
           return true;
       #endif
    }
#endmacro

#macro BuildProductionLookaheadMethod production
   #-- TODO: I think this will need a cardinalitiesVar arg if/when cardinality is transitive across productions.
   // BuildProductionLookaheadMethod macro
  #set CU.newVarIndex = 0 
   private boolean ${production.lookaheadMethodName}(boolean scanToEnd) {
      #if production.javaCode?? && (production.javaCode.appliesInLookahead || production.onlyForLookahead)
         ${production.javaCode}
      #endif
      ${BuildScanCode(production.expansion)}
      return true;
   }
#endmacro

[#--
   Macro to build the lookahead code for an expansion.
   This macro just delegates to the various sub-macros
   based on the Expansion's class name.
--]
#macro BuildScanCode expansion cardVar parentCardVar 
  #var classname = expansion.simpleName
  #var skipCheck = classname == "ExpansionSequence" || 
                  #-- We can skip the check if this is a semantically meaningless
                  #-- parentheses, only there for grouping or readability
                   classname == "ExpansionWithParentheses" && !expansion::startsWithLexicalChange()
  #if !skipCheck
      if (hitFailure) ${returnFalse(cardVar!null)};
      if (remainingLookahead <= 0 ) ${returnTrue(cardVar!null)};
    // Lookahead Code for ${classname} specified at ${expansion.location}
  #else
    // skipping check
  #endif
  [@CU.HandleLexicalStateChange expansion true]
   // Building scan code for: ${classname}
   // at: ${expansion.location}
   #if classname = "ExpansionWithParentheses"
      ${BuildScanCode(expansion.nestedExpansion cardVar!null parentCardVar!null)}
   #elif expansion.singleTokenLookahead
      ${ScanSingleToken(expansion cardVar!null)}
   #elif expansion.terminal
      [#-- This is actually dead code since this is
      caught by the previous case. I have it here because
      sometimes I like to comment out the previous condition
      for testing purposes.--]
      ${ScanSingleToken(expansion cardVar!null)}
   #elif classname = "Assertion" 
      #if expansion.appliesInLookahead
         ${ScanCodeAssertion(expansion cardVar!null parentCardVar!null)}
      #else
         // No code generated since this assertion does not apply in lookahead
      #endif
   #elif classname = "Failure"
         ${ScanCodeError(expansion cardVar!null)}
   #elif classname = "UncacheTokens"
         uncacheTokens();
   #elif classname = "ExpansionSequence"
      ${ScanCodeSequence(expansion cardVar!null parentCardVar!null)}
   #elif classname = "ZeroOrOne"
      ${ScanCodeZeroOrOne(expansion cardVar!null)}
   #elif classname = "ZeroOrMore"
      ${ScanCodeZeroOrMore(expansion cardVar!null)}
   #elif classname = "OneOrMore"
      ${ScanCodeOneOrMore(expansion cardVar!null)}
   #elif classname = "NonTerminal"
      ${ScanCodeNonTerminal(expansion cardVar!null)}
   #elif classname = "TryBlock" || classname = "AttemptBlock"
      ${BuildScanCode(expansion.nestedExpansion cardVar!null parentCardVar!null)}
   #elif classname = "ExpansionChoice"
      ${ScanCodeChoice(expansion cardVar!null parentCardVar!null)}
   #elif classname = "CodeBlock"
      #if expansion.appliesInLookahead || expansion.insideLookahead || expansion.containingProduction.onlyForLookahead
         ${expansion}
      #endif
   #endif
  [/@CU.HandleLexicalStateChange]
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
#macro ScanCodeSequence sequence cardVar parentCardVar
   #list sequence.units as sub
       ${BuildScanCode(sub cardVar!null parentCardVar!null)}
       #if sub.scanLimit
         if (!scanToEnd && lookaheadStack.size() <= 1) {
            if (lookaheadRoutineNesting == 0) {
              remainingLookahead = ${sub.scanLimitPlus};
            }
            else if (lookaheadStack.size() == 1) {
               passedPredicateThreshold = remainingLookahead[#if sub.scanLimitPlus > 0] - ${sub.scanLimitPlus}[/#if];
            }
         }
       #endif
   #endlist
#endmacro

[#--
  Generates the lookahead code for a non-terminal.
  It (trivially) just delegates to the code for
  checking the production's nested expansion
--]
#macro ScanCodeNonTerminal nt cardinalitiesVar
      // NonTerminal ${nt.name} at ${nt.location}
      pushOntoLookaheadStack("${nt.containingProduction.name}", "${nt.inputSource?j_string}", ${nt.beginLine}, ${nt.beginColumn});
      currentLookaheadProduction = "${nt.production.name}";
      try {
          if (!${nt.production.lookaheadMethodName}(${CU.bool(nt.scanToEnd)})) ${returnFalse(cardinalitiesVar!null)};
      }
      finally {
          popLookaheadStack();
      }
#endmacro

#macro ScanSingleToken expansion cardinalitiesVar
    #var firstSet = expansion.firstSet.tokenNames
    #if firstSet?size < CU.USE_FIRST_SET_THRESHOLD
      if (!scanToken(
        #list expansion.firstSet.tokenNames as name
          ${name}
          [#if name_has_next],[/#if]
        #endlist
      )) ${returnFalse(cardinalitiesVar!null)};
    #else
      if (!scanToken(${expansion.firstSetVarName})) ${returnFalse(cardinalitiesVar!null)};
    #endif
#endmacro

#macro ScanCodeAssertion assertion cardinalitiesVar patentCardVar
   #if assertion.assertionExpression??
      if (!(${assertion.assertionExpression})) {
         hitFailure = true;
         ${returnFalse(cardinalitiesVar, parentCardVar!null)};
      }
   #endif
   #if assertion.expansion??
      if (
         ${!assertion.expansionNegated ?: "!"}
         ${assertion.expansion.scanRoutineName}()
      ) {
        hitFailure = true;
        ${returnFalse(cardinalitiesVar, parentCardVar!null)};
      }
   #endif
   #if assertion.cardinalityConstraint
      // Cardinality constraint check to ensure maximum not reached.
      if (!${cardinalitiesVar}.choose(${assertion.assertionIndex}, true)) {
         hitFailure = true;
         ${returnFalse(cardinalitiesVar, parentCardVar!null)};
      }
   #endif
#endmacro

#macro ScanCodeError expansion cardinalitiesVar
    if (true) {
      hitFailure = true;
      ${returnFalse(cardinalitiesVar!null)};
    }
#endmacro

#macro ScanCodeChoice choice [#-- choices --] cardinalitiesVar parentCardVar
   ${CU.newVar(settings.baseTokenClassName, "currentLookaheadToken")}
   int remainingLookahead${CU.newVarIndex} = remainingLookahead;
   boolean hitFailure${CU.newVarIndex} = hitFailure;
   boolean passedPredicate${CU.newVarIndex} = passedPredicate;
   try {
  #list choice.choices as subseq
     passedPredicate = false;
     if (!${CheckExpansion(subseq cardinalitiesVar!null parentCardVar!null)}) {
     currentLookaheadToken = ${settings.baseTokenClassName?lower_case}${CU.newVarIndex};
     remainingLookahead = remainingLookahead${CU.newVarIndex};
     hitFailure = hitFailure${CU.newVarIndex};
     #if !subseq_has_next
        ${returnFalse(cardinalitiesVar!null, parentCardVar!null)};
     #else
        if (passedPredicate && !legacyGlitchyLookahead) ${returnFalse(cardinalitiesVar!null, parentCardVar!null)};
     #endif
  #endlist
  [#list choice.choices as unused] 
     }
  [/#list]
   } finally {
      passedPredicate = passedPredicate${CU.newVarIndex};
   }
#endmacro

#macro ScanCodeZeroOrOne zoo cardVar parentCardVar
   ${CU.newVar(settings.baseTokenClassName"currentLookaheadToken")}
   boolean passedPredicate${CU.newVarIndex} = passedPredicate;
   passedPredicate = false;
   try {
      if (!${CheckExpansion(zoo.nestedExpansion cardVar!null parentCardVar!null)}) {
         if (passedPredicate && !legacyGlitchyLookahead) ${returnFalse(cardVar!null)};
         currentLookaheadToken = ${settings.baseTokenClassName?lower_case}${CU.newVarIndex};
         hitFailure = false;
      }
   } finally {passedPredicate = passedPredicate${CU.newVarIndex};}
#endmacro

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
#macro ScanCodeZeroOrMore zom cardVar cardinalitiesVar
   #var prevPassPredicateVarName = "passedPredicate" + CU.newID()
    #var zomCardVar = cardVar!null
    #if zom.cardinalityContainer & zomCardVar?is_null
      #set zomCardVar = "cardinality" + repetitionIndex
      #set repetitionIndex = repetitionIndex + 1
      // instantiating the OneOrMore choice cardinality container for its ExpansionChoices 
      RepetitionCardinality ${zomCardVar} = new RepetitionCardinality(${CU.BuildCardinalities(zom.cardinalityConstraints)}, false); 
    #endif
    boolean ${prevPassPredicateVarName} = passedPredicate;
    try {
      while (remainingLookahead > 0 && !hitFailure) {
      ${CU.newVar(type = settings.baseTokenClassName init = "currentLookaheadToken")}
        passedPredicate = false;
        if (!${CheckExpansion(zom.nestedExpansion zomCardVar cardinalitiesVar!null)}) {
            if (passedPredicate && !legacyGlitchyLookahead) ${returnFalse(cardinalitiesVar!null)};
            currentLookaheadToken = ${settings.baseTokenClassName?lower_case}${CU.newVarIndex};
            break;
        }
        #if zom.cardinalityContainer
           ${zomCardVar}.commitIteration(false);
        #endif
      }
      #if zom.cardinalityContainer
         if(!${zomCardVar}.checkCardinality(true)) ${returnFalse(cardinalitiesVar!null)};
      #endif
    } finally {
      passedPredicate = ${prevPassPredicateVarName};
    }
    hitFailure = false;
#endmacro

[#--
   Generates lookahead code for a OneOrMore construct
   It generates the code for checking a single occurrence
   and then the same code as a ZeroOrMore
--]
#macro ScanCodeOneOrMore oom cardinalitiesVar[#-- Note, incoming cardinalities here imply a direct outer iteration (affects commit returns) --]
    #var oomCardVar = null
    #if oom.cardinalityContainer
      #set oomCardVar = "cardinality" + repetitionIndex
      #set repetitionIndex = repetitionIndex + 1
      // instantiating the OneOrMore choice cardinality container for its ExpansionChoices 
      RepetitionCardinality ${oomCardVar} = new RepetitionCardinality(${CU.BuildCardinalities(oom.cardinalityConstraints)}, false); 
    #endif
   ${BuildScanCode(oom.nestedExpansion oomCardVar cardinalitiesVar!null)}
   #if oom.cardinalityContainer
      ${oomCardVar}.commitIteration(false);
   #endif
   ${ScanCodeZeroOrMore(oom oomCardVar cardinalitiesVar!null)}
#endmacro

#macro CheckExpansion expansion cardinalitiesVar parentCardVar
   #if expansion.singleTokenLookahead
     #if expansion.firstSet.tokenNames?size < CU.USE_FIRST_SET_THRESHOLD
      scanToken(
        #list expansion.firstSet.tokenNames as name
          ${name}
          [#if name_has_next],[/#if]
        #endlist
      )
     #else
      scanToken(${expansion.firstSetVarName})
     #endif
   #else
      ${expansion.scanRoutineName}(false[#if expansion.cardinalityConstrained && cardinalitiesVar??], ${cardinalitiesVar}[/#if])
   #endif
#endmacro
