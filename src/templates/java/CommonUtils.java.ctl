[#-- A place to put some utility routines used in various templates. Currently doesn't
     really have much! --]

#var USE_FIRST_SET_THRESHOLD = 5

[#--
  Rewritten version of this macro to try to get around the Code too large problem.
--]
#macro enumSet varName tokenNames
   #if tokenNames?size = 0
     private static final EnumSet<TokenType> ${varName} = EnumSet.noneOf(TokenType.class);
   #elif tokenNames?size < 8
    private static final EnumSet<TokenType> ${varName} = tokenTypeSet(
       #list tokenNames as type
         [#if type_index > 0],[/#if]
         ${type}
       #endlist
    );
   [#else]
    private static final EnumSet<TokenType> ${varName} = ${varName}_init();
    private static EnumSet<TokenType> ${varName}_init() {
       return tokenTypeSet(
         #list tokenNames as type
          [#if type_index > 0],[/#if]
           ${type}
         #endlist
       );
    }
   #endif
#endmacro

#macro firstSetVar expansion
    ${enumSet(expansion.firstSetVarName, expansion.firstSet.tokenNames)}
#endmacro

#macro finalSetVar expansion
    ${enumSet(expansion.finalSetVarName, expansion.finalSet.tokenNames)}
#endmacro

#macro followSetVar expansion
    ${enumSet(expansion.followSetVarName, expansion.followSet.tokenNames)}
#endmacro


#var newVarIndex = 0
[#-- Just to generate a new unique variable name
  All it does is tack an integer (that is incremented)
  onto the type name, and optionally initializes it to some value--]
#macro newVar type init = null
   #set newVarIndex = newVarIndex + 1
   ${type} ${type?lower_case}${newVarIndex}
   #if init??
      = ${init}
   #endif
   ;
#endmacro

#macro newVarName prefix
   ${prefix}${newID()}
#endmacro

#function newID
    #set newVarIndex = newVarIndex + 1
    #return newVarIndex
#endfunction

#function bool val
   #return val ?: "true" : "false"
#endfunction

#macro BuildCardinalities assertions
   new int[][]{[#list assertions as range]new int[]{${range[0]},${range[1]}}[#if range_has_next],[/#if][/#list]}
#endmacro

#macro HandleLexicalStateChange expansion inLookahead cardinalitiesVar
   #var resetToken = inLookahead ?: "currentLookaheadToken" : "lastConsumedToken"
   #var prevLexicalStateVar = newVarName("previousLexicalState")
   #if expansion.specifiedLexicalState
         LexicalState ${prevLexicalStateVar} = token_source.lexicalState;
         token_source.reset(${resetToken}, LexicalState.${expansion.specifiedLexicalState});
         try {
           #nested
         }
         finally {
            if (${prevLexicalStateVar} != LexicalState.${expansion.specifiedLexicalState}) {
                if (${resetToken}.getNext() != null) {
                    token_source.reset(${resetToken}, ${prevLexicalStateVar});
                }
                else {
                    token_source.switchTo(${prevLexicalStateVar});
                }
                nextTokenType = null;
            }
         }
   #elif expansion.tokenActivation
      #var tokenActivation = expansion.tokenActivation
      #var prevActives = newVarName("previousActives")
      #var somethingChanged = newVarName("somethingChanged")
      EnumSet<TokenType> ${prevActives} = EnumSet.copyOf(token_source.activeTokenTypes);
      boolean ${somethingChanged} = false;
      #if tokenActivation.activatedTokens
         ${somethingChanged} = activateTokenTypes(
         #list tokenActivation.activatedTokens as tokenName
             ${tokenName}[#if tokenName_has_next],[/#if]
         #endlist
         );
      #endif
      #if tokenActivation.deactivatedTokens
         ${somethingChanged} = ${somethingChanged} |= deactivateTokenTypes(
         #list tokenActivation.deactivatedTokens as tokenName
             ${tokenName}[#if tokenName_has_next],[/#if]
         #endlist
         );
      #endif
      try {
         #nested
      }
      finally {
         token_source.activeTokenTypes = ${prevActives};
         if (${somethingChanged}) {
             token_source.reset(${resetToken});
             nextTokenType = null;
         }
      }
   #else
      #nested
   #endif
#endmacro

