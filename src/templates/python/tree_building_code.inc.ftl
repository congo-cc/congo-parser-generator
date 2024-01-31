    #
    # the root node of the AST. It only makes sense to call
    # this after a successful parse.
    #
    @property
    def root_node(self):
        return self.current_node_scope.root_node

    #
    # push a node onto the top of the node stack
    #
    def push_node(self, n):
        self.current_node_scope.append(n)

    #
    # return the node on the top of the stack, and remove it from the
    # stack
    def pop_node(self):
        return self.current_node_scope.pop()

    #
    # the node currently on the top of the tree-building stack.
    #
    def peek_node(self):
        return self.current_node_scope.peek()

    #
    # Puts the node on the top of the stack. However, unlike pushNode()
    # it replaces the node that is currently on the top of the stack.
    # This is effectively equivalent to popNode() followed by pushNode(n)
    #
    def poke_node(self, n):
        self.current_node_scope.poke(n)

    #
    # Pop and return a number of nodes. This can be perhaps optimized
    # at the expense of encapsulation (e.g. get a slice of the underlying
    # array)
    #
    def pop_nodes(self, n):
        return [self.pop_node() for i in range(n)]

    #
    # return the number of Nodes on the tree-building stack in the current node
    # scope.
    @property
    def node_arity(self):
        return len(self.current_node_scope)

    def clear_node_scope(self):
        self.current_node_scope.clear()

    def open_node_scope(self, n):
        NodeScope(self)  # as a side-effect, attaches into self
        if n is not None:
            lct = self.last_consumed_token
            next = self.next_token(lct)
            n.token_source = lct.token_source
            n.begin_offset = next.begin_offset
            n.open()
[#list grammar.openNodeScopeHooks as hook]
            self.${hook}(n)
[/#list]

    #
    # A definite node is constructed from a specified number of
    # children.  That number of nodes are popped from the stack and
    # made the children of the definite node.  Then the definite node
    # is pushed on to the stack.
    #
    def close_node_scope_numbered(self, n, num):
        n.end_offset = self.last_consumed_token.end_offset
        self.current_node_scope.close()
        nodes = self.pop_nodes(num)
        if nodes:
            n.begin_offset = nodes[-1].begin_offset
            n.end_offset = nodes[0].end_offset
        for child in reversed(nodes):
            n.add(child)
        n.close()
        self.push_node(n)
[#list grammar.closeNodeScopeHooks as hook]
        ${hook}(n)
[/#list]

    #
    # A conditional node is constructed if the condition is true.  All
    # the nodes that have been pushed since the node was opened are
    # made children of the conditional node, which is then pushed
    # on to the stack.  If the condition is false the node is not
    # constructed and they are left on the stack.
    #
    def close_node_scope(self, n, condition_or_num):
        # Sometimes, the condition is just a number, so we pass thar
        # to the relevant method. Perhaps the method should be renamed;
        # in Java the methods are named the same and the correct one
        # is selected via method overloading
        if not isinstance(condition_or_num, bool):
            assert isinstance(condition_or_num, int)
            self.close_node_scope_numbered(n, condition_or_num)
            return True
        if n and condition_or_num:
            n.begin_offset = self.last_consumed_token.end_offset
            n.end_offset = self.last_consumed_token.end_offset
            a = self.node_arity
            self.current_node_scope.close()
            nodes = self.pop_nodes(a)
            if nodes:
                n.begin_offset = nodes[-1].begin_offset
                n.end_offset = nodes[0].end_offset
            for child in reversed(nodes):
                if self.unparsed_tokens_are_nodes and isinstance(child, Token):
                    tok = child
                    while tok.previous_cached_token and tok.previous_cached_token.is_unparsed:
                        tok = tok.previous_cached_token
                    while tok.is_unparsed:
                        n.add(tok)
                        tok = tok.next_cached_token
                n.add(child)
            n.close()
            self.push_node(n)
[#list grammar.closeNodeScopeHooks as hook]
            self.${hook}(n)
[/#list]
        else:
            self.current_node_scope.close()
            return False
        return True

