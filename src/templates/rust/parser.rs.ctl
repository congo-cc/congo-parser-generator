[#-- parser.rs.ctl: the recursive descent parser of the Rust target.

     This template is a port of the Java target's parser templates:
       * ParserProductions.java.ctl (the code of the productions),
       * LookaheadRoutines.java.ctl (the lookahead routines),
       * CommonUtils.java.ctl (HandleLexicalStateChange),
       * the runtime in Parser.java.ctl, ErrorHandling.java.ctl and
         TreeBuildingCode.java.ctl.
     The generated parser makes the same decisions as the generated Java
     parser for the same grammar: the same lookahead routines with the same
     scan limits, the same token-type activation and lexical-state handling,
     the same tree-building rules.  Each macro names the Java macro it ports;
     its comments explain where Rust needs a different shape:

       * Java try/finally becomes an immediately-called closure followed by
         the finally code, so that the finally code also runs when the
         closure exits early;
       * Java's ParseException becomes Err(Box<ParseError>), propagated with
         `?` (boxed so that the Result returned by every production stays
         one word wide);
       * a Java `return x` inside lookahead code becomes `return Some(x)`
         inside a closure returning Option<bool>, where None means "the code
         ran to its end";
       * Java's lastConsumedToken/currentLookaheadToken become the positions
         `pos`/`scan_pos` in the parser's token buffer.

     Java code embedded in the grammar (code actions, semantic lookaheads,
     assertions, tree-building conditions, production arguments and the
     members injected with INJECT PARSER_CLASS) is translated by
     org.congocc.codegen.rust.RustCodeEmitter through the globals.rust*
     methods, which report at generation time whatever cannot be translated.

     Not implemented (reported by RustCodeEmitter): fault-tolerant parsing,
     repetition cardinality constraints, TRY/CATCH and ATTEMPT/RECOVER
     blocks, synthetic (JTB) nodes, Java hook methods.

     Template conventions: generated temporaries start with "__" so they
     cannot collide with translated grammar variables (the translator never
     produces such names); newID() makes them unique within the file. --]
[#var newVarIndex = 0]
[#function newID]
    [#set newVarIndex = newVarIndex + 1]
    [#return newVarIndex]
[/#function]
[#-- Java CommonUtils.USE_FIRST_SET_THRESHOLD: below this many token types a
     condition tests the types one by one; from it on, it uses the first set. --]
[#var USE_FIRST_SET_THRESHOLD = 5]
[#var currentProduction]
[#-- The variable that tells a OneOrMore whose nested expansion is a choice
     whether it is in its first iteration (Java inFirstVarName). --]
[#var inFirstVarName = ""]
[#-- The label of the innermost loop generated for a ZeroOrMore/OneOrMore:
     a choice nested directly in a loop leaves it with `break`. --]
[#var loopLabel = ""]
[#-- Where lookahead code stores the passed-predicate threshold.  In Java, a
     scan routine without a scan limit declares a local variable called
     passedPredicateThreshold that shadows the field of the same name for the
     rest of the routine; BuildScanRoutine switches this to that local. --]
[#var pptTarget = "self.passed_predicate_threshold"]
[#var legacyGlitchyLookahead = settings::legacyGlitchyLookahead!false]

[#-- ====================================================================
     Functions
     ==================================================================== --]

[#-- Java resolveTreeNodeBehavior, without the synthetic (JTB) nodes that
     the Rust target does not implement: the explicit tree-building
     annotation, or the default node of a production. --]
[#function resolveTreeNodeBehavior expansion]
   [#var treeNodeBehavior = expansion::treeNodeBehavior]
   [#if !treeNodeBehavior?? && expansion::simpleName = "BNFProduction"
        && !settings::nodeDefaultVoid
        && !grammar.nodeIsInterface(expansion::name)
        && !grammar.nodeIsAbstract(expansion::name)]
      [#if settings::smartNodeCreation]
         [#set treeNodeBehavior = {"nodeName" : expansion::name, "condition" : "1", "gtNode" : true, "initialShorthand" : ">"}]
      [#else]
         [#set treeNodeBehavior = {"nodeName" : expansion::name, "gtNode" : false}]
      [/#if]
   [/#if]
   [#if (treeNodeBehavior!::neverInstantiated)!]
      [#return null]
   [/#if]
   [#return treeNodeBehavior]
[/#function]

[#function nodeClassName treeNodeBehavior]
   [#if treeNodeBehavior?? && treeNodeBehavior::nodeName??]
      [#return treeNodeBehavior::nodeName]
   [/#if]
   [#return currentProduction::name]
[/#function]

[#-- A slice expression with the given token types: &[TokenType::A, TokenType::B] --]
[#function tokenTypeList names]
   [#var result = ""]
   [#list names as name]
      [#set result = result + "TokenType::" + name]
      [#if name_has_next]
         [#set result = result + ", "]
      [/#if]
   [/#list]
   [#return "&[" + result + "]"]
[/#function]

[#-- ====================================================================
     First sets (Java CommonUtils.firstSetVar): one constant per expansion,
     a bit set that tests membership in O(1) like Java's EnumSet.
     ==================================================================== --]
[#macro firstSetVar expansion]
const ${expansion::firstSetVarName}: TokenTypeSet = TokenTypeSet::from_slice(&[
[#list expansion::firstSet::tokenNames as name]
    TokenType::${name},
[/#list]
]);
[/#macro]

[#-- ====================================================================
     Productions (Java ParserProductions.java.ctl)
     ==================================================================== --]

[#macro PushCallStack productionName node]
        self.push_onto_call_stack("${productionName}", ${globals.rustStringLiteral(node::inputSource)}, ${node::beginLine}, ${node::beginColumn});
[/#macro]

[#-- Java ParserProduction: the function of a production. --]
[#macro ParserProduction production]
    [#set newVarIndex = 0]
    [#set currentProduction = production]
    // ${production::location}
    fn parse_${production::name}(&mut self${globals.rustProductionParameters(production)}) -> PResult {
        self.currently_parsed_production = "${production::name}";
[@BuildCode production /]
        Ok(())
    }

[/#macro]

[#-- Java BuildCode --]
[#macro BuildCode expansion]
[#if expansion::simpleName != "ExpansionSequence" && expansion::simpleName != "ExpansionWithParentheses"]
        // Code for ${expansion::simpleName} specified at ${expansion::location}
[/#if]
[@HandleLexicalStateChange expansion, false]
[@BuildExpansionCode expansion /]
[/@HandleLexicalStateChange]
[/#macro]

[#-- Java CommonUtils.HandleLexicalStateChange: runs the nested code in the
     lexical state given with LEXICAL_STATE, or with the token types given with
     ACTIVATE_TOKENS/DEACTIVATE_TOKENS, and restores the previous state
     afterwards (Java finally block), re-lexing the tokens that were lexed
     ahead under the temporary state.  In lookahead code (inLookahead) the
     reset point is the lookahead position and the nested code returns
     Option<bool>; in production code it is the last consumed token and the
     nested code returns PResult. --]
[#macro HandleLexicalStateChange expansion inLookahead]
[#var base = inLookahead ? "self.scan_pos" : "self.pos"]
[#var resultType = inLookahead ? "Option<bool>" : "PResult"]
[#var fallThrough = inLookahead ? "None" : "Ok(())"]
[#if expansion::specifiedLexicalState??]
[#var id = newID()]
[#var newState = "LexicalState::" + expansion::specifiedLexicalState]
        let __previous_lexical_state_${id} = self.lexer.lexical_state();
        self.reset_to(${base}, Some(${newState}));
        let __result_${id} = (|| -> ${resultType} {
[#nested]
            ${fallThrough}
        })();
        if __previous_lexical_state_${id} != ${newState} {
            // Java: if (resetToken.getNext() != null) reset(...) else switchTo(...)
            if self.tokens.len() > ${base} {
                self.reset_to(${base}, Some(__previous_lexical_state_${id}));
            } else {
                self.lexer.switch_to(__previous_lexical_state_${id});
            }
        }
[#if inLookahead]
        if __result_${id}.is_some() {
            return __result_${id};
        }
[#else]
        __result_${id}?;
[/#if]
[#elseif expansion::tokenActivation??]
[#var id = newID()]
[#var tokenActivation = expansion::tokenActivation]
        let __previous_actives_${id} = self.lexer.active_snapshot();
        let mut __something_changed_${id} = false;
[#if tokenActivation::activatedTokens]
        __something_changed_${id} = self.activate_token_types(${tokenTypeList(tokenActivation::activatedTokens)});
[/#if]
[#if tokenActivation::deactivatedTokens]
        __something_changed_${id} |= self.deactivate_token_types(${tokenTypeList(tokenActivation::deactivatedTokens)});
[/#if]
        let __result_${id} = (|| -> ${resultType} {
[#nested]
            ${fallThrough}
        })();
        self.lexer.restore_active_snapshot(__previous_actives_${id});
        if __something_changed_${id} {
            self.reset_to(${base}, None);
        }
[#if inLookahead]
        if __result_${id}.is_some() {
            return __result_${id};
        }
[#else]
        __result_${id}?;
[/#if]
[#else]
[#nested]
[/#if]
[/#macro]

[#-- Java BuildExpansionCode --]
[#macro BuildExpansionCode expansion]
[#var classname = expansion::simpleName]
[#if classname = "CodeBlock"]
${globals.rustAction(expansion, 8)}
[#elseif classname = "RawCode"]
[#if !expansion::wrongLanguageIgnore]
        ${expansion::rawContent}
[/#if]
[#elseif classname = "UncacheTokens"]
        self.uncache_tokens();
[#elseif classname = "Failure"]
[@BuildCodeFailure expansion /]
[#elseif classname = "Assertion"]
[#if expansion::appliesInRegularParsing]
[@BuildAssertionCode expansion /]
[/#if]
[#elseif classname = "TokenTypeActivation"]
[@BuildCodeTokenTypeActivation expansion /]
[#elseif classname = "TryBlock"]
${globals.rustUnsupported(expansion, "TRY/CATCH blocks", "the expansion is parsed without the CATCH and FINALLY code")}
[@BuildCode expansion::nestedExpansion /]
[#elseif classname = "AttemptBlock"]
${globals.rustUnsupported(expansion, "ATTEMPT/RECOVER blocks", "the ATTEMPT expansion is parsed without recovery")}
[@BuildCode expansion::nestedExpansion /]
[#else]
[@TreeBuildingAndRecovery expansion]
[#if classname = "BNFProduction"]
[@BuildCode expansion::nestedExpansion /]
[#elseif classname = "NonTerminal"]
[@BuildCodeNonTerminal expansion /]
[#elseif classname = "Terminal"]
[@BuildCodeTerminal expansion /]
[#elseif classname = "ZeroOrOne"]
[@BuildCodeZeroOrOne expansion /]
[#elseif classname = "ZeroOrMore"]
[@BuildCodeZeroOrMore expansion /]
[#elseif classname = "OneOrMore"]
[@BuildCodeOneOrMore expansion /]
[#elseif classname = "ExpansionChoice"]
[@BuildCodeChoice expansion /]
[#elseif classname = "ExpansionWithParentheses"]
[@BuildExpansionCode expansion::nestedExpansion /]
[#elseif classname = "ExpansionSequence"]
[@BuildCodeSequence expansion /]
[/#if]
[/@TreeBuildingAndRecovery]
[/#if]
[/#macro]

[#-- Java TreeBuildingAndRecovery (without fault-tolerant recovery): opens a
     node scope, runs the nested code, and closes the scope according to the
     tree-building annotation; on a parse error the scope is cleared. --]
[#macro TreeBuildingAndRecovery expansion]
[#var treeNodeBehavior = resolveTreeNodeBehavior(expansion)]
[#if !treeNodeBehavior?? || !settings::treeBuildingEnabled]
[#nested]
[#else]
[#var id = newID()]
        let __building_${id} = self.open_node(NodeKind::${nodeClassName(treeNodeBehavior)});
        let __call_stack_size_${id} = self.call_stack.len();
        let __result_${id} = (|| -> PResult {
[#nested]
            Ok(())
        })();
        self.restore_call_stack(__call_stack_size_${id});
        if __building_${id} {
            if __result_${id}.is_ok() {
                let __end_offset = self.last_consumed_end();
                ${globals.rustCloseNodeScope(treeNodeBehavior::condition!"", treeNodeBehavior::gtNode!false, treeNodeBehavior::initialShorthand!"", expansion, "__end_offset")}
[#if treeNodeBehavior::assignment??]
                ${globals.rustUnsupported(expansion, "assignments of nodes built by tree-building annotations", "the assignment is not performed")}
[/#if]
            } else {
                self.builder.clear_node_scope();
            }
        }
        __result_${id}?;
[/#if]
[/#macro]

[#-- Java BuildCodeFailure --]
[#macro BuildCodeFailure fail]
[#if fail::hasRunnableBlock]
${globals.rustFailureCode(fail, 8)}
[#else]
[@PushCallStack fail::containingProduction::name, fail /]
        {
            let __message = ${globals.rustFailureMessage(fail)};
            let __location = self.get_token(1);
            self.fail_at(__message, __location)?;
        }
[/#if]
[/#macro]

[#-- Java BuildAssertionCode --]
[#macro BuildAssertionCode assertion]
[#if assertion::lookBehind??]
        if ${!assertion::lookBehind::negated ? "!"}self.${assertion::lookBehind::routineName}() {
[@AssertionFailure assertion /]
        }
[#elseif assertion::assertionExpression??]
        if !(${globals.rustAssertionCondition(assertion, true)}) {
[@AssertionFailure assertion /]
        }
[#elseif assertion::rawCode?? && !assertion::rawCode::wrongLanguageIgnore]
        if !(${globals.rustAssertionCondition(assertion, true)}) {
[@AssertionFailure assertion /]
        }
[#elseif assertion::cardinalityConstraint??]
        // The repetition cardinality constraint at ${assertion::location} is not checked (not implemented by the Rust target).
[/#if]
[#if assertion::expansion??]
        if ${!assertion::expansionNegated ? "!"}self.${assertion::expansion::scanRoutineName}() {
[@AssertionFailure assertion /]
        }
[/#if]
[/#macro]

[#macro AssertionFailure assertion]
[#var location = globals.rustAssertionLocation(assertion)]
[@PushCallStack assertion::containingProduction::name, assertion /]
            {
                let __message = ${globals.rustAssertionMessage(assertion)};
                let __location = [#if location]${location}[#else]self.get_token(1)[/#if];
                self.fail_at(__message, __location)?;
            }
[/#macro]

[#-- Java BuildCodeTokenTypeActivation --]
[#macro BuildCodeTokenTypeActivation activation]
[#if activation::deactivate]
        self.deactivate_token_types(${tokenTypeList(activation::tokenNames)});
[#else]
        self.activate_token_types(${tokenTypeList(activation::tokenNames)});
[/#if]
[/#macro]

[#-- Java BuildCodeNonTerminal: the call stack entry is popped even when the
     production fails (Java finally block). --]
[#macro BuildCodeNonTerminal nonterminal]
[@PushCallStack nonterminal::containingProduction::name, nonterminal /]
        let __result = ${globals.rustNonTerminalCall(nonterminal)};
        self.pop_call_stack();
        __result?;
${globals.rustAssignment(nonterminal, 8, "")}
[/#macro]

[#-- Java BuildCodeTerminal --]
[#macro BuildCodeTerminal terminal]
[#var assignmentCode = globals.rustAssignment(terminal, 8, "__token")]
[@PushCallStack currentProduction::name, terminal /]
        let __result = self.consume_token(TokenType::${terminal::regexp::label});
        self.pop_call_stack();
[#if assignmentCode]
        let __token = __result?;
${assignmentCode}
[#else]
        __result?;
[/#if]
[/#macro]

[#-- Java BuildCodeZeroOrOne --]
[#macro BuildCodeZeroOrOne zoo]
[#if zoo::nestedExpansion::class::simpleName = "ExpansionChoice"]
[@BuildCode zoo::nestedExpansion /]
[#else]
        if [@ExpansionCondition zoo::nestedExpansion /] {
[@BuildCode zoo::nestedExpansion /]
        }
[/#if]
[/#macro]

[#-- Java BuildCodeOneOrMore --]
[#macro BuildCodeOneOrMore oom]
[#var nestedExp = oom::nestedExpansion]
[#var prevInFirstVarName = inFirstVarName]
[#var prevLoopLabel = loopLabel]
[#var id = newID()]
[#set loopLabel = "'__loop" + id]
[#if nestedExp::simpleName = "ExpansionChoice"]
[#set inFirstVarName = "__in_first_" + id]
        let mut ${inFirstVarName} = true;
[/#if]
        ${loopLabel}: loop {
[@BuildCode nestedExp /]
[#if nestedExp::simpleName = "ExpansionChoice"]
            ${inFirstVarName} = false;
[#else]
            if !([@ExpansionCondition nestedExp /]) {
                break ${loopLabel};
            }
[/#if]
        }
[#set inFirstVarName = prevInFirstVarName]
[#set loopLabel = prevLoopLabel]
[/#macro]

[#-- Java BuildCodeZeroOrMore --]
[#macro BuildCodeZeroOrMore zom]
[#var prevLoopLabel = loopLabel]
[#var id = newID()]
[#set loopLabel = "'__loop" + id]
        ${loopLabel}: loop {
[#if zom::nestedExpansion::class::simpleName != "ExpansionChoice"]
            if !([@ExpansionCondition zom::nestedExpansion /]) {
                break ${loopLabel};
            }
[/#if]
[@BuildCode zom::nestedExpansion /]
        }
[#set loopLabel = prevLoopLabel]
[/#macro]

[#-- Java BuildCodeChoice.  A choice nested directly in a loop leaves the
     loop when no alternative applies (the closures generated for tree
     building and lexical changes never come between the two, because a
     choice directly inside a loop cannot have its own tree-building
     annotation or lexical change). --]
[#macro BuildCodeChoice choice]
[#list choice::choices as expansion]
[#if expansion::enteredUnconditionally]
        {
[@BuildCode expansion /]
        }
[#if expansion_has_next]
        // Warning: the choices after the one at ${expansion::location} are ignored
        // because that choice is entered unconditionally.
[/#if]
[#return]
[/#if]
        if [@ExpansionCondition expansion /] {
[@BuildCode expansion /]
        }[#if expansion_has_next] else[/#if]
[/#list]
[#if choice::parent::simpleName = "ZeroOrMore"]
        else {
            break ${loopLabel};
        }
[#elseif choice::parent::simpleName = "OneOrMore"]
        else if ${inFirstVarName} {
[@PushCallStack currentProduction::name, choice /]
            return Err(self.unexpected_token_error(&${choice::firstSetVarName}));
        } else {
            break ${loopLabel};
        }
[#elseif choice::parent::simpleName != "ZeroOrOne"]
        else {
[@PushCallStack currentProduction::name, choice /]
            return Err(self.unexpected_token_error(&${choice::firstSetVarName}));
        }
[/#if]
[/#macro]

[#macro BuildCodeSequence expansion]
[#list expansion::units as subexp]
[@BuildCode subexp /]
[/#list]
[/#macro]

[#-- Java ExpansionCondition: the condition for entering an expansion. --]
[#macro ExpansionCondition expansion]
[#if expansion::requiresPredicateMethod][@ScanAheadCondition expansion /][#else][@SingleTokenCondition expansion /][/#if]
[/#macro]

[#-- Java ScanAheadCondition --]
[#macro ScanAheadCondition expansion]
[#if expansion::lookahead?? && expansion::lookahead::assignment??]
{
${globals.rustUnsupported(expansion, "assignments of the result of a lookahead", "the result is not assigned")}
[/#if]
[#if expansion::hasSemanticLookahead && !expansion::lookahead::semanticLookaheadNested](${globals.rustPredicate(expansion, true)}) && [/#if]self.${expansion::predicateMethodName}()
[#if expansion::lookahead?? && expansion::lookahead::assignment??]
}
[/#if]
[/#macro]

[#-- Java SingleTokenCondition.  As in Java, a semantic lookahead binds
     only to the first token test: (semantic) && A || B. --]
[#macro SingleTokenCondition expansion]
[#var names = expansion::firstSet::tokenNames]
[#if expansion::hasSemanticLookahead](${globals.rustPredicate(expansion, true)}) && [/#if][#if names.size() == 0]false[#elseif names.size() < USE_FIRST_SET_THRESHOLD][#list names as name]self.next_token_matches(TokenType::${name})[#if name_has_next] || [/#if][/#list][#else]self.next_token_in(&${expansion::firstSetVarName})[/#if]
[/#macro]

[#-- ====================================================================
     Lookahead routines (Java LookaheadRoutines.java.ctl).  Every routine
     body runs in a closure returning Option<bool>: Some(x) is Java's
     `return x`, None means the code ran to its end.  The code after the
     closure is Java's finally block.
     ==================================================================== --]

[#macro GenerateLookaheads]
[#if grammar::choicePointExpansions]
[#list grammar::choicePointExpansions as expansion]
[#if expansion::parent::class::simpleName != "BNFProduction"]
[@BuildScanRoutine expansion /]
[/#if]
[/#list]
[#list grammar::assertionExpansions as expansion]
[@BuildAssertionRoutine expansion /]
[/#list]
[#list grammar::expansionsNeedingPredicate as expansion]
[@BuildPredicateRoutine expansion /]
[/#list]
[#list grammar::allLookaheads as lookahead]
[#if lookahead::nestedExpansion??]
[@BuildLookaheadRoutine lookahead /]
[/#if]
[/#list]
[#list grammar::allLookBehinds as lookBehind]
[@BuildLookBehindRoutine lookBehind /]
[/#list]
[#list grammar::parserProductions as production]
[@BuildProductionLookaheadMethod production /]
[/#list]
[/#if]
[/#macro]

[#-- Java BuildPredicateRoutine --]
[#macro BuildPredicateRoutine expansion]
[#var needsScanCode = !expansion::hasSeparateSyntacticLookahead && expansion::lookaheadAmount > 0]
[#var lookaheadAmount = expansion::lookaheadAmount == 2147483647 ? "UNLIMITED" : expansion::lookaheadAmount]
[#set newVarIndex = 0]
    // BuildPredicateRoutine: ${expansion::simpleName} at ${expansion::location}
    fn ${expansion::predicateMethodName}(&mut self) -> bool {
        self.remaining_lookahead = ${lookaheadAmount};
        self.in_lookahead = true;
        self.scan_pos = self.pos;
        let scan_to_end = false;
        let __result = (|| -> Option<bool> {
[@BuildPredicateCode expansion /]
[#if needsScanCode]
[@BuildScanCode expansion /]
[/#if]
            Some(true)
        })();
        self.lookahead_routine_nesting = 0;
        self.in_lookahead = false;
        self.current_lookahead_production = "";
        self.hit_failure = false;
        __result.unwrap_or(true)
    }

[/#macro]

[#-- Java BuildScanRoutine --]
[#macro BuildScanRoutine expansion]
[#if !expansion::singleTokenLookahead]
[#set newVarIndex = 0]
    // scanahead routine for expansion at:
    // ${expansion::location}
    fn ${expansion::scanRoutineName}(&mut self, scan_to_end: bool) -> bool {
[#if expansion::hasScanLimit]
        let __previous_passed_predicate_threshold = self.passed_predicate_threshold;
        self.passed_predicate_threshold = -1;
[#else]
        let mut __reached_scan_code = false;
        let mut __passed_predicate_threshold = self.remaining_lookahead.wrapping_sub(${expansion::lookaheadAmount});
[#set pptTarget = "__passed_predicate_threshold"]
[/#if]
        self.lookahead_routine_nesting += 1;
        let __result = (|| -> Option<bool> {
[@BuildPredicateCode expansion /]
[#if !expansion::hasScanLimit]
            __reached_scan_code = true;
[/#if]
[@BuildScanCode expansion /]
            None
        })();
        self.lookahead_routine_nesting -= 1;
[#if expansion::hasScanLimit]
        if self.remaining_lookahead <= self.passed_predicate_threshold {
            self.passed_predicate = true;
            self.passed_predicate_threshold = __previous_passed_predicate_threshold;
        }
[#else]
        if __reached_scan_code && self.remaining_lookahead <= __passed_predicate_threshold {
            self.passed_predicate = true;
        }
[/#if]
[#set pptTarget = "self.passed_predicate_threshold"]
        if let Some(__value) = __result {
            return __value;
        }
        self.passed_predicate = false;
        true
    }

[/#if]
[/#macro]

[#-- Java BuildAssertionRoutine --]
[#macro BuildAssertionRoutine expansion]
[#set newVarIndex = 0]
    // scanahead routine for assertion at:
    // ${expansion::parent::location}
    fn ${expansion::scanRoutineName}(&mut self) -> bool {
        let scan_to_end = true;
        let __stored_remaining_lookahead = self.remaining_lookahead;
        self.remaining_lookahead = UNLIMITED;
        let __stored_in_lookahead = self.in_lookahead;
        let __stored_scan_pos = self.scan_pos;
        let __previous_hit_failure = self.hit_failure;
        if !self.in_lookahead {
            self.in_lookahead = true;
            self.scan_pos = self.pos;
        }
        self.lookahead_routine_nesting += 1;
        let __result = (|| -> Option<bool> {
[@BuildScanCode expansion /]
            Some(true)
        })();
        self.lookahead_routine_nesting -= 1;
        self.in_lookahead = __stored_in_lookahead;
        self.scan_pos = __stored_scan_pos;
        self.remaining_lookahead = __stored_remaining_lookahead;
        self.hit_failure = __previous_hit_failure;
        __result.unwrap_or(true)
    }

[/#macro]

[#-- Java BuildPredicateCode: the semantic lookahead, lookbehind and separate
     syntactic lookahead of an expansion. --]
[#macro BuildPredicateCode expansion]
            // BuildPredicateCode macro
[#if expansion::hasSemanticLookahead && (expansion::lookahead::semanticLookaheadNested || expansion::containingProduction::onlyForLookahead)]
            if !(${globals.rustPredicate(expansion, false)}) {
                return Some(false);
            }
[/#if]
[#if expansion::hasLookBehind]
            if ${!expansion::lookBehind::negated ? "!"}self.${expansion::lookBehind::routineName}() {
                return Some(false);
            }
[/#if]
[#if expansion::hasSeparateSyntacticLookahead]
            if self.remaining_lookahead <= 0 {
                self.passed_predicate = true;
                return Some(!self.hit_failure);
            }
            if ${!expansion::lookahead::negated ? "!"}self.${expansion::lookaheadExpansion::scanRoutineName}(true) {
                return Some(false);
            }
[/#if]
[#if expansion::lookaheadAmount == 0]
            self.passed_predicate = true;
[/#if]
            // End BuildPredicateCode macro
[/#macro]

[#-- Java BuildLookaheadRoutine: the routine for an explicit syntactic
     lookahead, used by a nested lookahead. --]
[#macro BuildLookaheadRoutine lookahead]
[#set newVarIndex = 0]
    // lookahead routine for lookahead at:
    // ${lookahead::location}
    fn ${lookahead::nestedExpansion::scanRoutineName}(&mut self, scan_to_end: bool) -> bool {
        let __previous_remaining_lookahead = self.remaining_lookahead;
        let __previous_hit_failure = self.hit_failure;
        let __previous_in_lookahead = self.in_lookahead;
        let __previous_scan_pos = self.scan_pos;
        self.lookahead_routine_nesting += 1;
        let __result = (|| -> Option<bool> {
[@BuildScanCode lookahead::nestedExpansion /]
            Some(!self.hit_failure)
        })();
        self.lookahead_routine_nesting -= 1;
        self.in_lookahead = __previous_in_lookahead;
        self.scan_pos = __previous_scan_pos;
        self.remaining_lookahead = __previous_remaining_lookahead;
        self.hit_failure = __previous_hit_failure;
        __result.unwrap_or(true)
    }

[/#macro]

[#-- Java BuildLookBehindRoutine: matches the path of a lookbehind
     (\Foo\...\Bar) against the call stacks. --]
[#macro BuildLookBehindRoutine lookBehind]
    fn ${lookBehind::routineName}(&self) -> bool {
        let mut __stack_iterator = self.${lookBehind::backward ? "stack_iterator_backward" : "stack_iterator_forward"}();
[#list lookBehind::path as element]
[#var elementNegated = (element[0] == "~")]
[#var elementName = elementNegated ? element.substring(1) : element]
[#if elementName = "."]
        if !__stack_iterator.has_next(self) {
            return false;
        }
        __stack_iterator.next(self);
[#elseif elementName = "..."]
[#if element_index == lookBehind::path.size() - 1]
[#if lookBehind::hasEndingSlash]
        return !__stack_iterator.has_next(self);
[#else]
        return true;
[/#if]
[#else]
[#var nextElement = lookBehind::path[element_index + 1]]
[#var nextElementNegated = (nextElement[0] == "~")]
[#var nextElementName = nextElementNegated ? nextElement.substring(1) : nextElement]
        while __stack_iterator.has_next(self) {
            let __ntc = __stack_iterator.next(self);
            if __ntc.production_name ${nextElementNegated ? "!=" : "=="} "${nextElementName}" {
                __stack_iterator.previous(self);
                break;
            }
            if !__stack_iterator.has_next(self) {
                return false;
            }
        }
[/#if]
[#else]
        if !__stack_iterator.has_next(self) {
            return false;
        }
        let __ntc = __stack_iterator.next(self);
        if __ntc.production_name ${elementNegated ? "==" : "!="} "${elementName}" {
            return false;
        }
[/#if]
[/#list]
[#if lookBehind::hasEndingSlash]
        !__stack_iterator.has_next(self)
[#else]
        true
[/#if]
    }

[/#macro]

[#-- Java BuildProductionLookaheadMethod --]
[#macro BuildProductionLookaheadMethod production]
[#set newVarIndex = 0]
    // BuildProductionLookaheadMethod for ${production::name}
    fn ${production::lookaheadMethodName}(&mut self, scan_to_end: bool) -> bool {
        let __result = (|| -> Option<bool> {
[@BuildScanCode production::expansion /]
            None
        })();
        __result.unwrap_or(true)
    }

[/#macro]

[#-- Java BuildScanCode: the lookahead code for an expansion. --]
[#macro BuildScanCode expansion]
[#var classname = expansion::simpleName]
[#var skipCheck = classname == "ExpansionSequence" || (classname == "ExpansionWithParentheses" && !expansion.startsWithLexicalChange())]
[#if !skipCheck]
            if self.hit_failure {
                return Some(false);
            }
            if self.remaining_lookahead <= 0 {
                return Some(true);
            }
            // Lookahead Code for ${classname} specified at ${expansion::location}
[/#if]
[@HandleLexicalStateChange expansion, true]
[#if classname = "ExpansionWithParentheses"]
[@BuildScanCode expansion::nestedExpansion /]
[#elseif expansion::singleTokenLookahead || expansion::terminal]
[@ScanSingleToken expansion /]
[#elseif classname = "Assertion"]
[#if expansion::appliesInLookahead]
[@ScanCodeAssertion expansion /]
[/#if]
[#elseif classname = "Failure"]
            if true {
                self.hit_failure = true;
                return Some(false);
            }
[#elseif classname = "UncacheTokens"]
            self.uncache_tokens();
[#elseif classname = "ExpansionSequence"]
[@ScanCodeSequence expansion /]
[#elseif classname = "ZeroOrOne"]
[@ScanCodeZeroOrOne expansion /]
[#elseif classname = "ZeroOrMore"]
[@ScanCodeZeroOrMore expansion /]
[#elseif classname = "OneOrMore"]
[@BuildScanCode expansion::nestedExpansion /]
[@ScanCodeZeroOrMore expansion /]
[#elseif classname = "NonTerminal"]
[@ScanCodeNonTerminal expansion /]
[#elseif classname = "TryBlock" || classname = "AttemptBlock"]
[@BuildScanCode expansion::nestedExpansion /]
[#elseif classname = "ExpansionChoice"]
[@ScanCodeChoice expansion /]
[#elseif classname = "CodeBlock"]
[#if expansion::appliesInLookahead || expansion::insideLookahead || expansion::containingProduction::onlyForLookahead]
${globals.rustLookaheadAction(expansion, 12)}
[/#if]
[#elseif classname = "RawCode"]
[#if (expansion::appliesInLookahead || expansion::insideLookahead || expansion::containingProduction::onlyForLookahead) && !expansion::wrongLanguageIgnore]
            ${expansion::rawContent}
[/#if]
[/#if]
[/@HandleLexicalStateChange]
[/#macro]

[#-- Java ScanCodeSequence --]
[#macro ScanCodeSequence sequence]
[#list sequence::units as sub]
[@BuildScanCode sub /]
[#if sub::scanLimit]
            if !scan_to_end && self.lookahead_stack.len() <= 1 {
                if self.lookahead_routine_nesting == 0 {
                    self.remaining_lookahead = ${sub::scanLimitPlus};
                } else if self.lookahead_stack.len() == 1 {
                    ${pptTarget} = self.remaining_lookahead[#if sub::scanLimitPlus > 0].wrapping_sub(${sub::scanLimitPlus})[/#if];
                }
            }
[/#if]
[/#list]
[/#macro]

[#-- Java ScanCodeNonTerminal: the lookahead stack entry is popped even when
     the scan fails (Java finally block). --]
[#macro ScanCodeNonTerminal nt]
            // NonTerminal ${nt::name} at ${nt::location}
            self.push_onto_lookahead_stack("${nt::containingProduction::name}", ${globals.rustStringLiteral(nt::inputSource)}, ${nt::beginLine}, ${nt::beginColumn});
            self.current_lookahead_production = "${nt::production::name}";
            let __scan_result = self.${nt::production::lookaheadMethodName}(${nt::scanToEnd ? "true" : "false"});
            self.pop_lookahead_stack();
            if !__scan_result {
                return Some(false);
            }
[/#macro]

[#-- Java ScanSingleToken --]
[#macro ScanSingleToken expansion]
[#var names = expansion::firstSet::tokenNames]
[#if names.size() < USE_FIRST_SET_THRESHOLD]
            if !self.scan_token(${tokenTypeList(names)}) {
[#else]
            if !self.scan_token_set(&${expansion::firstSetVarName}) {
[/#if]
                return Some(false);
            }
[/#macro]

[#-- Java ScanCodeAssertion --]
[#macro ScanCodeAssertion assertion]
[#if assertion::lookBehind??]
            if ${!assertion::lookBehind::negated ? "!"}self.${assertion::lookBehind::routineName}() {
                self.hit_failure = true;
                return Some(false);
            }
[#elseif assertion::assertionExpression??]
            if !(${globals.rustAssertionCondition(assertion, false)}) {
                self.hit_failure = true;
                return Some(false);
            }
[#elseif assertion::rawCode?? && !assertion::rawCode::wrongLanguageIgnore]
            if !(${globals.rustAssertionCondition(assertion, false)}) {
                self.hit_failure = true;
                return Some(false);
            }
[/#if]
[#if assertion::expansion??]
            if ${!assertion::expansionNegated ? "!"}self.${assertion::expansion::scanRoutineName}() {
                self.hit_failure = true;
                return Some(false);
            }
[/#if]
[/#macro]

[#-- Java ScanCodeChoice.  The finally block that restores passedPredicate is
     written out before each return, as all the returns are in this code. --]
[#macro ScanCodeChoice choice]
[#var id = newID()]
            let __token_${id} = self.scan_pos;
            let __remaining_lookahead_${id} = self.remaining_lookahead;
            let __hit_failure_${id} = self.hit_failure;
            let __passed_predicate_${id} = self.passed_predicate;
[#list choice::choices as subseq]
            self.passed_predicate = false;
            if ![@CheckExpansion subseq /] {
                self.scan_pos = __token_${id};
                self.remaining_lookahead = __remaining_lookahead_${id};
                self.hit_failure = __hit_failure_${id};
[#if !subseq_has_next]
                self.passed_predicate = __passed_predicate_${id};
                return Some(false);
[#elseif !legacyGlitchyLookahead]
                if self.passed_predicate {
                    self.passed_predicate = __passed_predicate_${id};
                    return Some(false);
                }
[/#if]
[/#list]
[#list choice::choices as unused]
            }
[/#list]
            self.passed_predicate = __passed_predicate_${id};
[/#macro]

[#-- Java ScanCodeZeroOrOne --]
[#macro ScanCodeZeroOrOne zoo]
[#var id = newID()]
            let __token_${id} = self.scan_pos;
            let __passed_predicate_${id} = self.passed_predicate;
            self.passed_predicate = false;
            if ![@CheckExpansion zoo::nestedExpansion /] {
[#if !legacyGlitchyLookahead]
                if self.passed_predicate {
                    self.passed_predicate = __passed_predicate_${id};
                    return Some(false);
                }
[/#if]
                self.scan_pos = __token_${id};
                self.hit_failure = false;
            }
            self.passed_predicate = __passed_predicate_${id};
[/#macro]

[#-- Java ScanCodeZeroOrMore --]
[#macro ScanCodeZeroOrMore zom]
[#var id = newID()]
            let __passed_predicate_${id} = self.passed_predicate;
            '__scan_loop${id}: while self.remaining_lookahead > 0 && !self.hit_failure {
                let __token_${id} = self.scan_pos;
                self.passed_predicate = false;
                if ![@CheckExpansion zom::nestedExpansion /] {
[#if !legacyGlitchyLookahead]
                    if self.passed_predicate {
                        self.passed_predicate = __passed_predicate_${id};
                        return Some(false);
                    }
[/#if]
                    self.scan_pos = __token_${id};
                    break '__scan_loop${id};
                }
            }
            self.passed_predicate = __passed_predicate_${id};
            self.hit_failure = false;
[/#macro]

[#-- Java CheckExpansion: whether the expansion matches at the lookahead position. --]
[#macro CheckExpansion expansion]
[#if expansion::singleTokenLookahead][#if expansion::firstSet::tokenNames.size() < USE_FIRST_SET_THRESHOLD]self.scan_token(${tokenTypeList(expansion::firstSet::tokenNames)})[#else]self.scan_token_set(&${expansion::firstSetVarName})[/#if][#elseif grammar::assertionExpansions?? && grammar::assertionExpansions.contains(expansion)]self.${expansion::scanRoutineName}()[#else]self.${expansion::scanRoutineName}(false)[/#if]
[/#macro]

[#-- ====================================================================
     The generated Rust code
     ==================================================================== --]
[#var rootProduction = null]
[#list grammar::parserProductions as production]
  [#if !production::onlyForLookahead]
    [#set rootProduction = production]
    [#break]
  [/#if]
[/#list]
// Generated by ${generated_by}. Do not edit.
//! The recursive descent parser.
//!
//! This parser is generated from the same templates logic as the Java parser
//! that CongoCC generates for this grammar, and makes the same decisions: the
//! same lookahead, the same lexical state and token activation handling, and
//! the same tree.  Use [`Parser::parse`]:
//!
//! ```no_run
//! use ${settings::parserPackage.replace(".", "_")}::parser::Parser;
//!
//! match Parser::parse("input text", Some("input.txt")) {
//!     Ok(ast) => println!("{} nodes", ast.node_count()),
//!     Err(error) => eprintln!("{}", error),
//! }
//! ```
#![allow(dead_code, non_snake_case, non_upper_case_globals, unused_mut, unused_variables,
         unused_assignments, unreachable_code, unused_parens, unused_labels, unused_braces,
         while_true, clippy::all)]

use crate::ast::{Ast, AstBuilder, NodeKind};
use crate::error::{ParseError, ParseResult};
use crate::lexer::{Lexer, Token};
use crate::tokens::{LexicalState, TokenType, TokenTypeSet};
[#if lexerData::hasContextualTokens]
use crate::tokens::{is_contextual_token, java_equals_ignore_case, literal_string};
[/#if]

/// The result of the parsing functions: Java's `ParseException` becomes the
/// boxed error, so that the `Result` of every production stays small.
type PResult = Result<(), Box<ParseError>>;

/// Java's `UNLIMITED` lookahead amount.
const UNLIMITED: i32 = i32::MAX;

// =================================================================
// First sets of the productions and of the expansions with lookahead
// =================================================================
[#list grammar::parserProductions as production]
[@firstSetVar production::expansion /]
[/#list]
[#if grammar::choicePointExpansions]
[#list grammar::expansionsForFirstSet as expansion]
[@firstSetVar expansion /]
[/#list]
[/#if]

/// An entry of the parser's call stack (Java `NonTerminalCall`): the
/// production that was being parsed when another production was entered,
/// and where in the grammar that happened.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct NonTerminalCall {
    /// The name of the production making the call.
    pub production_name: &'static str,
    /// The grammar file of the call.
    pub source_file: &'static str,
    /// The line of the call in the grammar file.
    pub line: u32,
    /// The column of the call in the grammar file.
    pub column: u32,
}

/// A cursor over the parser's call stacks: the parsing stack followed by the
/// lookahead stack, as a single list.  It is the Rust counterpart of the
/// `ListIterator<NonTerminalCall>` that the Java parser's
/// `stackIteratorForward()` and `stackIteratorBackward()` return, with the
/// same `hasNext`/`next`/`hasPrevious`/`previous` semantics; it reads the
/// stacks through the parser passed to each method, so it must not be used
/// after the stacks change.
///
/// ```ignore
/// let mut it = parser.stack_iterator_backward();
/// while it.has_next(&parser) {
///     let call = it.next(&parser);   // innermost call first
///     println!("{}", call.production_name);
/// }
/// ```
#[derive(Debug, Clone, Copy)]
pub(crate) struct StackIter {
    /// The position between two elements: the element before it is the one
    /// `previous` returns (forward) or `next` returns (backward).
    cursor: usize,
    backward: bool,
}

impl StackIter {
    fn len(parser: &Parser) -> usize {
        parser.call_stack.len() + parser.lookahead_stack.len()
    }

    fn element(parser: &Parser, index: usize) -> NonTerminalCall {
        let parsing = parser.call_stack.len();
        if index < parsing {
            parser.call_stack[index]
        } else {
            parser.lookahead_stack[index - parsing]
        }
    }

    /// Java `hasNext()`.
    pub fn has_next(&self, parser: &Parser) -> bool {
        if self.backward { self.cursor > 0 } else { self.cursor < Self::len(parser) }
    }

    /// Java `hasPrevious()`.
    pub fn has_previous(&self, parser: &Parser) -> bool {
        if self.backward { self.cursor < Self::len(parser) } else { self.cursor > 0 }
    }

    /// Java `next()`.
    ///
    /// # Panics
    ///
    /// Panics if there is no next element (Java throws `NoSuchElementException`).
    pub fn next(&mut self, parser: &Parser) -> NonTerminalCall {
        assert!(self.has_next(parser), "StackIter::next() was called at the end of the call stacks \
                (Java would throw NoSuchElementException); check has_next() first");
        if self.backward {
            self.cursor -= 1;
            Self::element(parser, self.cursor)
        } else {
            self.cursor += 1;
            Self::element(parser, self.cursor - 1)
        }
    }

    /// Java `previous()`.
    ///
    /// # Panics
    ///
    /// Panics if there is no previous element (Java throws `NoSuchElementException`).
    pub fn previous(&mut self, parser: &Parser) -> NonTerminalCall {
        assert!(self.has_previous(parser), "StackIter::previous() was called at the start of the call stacks \
                (Java would throw NoSuchElementException); check has_previous() first");
        if self.backward {
            self.cursor += 1;
            Self::element(parser, self.cursor - 1)
        } else {
            self.cursor -= 1;
            Self::element(parser, self.cursor)
        }
    }
}

/// The recursive descent parser generated from the grammar.
///
/// Use [`Parser::parse`], which parses a whole input and returns its
/// [`Ast`].  The parser reads tokens from its [`Lexer`] on demand, like the
/// Java parser: the grammar can change the lexical state or the active token
/// types in the middle of the input, and tokens that were lexed ahead under
/// other conditions are lexed again.
///
/// # Examples
///
/// ```no_run
/// use ${settings::parserPackage.replace(".", "_")}::parser::Parser;
///
/// let ast = Parser::parse("input text", Some("example.txt")).expect("syntax error");
/// println!("Parsed {} nodes", ast.node_count());
/// ```
pub struct Parser {
    /// The lexer, which also owns the (munged) input text.
    lexer: Lexer,
    /// The regular tokens lexed so far, in order (Java's token cache, without
    /// the unparsed tokens such as comments, which the parser never sees).
    tokens: Vec<Token>,
    /// The code point offset where the lexer continues after `tokens`.
    lexer_pos: usize,
    /// The number of tokens consumed: `tokens[pos - 1]` is Java's
    /// `lastConsumedToken`, and `pos == 0` stands for its initial dummy token.
    pos: usize,
    /// During lookahead, the number of tokens scanned: `tokens[scan_pos - 1]`
    /// is Java's `currentLookaheadToken`.
    scan_pos: usize,
    /// Whether a lookahead is running (Java: `currentLookaheadToken != null`).
    in_lookahead: bool,
    /// The tree under construction.
    builder: AstBuilder,
    /// Java `buildTree`.
    build_tree: bool,
    /// Java `tokensAreNodes`.
    tokens_are_nodes: bool,
    /// Java `parsingStack`.
    call_stack: Vec<NonTerminalCall>,
    /// Java `lookaheadStack`.
    lookahead_stack: Vec<NonTerminalCall>,
    /// Java `currentlyParsedProduction` ("" for null).
    currently_parsed_production: &'static str,
    /// Java `currentLookaheadProduction` ("" for null).
    current_lookahead_production: &'static str,
    /// Java `remainingLookahead`.
    remaining_lookahead: i32,
    /// Java `hitFailure`.
    hit_failure: bool,
    /// Java `passedPredicate`.
    passed_predicate: bool,
    /// Java `passedPredicateThreshold`.
    passed_predicate_threshold: i32,
    /// Java `lookaheadRoutineNesting`.
    lookahead_routine_nesting: i32,
[#-- Fields from INJECT PARSER_CLASS --]
${globals.rustParserFields()}}

impl std::fmt::Debug for Parser {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Parser")
            .field("pos", &self.pos)
            .field("tokens_lexed", &self.tokens.len())
            .field("currently_parsed_production", &self.currently_parsed_production)
            .finish_non_exhaustive()
    }
}

impl Parser {
    /// Parses `input` and returns its syntax tree.
    ///
    /// The tree's offsets refer to [`Ast::source`], the input after the
    /// rewriting that the grammar's settings ask for (tab expansion, line
    /// ending normalization, a final newline...), as the lexer does it.
    ///
    /// # Arguments
    ///
    /// * `input` - the text to parse.
    /// * `source_name` - a name for the input (a file name, say), used in
    ///   error messages.
    ///
    /// # Errors
    ///
    /// Returns the [`ParseError`] for the first syntax error, with its
    /// location, the expected token types and the productions being parsed.
    pub fn parse(input: &str, source_name: Option<&str>) -> ParseResult<Ast> {
        let source_name = source_name.unwrap_or("");
        let mut parser = Parser::new(input, source_name);
[#if rootProduction??]
        parser.parse_${rootProduction::name}().map_err(|error| *error)?;
[/#if]
        if let Some(root) = parser.builder.peek_node() {
            parser.builder.set_root(root);
        }
        let Parser { lexer, builder, .. } = parser;
        Ok(builder.build(lexer.source().to_string(), source_name.to_string()))
    }

    fn new(input: &str, source_name: &str) -> Self {
        Parser {
            lexer: Lexer::new(input, source_name),
            tokens: Vec::new(),
            lexer_pos: 0,
            pos: 0,
            scan_pos: 0,
            in_lookahead: false,
            builder: AstBuilder::new(),
            build_tree: ${settings::treeBuildingDefault ? "true" : "false"},
            tokens_are_nodes: ${settings::tokensAreNodes ? "true" : "false"},
            call_stack: Vec::new(),
            lookahead_stack: Vec::new(),
            currently_parsed_production: "",
            current_lookahead_production: "",
            remaining_lookahead: 0,
            hit_failure: false,
            passed_predicate: false,
            passed_predicate_threshold: -1,
            lookahead_routine_nesting: 0,
${globals.rustParserFieldInits()}        }
    }

    // =============================================================
    // Tokens (Java Parser.nextToken/getToken and the lexer's token cache)
    // =============================================================

    /// Lexes the next regular token and appends it to `tokens`; unparsed
    /// tokens (comments...) are skipped, like Java's `nextToken`.
    fn lex_next_regular(&mut self) {
        loop {
            let token = self.lexer.tokenize_at(self.lexer_pos);
            self.lexer_pos = token.end_cp as usize;
            if !token.is_unparsed {
                self.tokens.push(token);
                return;
            }
        }
    }

    /// Returns the regular token with the given index (0 is the first token
    /// of the input), lexing as far as necessary.  Past the end of the input
    /// it returns the `EOF` token.
    ///
    /// As in Java (`Lexer.getNextToken`), a token that was lexed ahead but
    /// whose type is no longer active is thrown away, together with the
    /// tokens after it, and the input is lexed again from there.
    #[inline]
    fn token_at(&mut self, index: usize) -> Token {
        if let Some(&token) = self.tokens.get(index) {
            if self.lexer.is_active(token.kind) {
                return token;
            }
            self.reset_to(index, None);
        }
        self.lex_until(index)
    }

    fn lex_until(&mut self, index: usize) -> Token {
        while self.tokens.len() <= index {
            if let Some(last) = self.tokens.last() {
                if last.kind == TokenType::EOF {
                    return *last;
                }
            }
            self.lex_next_regular();
        }
        self.tokens[index]
    }

    /// Returns a token that has already been lexed (for indexes past the
    /// end of the input, the `EOF` token).
    #[inline]
    fn lexed_token(&self, index: usize) -> Token {
        match self.tokens.get(index) {
            Some(&token) => token,
            None => self.tokens.last().copied().unwrap_or(Token::dummy()),
        }
    }

    /// Java `TokenSource.reset(token, state)`: forgets the tokens after the
    /// first `count` tokens, so that they are lexed again, and switches to
    /// `state` or, without one, to the state that the last kept token's
    /// type switches to.
    fn reset_to(&mut self, count: usize, state: Option<LexicalState>) {
        let count = count.min(self.tokens.len());
        self.tokens.truncate(count);
        self.lexer_pos = if count == 0 { 0 } else { self.tokens[count - 1].end_cp as usize };
        match state {
            Some(state) => {
                self.lexer.switch_to(state);
            }
            None => {
                if count > 0 {
                    let kind = self.tokens[count - 1].kind;
                    self.lexer.do_lexical_state_switch(kind);
                }
            }
        }
    }

    /// The number of tokens consumed by the parse or, during a lookahead,
    /// scanned by it: the position of Java's `getToken(0)`.
    #[inline]
    fn current_base(&self) -> usize {
        if self.in_lookahead { self.scan_pos } else { self.pos }
    }

    /// Java `getToken(n)`: the token `n` tokens after the current position
    /// (`n == 0` is the last consumed or scanned token, negative values go
    /// backwards).  Where Java returns `null` (before the first token) this
    /// returns [`Token::dummy`].
    pub(crate) fn get_token(&mut self, n: i32) -> Token {
        let base = self.current_base();
        if n <= 0 {
            let back = n.unsigned_abs() as usize;
            return if base > back { self.lexed_token(base - 1 - back) } else { Token::dummy() };
        }
        let mut token = Token::dummy();
        for offset in 0..n as usize {
            token = self.token_at(base + offset);
        }
        token
    }

    /// Java `getTokenType(n)` (`TokenType::DUMMY` where Java returns `null`).
    pub(crate) fn get_token_type(&mut self, n: i32) -> TokenType {
        self.get_token(n).kind
    }

    /// Java `checkNextTokenType(type)`.
    pub(crate) fn check_next_token_type(&mut self, tt: TokenType) -> bool {
        self.get_token(1).kind == tt
    }

    /// The byte offset where the last consumed token ends (0 before the
    /// first token): the extent of a node without children.
    #[inline]
    fn last_consumed_end(&self) -> u32 {
        if self.pos == 0 { 0 } else { self.lexed_token(self.pos - 1).end as u32 }
    }

    /// Java `typeMatches`: a token matches a type if it has that type or, for
    /// a contextual keyword, if its text is the keyword.
    #[inline]
    fn type_matches(&self, tt: TokenType, token: &Token) -> bool {
        if token.kind == tt {
            return true;
        }
[#if lexerData::hasContextualTokens]
        if is_contextual_token(tt) {
            if let Some(literal) = literal_string(tt) {
                let text = self.lexer.token_text(token);
                return if tt.is_ignore_case() { java_equals_ignore_case(literal, text) } else { literal == text };
            }
        }
[/#if]
        false
    }

    /// Java `hasMatch`: whether the token matches one of the types in `types`.
    #[inline]
    fn has_match(&self, types: &TokenTypeSet, token: &Token) -> bool {
        if types.contains(token.kind) {
            return true;
        }
[#if lexerData::hasContextualTokens]
        let contextual = types.intersection(&crate::tokens::CONTEXTUAL_TOKEN_TYPES);
        if !contextual.is_empty() {
            for tt in contextual.iter() {
                if self.type_matches(tt, token) {
                    return true;
                }
            }
        }
[/#if]
        false
    }

    /// Java `typeMatches(type, getToken(1))`.
    #[inline]
    fn next_token_matches(&mut self, tt: TokenType) -> bool {
        let token = self.get_token(1);
        self.type_matches(tt, &token)
    }

    /// Java `hasMatch(types, getToken(1))`.
    #[inline]
    fn next_token_in(&mut self, types: &TokenTypeSet) -> bool {
        let token = self.get_token(1);
        self.has_match(types, &token)
    }

    /// Java `scanToken(types...)`: during a lookahead, steps over the next
    /// token if it matches one of `types`.
    fn scan_token(&mut self, types: &[TokenType]) -> bool {
        let peeked = self.token_at(self.scan_pos);
        if !types.iter().any(|&tt| self.type_matches(tt, &peeked)) {
            return false;
        }
        self.remaining_lookahead = self.remaining_lookahead.wrapping_sub(1);
        self.scan_pos += 1;
        true
    }

    /// Java `scanToken(EnumSet)`.
    fn scan_token_set(&mut self, types: &TokenTypeSet) -> bool {
        let peeked = self.token_at(self.scan_pos);
        if !self.has_match(types, &peeked) {
            return false;
        }
        self.remaining_lookahead = self.remaining_lookahead.wrapping_sub(1);
        self.scan_pos += 1;
        true
    }

    /// Java `consumeToken`: consumes the next token, which must match
    /// `expected` (a contextual keyword takes the expected type), and adds it
    /// to the tree.
    fn consume_token(&mut self, expected: TokenType) -> Result<Token, Box<ParseError>> {
        let mut token = self.token_at(self.pos);
        if token.kind != expected {
            if !self.type_matches(expected, &token) {
                return Err(self.make_parse_error(token, &[expected]));
            }
            // Java Token.replaceType: the token keeps the contextual type.
            token.kind = expected;
            if let Some(cached) = self.tokens.get_mut(self.pos) {
                cached.kind = expected;
            }
        }
        self.pos += 1;
        if self.build_tree && self.tokens_are_nodes {
            let token_id = self.builder.add_token(token.kind, token.start, token.end, token.is_unparsed);
            let node = self.builder.add_node(NodeKind::Token(token_id), token.start as u32, token.end as u32);
            self.builder.push_node(node);
        }
        Ok(token)
    }

    /// Java `uncacheTokens()`: the tokens after the current position will be
    /// lexed again.
    fn uncache_tokens(&mut self) {
        let base = self.current_base();
        self.reset_to(base, None);
    }

    /// Java `activateTokenTypes`: activates the types and, if that changed
    /// the active set, makes the tokens after the current position be lexed
    /// again.  Does nothing while every type is active.
    fn activate_token_types(&mut self, types: &[TokenType]) -> bool {
        let changed = self.lexer.activate_types(types);
        if changed {
            self.uncache_tokens();
        }
        changed
    }

    /// Java `deactivateTokenTypes`.
    fn deactivate_token_types(&mut self, types: &[TokenType]) -> bool {
        let changed = self.lexer.deactivate_types(types);
        if changed {
            self.uncache_tokens();
        }
        changed
    }

    // =============================================================
    // Call stacks (Java ErrorHandling.java.ctl)
    // =============================================================

    /// Java `pushOntoCallStack`.
    fn push_onto_call_stack(&mut self, production_name: &'static str, source_file: &'static str, line: u32, column: u32) {
        // Java's NonTerminalCall records getToken(1), which may lex the next
        // token; doing the same keeps the lexer's lexical state in step.
        let _ = self.get_token(1);
        self.call_stack.push(NonTerminalCall { production_name, source_file, line, column });
    }

    /// Java `popCallStack`.
    fn pop_call_stack(&mut self) {
        let call = self.call_stack.pop().expect(
            "pop_call_stack() was called with an empty call stack: the generated parser's push and pop calls are unbalanced");
        self.currently_parsed_production = call.production_name;
    }

    /// Java `restoreCallStack`.
    fn restore_call_stack(&mut self, size: usize) {
        while self.call_stack.len() > size {
            self.pop_call_stack();
        }
    }

    /// Java `pushOntoLookaheadStack`.
    fn push_onto_lookahead_stack(&mut self, production_name: &'static str, source_file: &'static str, line: u32, column: u32) {
        let _ = self.get_token(1);
        self.lookahead_stack.push(NonTerminalCall { production_name, source_file, line, column });
    }

    /// Java `popLookaheadStack`.
    fn pop_lookahead_stack(&mut self) {
        let call = self.lookahead_stack.pop().expect(
            "pop_lookahead_stack() was called with an empty lookahead stack: the generated parser's push and pop calls are unbalanced");
        self.current_lookahead_production = call.production_name;
    }

    /// Java `stackIteratorForward()`: the call stacks from the outermost call.
    pub(crate) fn stack_iterator_forward(&self) -> StackIter {
        StackIter { cursor: 0, backward: false }
    }

    /// Java `stackIteratorBackward()`: the call stacks from the innermost call.
    pub(crate) fn stack_iterator_backward(&self) -> StackIter {
        StackIter { cursor: StackIter::len(self), backward: true }
    }

    /// Java `isInProduction(names...)`: whether one of the productions is
    /// being parsed or scanned, here or further up the call stacks.
    pub(crate) fn is_in_production(&self, names: &[&str]) -> bool {
        if names.contains(&self.currently_parsed_production) || names.contains(&self.current_lookahead_production) {
            return true;
        }
        let mut iterator = self.stack_iterator_backward();
        while iterator.has_next(self) {
            if names.contains(&iterator.next(self).production_name) {
                return true;
            }
        }
        false
    }

    // =============================================================
    // Errors
    // =============================================================

    /// Builds the error for an unexpected token.
    fn make_parse_error(&self, found: Token, expected: &[TokenType]) -> Box<ParseError> {
        let (line, column) = self.lexer.line_column(found.start);
        let mut error = ParseError::unexpected_token(
            found.kind,
            self.lexer.token_text(&found),
            self.lexer.source_name(),
            line,
            column,
            expected,
        );
        for call in self.call_stack.iter().rev() {
            error = error.with_production(call.production_name);
        }
        Box::new(error)
    }

    /// Java `throw new ParseException(getToken(1), firstSet, parsingStack)`.
    fn unexpected_token_error(&mut self, expected: &TokenTypeSet) -> Box<ParseError> {
        let found = self.get_token(1);
        let expected: Vec<TokenType> = expected.iter().collect();
        self.make_parse_error(found, &expected)
    }

    /// Java `fail(message, location)`: an error while parsing; during a
    /// lookahead it only makes the lookahead fail.
    fn fail_at(&mut self, message: &str, location: Token) -> PResult {
        if !self.in_lookahead {
            let (line, column) = self.lexer.line_column(location.start);
            let mut error = ParseError::at(message.to_string(), self.lexer.source_name(), line, column);
            error.found = Some(location.kind);
            for call in self.call_stack.iter().rev() {
                error = error.with_production(call.production_name);
            }
            return Err(Box::new(error));
        }
        self.hit_failure = true;
        Ok(())
    }

    // =============================================================
    // Tree building (Java TreeBuildingCode.java.ctl)
    // =============================================================

    /// Java `createNode` and `openNodeScope`: opens the scope of a node of
    /// the given kind, unless the tree is not being built, and returns
    /// whether it did.  The node itself is only allocated when its scope is
    /// closed with the node kept (see `AstBuilder::open_deferred_node_scope`):
    /// Java gives a new node the offsets of the end of the last consumed
    /// token, and so does the close, which overrides them.
    #[inline]
    fn open_node(&mut self, kind: NodeKind) -> bool {
        if !self.build_tree {
            return false;
        }
        self.builder.open_deferred_node_scope(kind);
        true
    }

    // =============================================================
    // Methods injected with INJECT PARSER_CLASS
    // =============================================================
${globals.rustParserMethods()}

    // =============================================================
    // Productions
    // =============================================================

[#list grammar::parserProductions as production]
[#if !production::onlyForLookahead]
[@ParserProduction production /]
[/#if]
[/#list]
    // =============================================================
    // Lookahead routines
    // =============================================================

[@GenerateLookaheads /]
}
