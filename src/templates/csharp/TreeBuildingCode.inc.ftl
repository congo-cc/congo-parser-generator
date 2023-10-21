        //
        // the root node of the AST. It only makes sense to call
        // this after a successful parse.
        //
        public Node RootNode { get { return CurrentNodeScope.RootNode; } }

        //
        // push a node onto the top of the node stack
        //
        internal void PushNode(Node n) {
            CurrentNodeScope.Add(n);
        }

        //
        // return the node on the top of the stack, and remove it from the
        // stack
        internal Node PopNode() {
            return CurrentNodeScope.Pop();
        }

        //
        // the node currently on the top of the tree-building stack.
        //
        internal Node PeekNode() {
            return CurrentNodeScope.Peek();
        }

        //
        // Puts the node on the top of the stack. However, unlike pushNode()
        // it replaces the node that is currently on the top of the stack.
        // This is effectively equivalent to PopNode() followed by PushNode(n)
        //
        internal void PokeNode(Node n) {
            CurrentNodeScope.Poke(n);
        }

        //
        // Pop and return a number of nodes. This can be perhaps optimized
        // at the expense of encapsulation (e.g. get a slice of the underlying
        // array)
        //
        internal IList<Node> PopNodes(uint n) {
            var result = new List<Node>();
            for (uint i = 0; i < n; i++) {
                result.Add(PopNode());
            }
            return result;
        }

        //
        // return the number of Nodes on the tree-building stack in the current node
        // scope.
        internal int NodeArity { get { return CurrentNodeScope.Count; } }

        internal void ClearNodeScope() {
            CurrentNodeScope.Clear();
        }

        internal void OpenNodeScope(Node n) {
            new NodeScope(this);    // as a side-effect, attaches to parser instance
            if (n != null) {
                var next = NextToken(LastConsumedToken);
                n.TokenSource = LastConsumedToken.TokenSource;
                n.BeginOffset = next.BeginOffset;
                n.Open();
    [#list grammar.openNodeScopeHooks as hook]
                ${hook}(n);
    [/#list]
            }
        }

        /*
        * A definite node is constructed from a specified number of
        * children.  That number of nodes are popped from the stack and
        * made the children of the definite node.  Then the definite node
        * is pushed on to the stack.
        */
        private bool CloseNodeScope(Node n, int num) {
            n.EndOffset = LastConsumedToken.EndOffset;
            CurrentNodeScope.Close();
            var nodes = new List<Node>();
            for (int i = 0; i < num; i++) {
                nodes.Add(PopNode());
            }
            nodes.Reverse();
            if (nodes.Count > 0) {
                n.BeginOffset = nodes[0].BeginOffset;
            }
            foreach (var child in nodes) {
                // FIXME deal with the UNPARSED_TOKENS_ARE_NODES case
                n.Add(child);
            }
            n.Close();
            PushNode(n);
    [#list grammar.closeNodeScopeHooks as hook]
            ${hook}(n);
    [/#list]
            return true;
        }

        /*
        * A conditional node is constructed if the condition is true.  All
        * the nodes that have been pushed since the node was opened are
        * made children of the conditional node, which is then pushed
        * on to the stack.  If the condition is false the node is not
        * constructed and they are left on the stack.
        */
        private bool CloseNodeScope(Node n, bool condition) {
            if (n!= null && condition) {
                n.BeginOffset = LastConsumedToken.EndOffset;
                n.EndOffset = LastConsumedToken.EndOffset;
                var a = NodeArity;
                CurrentNodeScope.Close();
                var nodes = new List<Node>();
                while (a-- > 0) {
                    nodes.Add(PopNode());
                }
                nodes.Reverse();
                if (nodes.Count > 0) {
                    n.BeginOffset = nodes[0].BeginOffset;
                    n.EndOffset = nodes[nodes.Count-1].EndOffset;
                }
                foreach (var child in nodes) {
                    if (UnparsedTokensAreNodes && child is Token tok) {
                        while (tok.PreviousCachedToken != null && tok.PreviousCachedToken.IsUnparsed) {
                            tok = tok.PreviousCachedToken;
                        }
                        while (tok.IsUnparsed) {
                            n.Add(tok);
                            tok = tok.NextCachedToken;
                        }
                    }
                    n.Add(child);
                }
                n.Close();
                PushNode(n);
    [#list grammar.closeNodeScopeHooks as hook]
                ${hook}(n);
    [/#list]
            }
            else {
                CurrentNodeScope.Close();
                return false;
            }
            return true;
        }
