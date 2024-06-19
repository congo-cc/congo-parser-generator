#-- This template generates the various lookahead/predicate routines

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

#macro BuildPredicateRoutine expansion
  #var lookaheadAmount = expansion.lookaheadAmount == 2147483647 ?: "UNLIMITED" : expansion.lookaheadAmount
  #set CU.newVarIndex = 0
  // BuildPredicateRoutine: expansion at ${expansion.location}
   private boolean ${expansion.predicateMethodName}() {
     remainingLookahead = ${lookaheadAmount};
     currentLookaheadToken = lastConsumedToken;
     final boolean scanToEnd = false;
     try {
      ${BuildPredicateCode(expansion)}
      #if !expansion.hasSeparateSyntacticLookahead && expansion.lookaheadAmount > 0
        ${BuildScanCode(expansion)}
      #endif
         return true;
      }
      finally {
         lookaheadRoutineNesting = 0;
         currentLookaheadToken = null;
         hitFailure = false;
     }
   }
#endmacro

#macro BuildScanRoutine expansion
 #if !expansion.singleTokenLookahead
  // scanahead routine for expansion at:
  // ${expansion.location}
  // BuildScanRoutine macro
#set newVarIndex = 0 in CU
  private boolean ${expansion.scanRoutineName}(boolean scanToEnd) {
    #if expansion.hasScanLimit
       int prevPassedPredicateThreshold = this.passedPredicateThreshold;
       this.passedPredicateThreshold = -1;
    #else
       boolean reachedScanCode = false;
       int passedPredicateThreshold = remainingLookahead - ${expansion.lookaheadAmount};
    /#if
    try {
       lookaheadRoutineNesting++;
       ${BuildPredicateCode(expansion)}
      #if !expansion.hasScanLimit
       reachedScanCode = true;
      #endif
       ${BuildScanCode(expansion)}
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
    return true;
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
#macro BuildPredicateCode expansion
    // BuildPredicateCode macro
  #if expansion.hasSemanticLookahead && (expansion.lookahead.semanticLookaheadNested || expansion.containingProduction.onlyForLookahead)
       if (!(${expansion.semanticLookahead})) return false;
  #endif
  #if expansion.hasLookBehind
       if ([#if !expansion.lookBehind.negated]![/#if]
       ${expansion.lookBehind.routineName}()) return false;
  #endif
  #if expansion.hasSeparateSyntacticLookahead
       if (remainingLookahead <= 0) {
        passedPredicate = true;
        return !hitFailure;
       }
       if (
      [#if !expansion.lookahead.negated]![/#if]
        ${expansion.lookaheadExpansion.scanRoutineName}(true)) return false;
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
     private boolean ${lookahead.nestedExpansion.scanRoutineName}(boolean scanToEnd) {
        int prevRemainingLookahead = remainingLookahead;
        boolean prevHitFailure = hitFailure;
        ${settings.baseTokenClassName} prevScanAheadToken = currentLookaheadToken;
        try {
          lookaheadRoutineNesting++;
          ${BuildScanCode(lookahead.nestedExpansion)}
          return !hitFailure;
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
                 return false;
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
                    if (!stackIterator.hasNext()) return false;
                 }
             #endif
          #else
             if (!stackIterator.hasNext()) return false;
             ntc = stackIterator.next();
             #var equalityOp = elementNegated?string("==", "!=")
               if (ntc.productionName ${equalityOp} "${element}") return false;
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
#macro BuildScanCode expansion
  #var classname = expansion.simpleName
  #var skipCheck = classname == "ExpansionSequence" || 
                  #-- We can skip the check if this is a semantically meaningless
                  #-- parentheses, only there for grouping or readability
                   classname == "ExpansionWithParentheses" && !expansion::startsWithLexicalChange()
  #if !skipCheck
      if (hitFailure) return false;
      if (remainingLookahead <= 0 ) return true;
    // Lookahead Code for ${classname} specified at ${expansion.location}
  #endif
  [@CU.HandleLexicalStateChange expansion true]
   [#--
   // Building scan code for: ${classname}
   // at: ${expansion.location}
   --]
   #if classname = "ExpansionWithParentheses"
      ${BuildScanCode(expansion.nestedExpansion)}
   #elif expansion.singleTokenLookahead
      ${ScanSingleToken(expansion)}
   #elif expansion.terminal
      [#-- This is actually dead code since this is
      caught by the previous case. I have it here because
      sometimes I like to comment out the previous condition
      for testing purposes.--]
      ${ScanSingleToken(expansion)}
   #elif classname = "Assertion" && expansion.appliesInLookahead
      #if expansion.appliesInLookahead
         ${ScanCodeAssertion(expansion)}
      #else
         // No code generated since this assertion does not apply in lookahead
      #endif
   #elif classname = "Failure"
         ${ScanCodeError(expansion)}
   #elif classname = "UncacheTokens"
         uncacheTokens();
   #elif classname = "ExpansionSequence"
      ${ScanCodeSequence(expansion)}
   #elif classname = "ZeroOrOne"
      ${ScanCodeZeroOrOne(expansion)}
   #elif classname = "ZeroOrMore"
      ${ScanCodeZeroOrMore(expansion)}
   #elif classname = "OneOrMore"
      ${ScanCodeOneOrMore(expansion)}
   #elif classname = "NonTerminal"
      ${ScanCodeNonTerminal(expansion)}
   #elif classname = "TryBlock" || classname = "AttemptBlock"
      ${BuildScanCode(expansion.nestedExpansion)}
   #elif classname = "ExpansionChoice"
      ${ScanCodeChoice(expansion)}
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
#macro ScanCodeSequence sequence
   #list sequence.units as sub
       ${BuildScanCode(sub)}
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
#macro ScanCodeNonTerminal nt
      // NonTerminal ${nt.name} at ${nt.location}
      pushOntoLookaheadStack("${nt.containingProduction.name}", "${nt.inputSource?j_string}", ${nt.beginLine}, ${nt.beginColumn});
      currentLookaheadProduction = "${nt.production.name}";
      try {
          if (!${nt.production.lookaheadMethodName}(${CU.bool(nt.scanToEnd)})) return false;
      }
      finally {
          popLookaheadStack();
      }
#endmacro

#macro ScanSingleToken expansion
    #var firstSet = expansion.firstSet.tokenNames
    #if firstSet?size < CU.USE_FIRST_SET_THRESHOLD
      if (!scanToken(
        #list expansion.firstSet.tokenNames as name
          ${name}
          [#if name_has_next],[/#if]
        #endlist
      )) return false;
    #else
      if (!scanToken(${expansion.firstSetVarName})) return false;
    #endif
#endmacro

#macro ScanCodeAssertion assertion
   #if assertion.assertionExpression??
      if (!(${assertion.assertionExpression})) {
         hitFailure = true;
         return false;
      }
   #endif
   #if assertion.expansion??
      if ([#if !assertion.expansionNegated]![/#if]
         ${assertion.expansion.scanRoutineName}()
      ) {
        hitFailure = true;
        return false;
      }
   #endif
#endmacro

#macro ScanCodeError expansion
    if (true) {
      hitFailure = true;
      return false;
    }
#endmacro

#macro ScanCodeChoice choice
   ${CU.newVar(settings.baseTokenClassName, "currentLookaheadToken")}
   int remainingLookahead${CU.newVarIndex} = remainingLookahead;
   boolean hitFailure${CU.newVarIndex} = hitFailure;
   boolean passedPredicate${CU.newVarIndex} = passedPredicate;
   try {
  #list choice.choices as subseq
     passedPredicate = false;
     if (!${CheckExpansion(subseq)}) {
     currentLookaheadToken = ${settings.baseTokenClassName?lower_case}${CU.newVarIndex};
     remainingLookahead = remainingLookahead${CU.newVarIndex};
     hitFailure = hitFailure${CU.newVarIndex};
     #if !subseq_has_next
        return false;
     #else
        if (passedPredicate && !legacyGlitchyLookahead) return false;
     #endif
  #endlist
  [#list choice.choices as unused] } [/#list]
   } finally {passedPredicate = passedPredicate${CU.newVarIndex};}
#endmacro

#macro ScanCodeZeroOrOne zoo
   ${CU.newVar(settings.baseTokenClassName"currentLookaheadToken")}
   boolean passedPredicate${CU.newVarIndex} = passedPredicate;
   passedPredicate = false;
   try {
      if (!${CheckExpansion(zoo.nestedExpansion)}) {
         if (passedPredicate && !legacyGlitchyLookahead) return false;
         currentLookaheadToken = ${settings.baseTokenClassName?lower_case}${CU.newVarIndex};
         hitFailure = false;
      }
   } finally {passedPredicate = passedPredicate${CU.newVarIndex};}
#endmacro

[#--
  Generates lookahead code for a ZeroOrMore construct]
--]
#macro ScanCodeZeroOrMore zom
   #var prevPassPredicateVarName = "passedPredicate" + CU.newID()
    boolean ${prevPassPredicateVarName} = passedPredicate;
    try {
      while (remainingLookahead > 0 && !hitFailure) {
      ${CU.newVar(type = settings.baseTokenClassName init = "currentLookaheadToken")}
        passedPredicate = false;
        if (!${CheckExpansion(zom.nestedExpansion)}) {
            if (passedPredicate && !legacyGlitchyLookahead) return false;
            currentLookaheadToken = ${settings.baseTokenClassName?lower_case}${CU.newVarIndex};
            break;
        }
      }
    } finally {passedPredicate = ${prevPassPredicateVarName};}
    hitFailure = false;
#endmacro

[#--
   Generates lookahead code for a OneOrMore construct
   It generates the code for checking a single occurrence
   and then the same code as a ZeroOrMore
--]
#macro ScanCodeOneOrMore oom
   ${BuildScanCode(oom.nestedExpansion)}
   ${ScanCodeZeroOrMore(oom)}
#endmacro


#macro CheckExpansion expansion
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
      ${expansion.scanRoutineName}(false)
   #endif
#endmacro
