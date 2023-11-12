[#-- A place to put some utility routines used in various templates. Currently doesn't
     really have much! --]

[#var USE_FIRST_SET_THRESHOLD = 5]

 [#--if settings.parserPackage]
   [#-- This is necessary because you can't do a static import from the unnamed or "default package" --]
   [#--set TT=""]
 [/#if--]

[#--
  Rewritten version of this macro to try to get around the Code too large problem.
--]
[#macro enumSet varName tokenNames]
   [#if tokenNames?size = 0]
     private static final EnumSet<TokenType> ${varName} = EnumSet.noneOf(TokenType.class);
   [#elseif tokenNames?size < 8]
    private static final EnumSet<TokenType> ${varName} = tokenTypeSet(
       [#list tokenNames as type]
         [#if type_index > 0],[/#if]
         ${type}
       [/#list]
    );
   [#else]
    private static final EnumSet<TokenType> ${varName} = ${varName}_init();
    private static EnumSet<TokenType> ${varName}_init() {
       return tokenTypeSet(
         [#list tokenNames as type]
          [#if type_index > 0],[/#if]
           ${type}
         [/#list]
       );
    }
   [/#if]
[/#macro]

[#macro firstSetVar expansion]
    [@enumSet expansion.firstSetVarName expansion.firstSet.tokenNames /]
[/#macro]

[#macro finalSetVar expansion]
    [@enumSet expansion.finalSetVarName expansion.finalSet.tokenNames /]
[/#macro]

[#macro followSetVar expansion]
    [@enumSet expansion.followSetVarName expansion.followSet.tokenNames/]
[/#macro]


[#var newVarIndex=0]
[#-- Just to generate a new unique variable name
  All it does is tack an integer (that is incremented)
  onto the type name, and optionally initializes it to some value--]
[#macro newVar type init=null]
   [#set newVarIndex = newVarIndex+1]
   ${type} ${type?lower_case}${newVarIndex}
   [#if init??]
      = ${init}
   [/#if]
   ;
[/#macro]

[#macro newVarName prefix]
   ${prefix}${newID()}
[/#macro]

[#function newID]
    [#set newVarIndex = newVarIndex+1]
    [#return newVarIndex]
[/#function]

[#-- A macro to use at one's convenience to comment out a block of code --]
[#macro comment]
[#var content, lines]
[#set content][#nested/][/#set]
[#set lines = content?split("\n")]
[#list lines as line]
// ${line}
[/#list]
[/#macro]

[#function bool val]
   [#return val?string("true", "false")/]
[/#function]

[#macro HandleLexicalStateChange expansion inLookahead]
   [#var resetToken = inLookahead?string("currentLookaheadToken", "lastConsumedToken")]
   [#var prevLexicalStateVar = newVarName("previousLexicalState")]
   [#if expansion.specifiedLexicalState??]
         LexicalState ${prevLexicalStateVar} = token_source.lexicalState;
         token_source.reset(${resetToken}, LexicalState.${expansion.specifiedLexicalState});
         try {
           [#nested/]
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
   [#elseif expansion.tokenActivation??]
      [#var tokenActivation = expansion.tokenActivation]
      [#var prevActives = newVarName("previousActives")]
      [#var somethingChanged = newVarName("somethingChanged")]
      EnumSet<TokenType> ${prevActives} = EnumSet.copyOf(token_source.activeTokenTypes);
      boolean ${somethingChanged} = false;
      [#if tokenActivation.activatedTokens?size>0]
         ${somethingChanged} = activateTokenTypes(
         [#list tokenActivation.activatedTokens as tokenName]
             ${tokenName}[#if tokenName_has_next],[/#if]
         [/#list]
         );
      [/#if]
      [#if tokenActivation.deactivatedTokens?size>0]
         ${somethingChanged} = ${somethingChanged} |= deactivateTokenTypes(
         [#list tokenActivation.deactivatedTokens as tokenName]
             ${tokenName}[#if tokenName_has_next],[/#if]
         [/#list]
         );
      [/#if]
      try {
         [#nested/]
      }
      finally {
         token_source.activeTokenTypes = ${prevActives};
         if (${somethingChanged}) {
             token_source.reset(${resetToken});
             nextTokenType= null;
         }
      }
   [#else]
      [#nested/]
   [/#if]
[/#macro]
