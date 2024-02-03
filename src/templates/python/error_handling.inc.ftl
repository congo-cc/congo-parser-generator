#var MULTIPLE_LEXICAL_STATE_HANDLING = (lexerData.numLexicalStates > 1)
    def stack_iterator_forward(self):

        class ForwardIterator:
            def __init__(self, iter1, iter2):
                self.iter1 = ListIterator(iter1)
                self.iter2 = ListIterator(iter2)

            @property
            def has_next(self):
                return self.iter1.has_next or self.iter2.has_next

            @property
            def next(self):
                return self.iter1.next if self.iter1.has_next else self.iter2.next

            @property
            def has_previous(self):
                return self.iter2.has_previous or self.iter1.has_previous

            @property
            def previous(self):
                return self.iter2.previous if self.iter2.has_previous else self.iter1.previous

        return ForwardIterator(self.parsing_stack, self.lookahead_stack)

    def stack_iterator_backward(self):

        class BackwardIterator:
            def __init__(self, iter1, iter2):
                self.iter1 = ListIterator(iter1, len(iter1))
                self.iter2 = ListIterator(iter2, len(iter2))

            @property
            def has_next(self):
                return self.iter2.has_previous or self.iter1.has_previous

            @property
            def next(self):
                return self.iter2.previous if self.iter2.has_previous else self.iter1.previous

            @property
            def has_previous(self):
                return self.iter1.has_next or self.iter2.has_next

            @property
            def previous(self):
                return self.iter1.next if self.iter1.has_next else self.iter2.next

        return BackwardIterator(self.parsing_stack, self.lookahead_stack)

    def push_onto_lookahead_stack(self, method_name, filename, line, column):
        self.lookahead_stack.append(NonTerminalCall(self, filename, method_name, line, column[#if settings.faultTolerant], None[/#if]))

    def pop_lookahead_stack(self):
        ntc = self.lookahead_stack.pop()
        self.current_lookahead_production = ntc.production_name
        self.scan_to_end = ntc.scan_to_end

    def consume_token(self, expected_type[#if settings.faultTolerant], tolerant, follow_set[/#if]):
        # old_token = self.last_consumed_token
        next_token = self.next_token(self.last_consumed_token)
        if next_token.type != expected_type:
            next_token = self.handle_unexpected_token_type(expected_type, next_token[#if settings.faultTolerant], tolerant, follow_set[/#if])
        self.last_consumed_token = next_token
        self._next_token_type = None
#if settings.treeBuildingEnabled
        if self.build_tree and self.tokens_are_nodes:
  #list grammar.openNodeScopeHooks as hook
            ${hook}(self.last_consumed_token)
  /#list
            self.push_node(self.last_consumed_token)
  #list grammar.closeNodeScopeHooks as hook
            ${hook}(self.last_consumed_token)
  /#list
/#if
#if settings.faultTolerant
        # Check whether the very next token is in the follow set of the last consumed token
        # and if it is not, we check one token ahead to see if skipping the next token remedies
        # the problem.
        if follow_set and self.is_tolerant:
            next_token = self.next_token(self.last_consumed_token)
            if next_token.type not in follow_set:
                next_next = self.next_token(next_token)
                if next_next.type in follow_set:
                    next_token.skipped = True
                    if self.debug_fault_tolerant:
                        logger.info('Skipping token %s at: %s', next_token.type, next_token.location)
                     # self.last_consumed_token.next = next_next
/#if
        return self.last_consumed_token

    def handle_unexpected_token_type(self, expected_type, next_token[#if settings.faultTolerant], tolerant, follow_set[/#if]):
      #if !settings.faultTolerant
        raise ParseException(self, token=next_token, expected=set([expected_type]))
      #else
        if not self.tolerant_parsing:
            raise ParseException(self, token=next_token, expected=set([expected_type]))

        next_next = self.next_token(next_token)
        if next_next.type == expected_type:
            [#-- REVISIT. Here we skip one token (as well as any InvalidToken) but maybe (probably!) this behavior
            should be configurable. But we need to experiment, because this is really a heuristic question, no?--]
            next_token.skipped = True
            if self.debug_fault_tolerant:
                logger.info('Skipping token of type: %s at: %s', next_token.type, next_token.location)
#if settings.treeBuildingEnabled
            self.push_node(next_token)
/#if
            # self.last_consumed_token.next = next_next
            return next_next

        [#-- Since skipping the next token did not work, we will insert a virtual token --]
        if self.is_tolerant or follow_set is None or next_token.type in follow_set:
            virtual_token = new_token(expected_type, 'VIRTUAL %s' % expected_type,
                                      self.last_consumed_token.input_source)
            virtual_token.virtual = True
            virtual_token.copy_location_info(next_token)
            # virtual_token.next = next_token
            # self.last_consumed_token.next = virtual_token
            if self.debug_fault_tolerant:
                logger.info('Inserting virtual token of type: %s at: %s', expected_type, virtual_token.location)
#if MULTIPLE_LEXICAL_STATE_HANDLING
            if self.token_source.do_lexical_state_switch(expected_type):
                self.token_source.reset(virtual_token)
/#if
            return virtual_token
        raise ParseException(self, token=next_token, expected=set([expected_type]))
      /#if
