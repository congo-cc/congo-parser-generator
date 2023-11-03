[#-- A place to put some utility routines used in various templates. Currently doesn't
     really have much! --]

[#var TT = "TokenType."]

[#macro enumSet varName tokenNames indent=0]
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

[#macro firstSetVar expansion]
    [@enumSet expansion.firstSetVarName expansion.firstSet.tokenNames 8 /]
[/#macro]

[#macro finalSetVar expansion]
    [@enumSet expansion.finalSetVarName expansion.finalSet.tokenNames 8 /]
[/#macro]

[#macro followSetVar expansion]
    [@enumSet expansion.followSetVarName expansion.followSet.tokenNames 8 /]
[/#macro]


[#var newVarIndex=0]
[#-- Just to generate a new unique variable name
  All it does is tack an integer (that is incremented)
  onto the type name, and optionally initializes it to some value
[#macro newVar type init=null]
   [#set newVarIndex = newVarIndex+1]
   ${type} ${type?lower_case}${newVarIndex}
   [#if init??]
      = ${init}
   [/#if]
   ;
[/#macro]
--]
[#macro newVarName prefix]
${prefix}${newID()}[#rt]
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

[#macro HandleLexicalStateChange expansion inLookahead indent]
[#-- # DBG > HandleLexicalStateChange ${indent} ${expansion.simpleName} --]
[#var resetToken = inLookahead?string("currentLookaheadToken", "LastConsumedToken")]
[#if expansion.specifiedLexicalState??]
  [#var prevLexicalStateVar = newVarName("previousLexicalState")]
LexicalState ${prevLexicalStateVar} = tokenSource.LexicalState;
tokenSource.Reset(${resetToken}, LexicalState.${expansion.specifiedLexicalState});
try {
[#nested indent + 8 /]
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
var ${prevActives} = new HashSet<TokenType>(tokenSource.ActiveTokenTypes);
var ${somethingChanged} = false;
[#if tokenActivation.activatedTokens?size > 0]
${somethingChanged} = ActivateTokenTypes(
  [#list tokenActivation.activatedTokens as tokenName]
    ${TT}${tokenName}[#if tokenName_has_next],[/#if]
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
  [#nested indent + 4 /]
}
finally {
    tokenSource.ActiveTokenTypes = ${prevActives};
    if (${somethingChanged}) {
        tokenSource.Reset(GetToken(0));
        _nextTokenType = null;
    }
}
[#else]
  [#nested indent /]
[/#if]
[#-- # DBG < HandleLexicalStateChange ${indent} ${expansion.simpleName} --]
[/#macro]

