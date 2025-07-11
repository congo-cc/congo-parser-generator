[#-- A place to put some utility routines used in various templates. Currently doesn't
     really have much! --]

[#var TT = "TokenType."]

[#--
  This macro is slightly different to the similarly-named one in lexer.py.ctl. This
  variant is intended for setting class-level first/final/follow sets, so 'self.' is not
  present and the indentation is fixed at 4 (class member level).
--]
[#var cache_sets = true]
[#macro enumSet varName tokenNames]
[#var size = tokenNames?size]
[#if size = 0]
    ${varName} = EMPTY_SET
[#elseif cache_sets]
    ${varName} = make_frozenset(
  [#list tokenNames as type]
        ${type}[#if type_has_next],[/#if]
  [/#list]
    )
[#else]
    ${varName} = frozenset({
  [#list tokenNames as type]
        ${type}[#if type_has_next],[/#if]
  [/#list]
    })
[/#if]

[/#macro]

[#macro firstSetVar expansion]
    [@enumSet expansion.firstSetVarName, expansion.firstSet.tokenNames /]
[/#macro]

[#--
[#macro finalSetVar expansion]
    [@enumSet expansion.finalSetVarName, expansion.finalSet.tokenNames /]
[/#macro]
--]

[#macro followSetVar expansion]
    [@enumSet expansion.followSetVarName, expansion.followSet.tokenNames /]
[/#macro]


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
${prefix}${newID()}[#rt]
[/#macro]

[#function newID]
    [#set newVarIndex = newVarIndex + 1]
    [#return newVarIndex]
[/#function]

[#-- A macro to use at one's convenience to comment out a block of code
[#macro comment]
[#var content, lines]
[#set content][#nested/][/#set]
[#set lines = content?split("\n")]
[#list lines as line]
// ${line}
[/#list]
[/#macro]
 --]

[#function bool val]
[#return val?string("True", "False")/]
[/#function]

[#macro HandleLexicalStateChange expansion inLookahead indent]
[#var is = ""?right_pad(indent)]
[#-- ${is}# DBG > HandleLexicalStateChange ${indent} ${expansion.simpleName} --]
[#var resetToken = inLookahead?string("self.current_lookahead_token", "self.last_consumed_token")]
[#if expansion.specifiedLexicalState??]
  #if inLookahead
${is}if self.hit_failure:
${is}    return False
${is}if self.remaining_lookahead <= 0:
${is}    return True
  #endif
  [#var prevLexicalStateVar = newVarName("previousLexicalState")]
${is}${prevLexicalStateVar} = self.token_source.lexical_state
${is}self.token_source.reset(${resetToken}, LexicalState.${expansion.specifiedLexicalState})
${is}try:
[#nested indent + 4 /]
${is}finally:
${is}    if ${prevLexicalStateVar} != LexicalState.${expansion.specifiedLexicalState}:
${is}        if ${resetToken}.next:
${is}            self.token_source.reset(${resetToken}, ${prevLexicalStateVar})
${is}        else:
${is}            self.token_source.switch_to(${prevLexicalStateVar})
${is}        self._next_token_type = None
[#elseif expansion.tokenActivation??]
  [#var tokenActivation = expansion.tokenActivation]
  [#var methodName = "activate_token_types"]
  [#if tokenActivation.deactivate]
    [#set methodName = "deactivate_token_types"]
  [/#if]
  [#var prevActives = newVarName("previousActives")]
  [#var somethingChanged = newVarName("somethingChanged")]
  #if inLookahead
${is}if self.hit_failure:
${is}    return False
${is}if self.remaining_lookahead <= 0:
${is}    return True
  #endif
${is}${somethingChanged} = False
${is}${prevActives} = _Set(self.token_source.active_token_types)
[#if expansion.tokenActivation.activatedTokens?size > 0]
${is}${somethingChanged} = ${somethingChanged} or self.activate_token_types(
  [#list tokenActivation.activatedTokens as tokenName]
${is}    ${tokenName}[#if tokenName_has_next],[/#if]
  [/#list]
${is})
[/#if]
[#if expansion.tokenActivation.deactivatedTokens?size > 0]
${is}${somethingChanged} = ${somethingChanged} or self.deactivate_token_types(
  [#list tokenActivation.deactivatedTokens as tokenName]
${is}    ${tokenName}[#if tokenName_has_next],[/#if]
  [/#list]
${is})
[/#if]
${is}try:
  [#nested indent + 4]
${is}finally:
${is}    self.token_source.active_token_types = ${prevActives}
${is}    if ${somethingChanged}:
${is}        self.token_source.reset(${resetToken})
${is}        self._next_token_type = None
[#else]
  [#nested indent]
[/#if]
[#-- ${is}# DBG < HandleLexicalStateChange ${indent} ${expansion.simpleName} --]
[/#macro]

