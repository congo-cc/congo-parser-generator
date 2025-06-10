[#-- A place to put some utility routines used in various templates. Currently doesn't
     really have much! --]

[#var TT = "TokenType."]

[#macro enumSet varName tokenNames]
[#var size = tokenNames?size]
[#if size = 0]
private static readonly HashSet<TokenType> ${varName} = Utils.GetOrMakeSet();
[#else]
private static readonly HashSet<TokenType> ${varName} = Utils.GetOrMakeSet(
[#list tokenNames as type]
    TokenType.${type}[#if type_has_next],[/#if]
[/#list]
);
[/#if]

[/#macro]

#macro firstSetVar expansion
    [@enumSet expansion.firstSetVarName, expansion.firstSet.tokenNames /]
#endmacro

[#macro finalSetVar expansion]
    [@enumSet expansion.finalSetVarName, expansion.finalSet.tokenNames /]
[/#macro]

#macro followSetVar expansion
    [@enumSet expansion.followSetVarName, expansion.followSet.tokenNames /]
#endmacro


[#var newVarIndex = 0]
[#-- Just to generate a new unique variable name
  All it does is tack an integer (that is incremented)
  onto the type name, and optionally initializes it to some value
[#macro newVar type init = null]
   [#set newVarIndex = newVarIndex + 1]
   ${type} ${type?lower_case}${newVarIndex}
   [#if init??]
      = ${init}
   [/#if]
   ;
[/#macro]
--]
[#macro newVarName prefix]
  ${prefix}${newID()}
[/#macro]

[#function newID]
    [#set newVarIndex = newVarIndex + 1]
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
[#-- # DBG > HandleLexicalStateChange ${expansion.simpleName} --]
[#var resetToken = inLookahead?string("currentLookaheadToken", "LastConsumedToken")]
[#if expansion.specifiedLexicalState??]
  [#var prevLexicalStateVar = newVarName("previousLexicalState")]
  #if inLookahead
if (_hitFailure) return false;
if (_remainingLookahead <= 0) return true;
  #endif
LexicalState ${prevLexicalStateVar} = tokenSource.LexicalState;
tokenSource.Reset(${resetToken}, LexicalState.${expansion.specifiedLexicalState});
try {
#nested
}
finally {
    if (${prevLexicalStateVar} != LexicalState.${expansion.specifiedLexicalState}) {
        if (${resetToken}.Next != null) {
            tokenSource.Reset(${resetToken}, ${prevLexicalStateVar});
        }
        else {
            tokenSource.SwitchTo(${prevLexicalStateVar});
        }
        _nextTokenType = null;
    }
}
[#elseif expansion.tokenActivation??]
  [#var tokenActivation = expansion.tokenActivation]
  [#var prevActives = newVarName("previousActives")]
  [#var somethingChanged = newVarName("somethingChanged")]
  #if inLookahead
if (_hitFailure) return false;
if (_remainingLookahead <= 0) return true;
  #endif
var ${prevActives} = new HashSet<TokenType>(tokenSource.ActiveTokenTypes);
var ${somethingChanged} = false;
[#if tokenActivation.activatedTokens?size > 0]
${somethingChanged} = ActivateTokenTypes(
  [#list tokenActivation.activatedTokens as tokenName]
    ${TT}${tokenName}${tokenName_has_next ?: ","}
  [/#list]
);
[/#if]
[#if tokenActivation.deactivatedTokens?size > 0]
${somethingChanged} = ${somethingChanged} || DeactivateTokenTypes(
  [#list tokenActivation.deactivatedTokens as tokenName]
    ${TT}${tokenName}[#if tokenName_has_next],[/#if]
  [/#list]
);
[/#if]
try {
  #nested
}
finally {
    tokenSource.ActiveTokenTypes = ${prevActives};
    if (${somethingChanged}) {
        tokenSource.Reset(GetToken(0));
        _nextTokenType = null;
    }
}
#else
  #nested 
#endif
[#-- # DBG < HandleLexicalStateChange ${expansion.simpleName} --]
#endmacro

