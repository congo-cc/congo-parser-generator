[#var MULTIPLE_LEXICAL_STATE_HANDLING = (lexerData.numLexicalStates > 1)]

        private void PushOntoLookaheadStack(string methodName, string fileName, uint line, uint column) {
            _lookaheadStack.Add(new NonTerminalCall(this, fileName, methodName, line, column[#if settings.faultTolerant], null[/#if]));
        }

        private void PopLookaheadStack() {
            var ntc = _lookaheadStack.Pop();
            _currentLookaheadProduction = ntc.ProductionName;
        }

        private Token ConsumeToken(TokenType expectedType[#if settings.faultTolerant], bool tolerant, HashSet<TokenType> followSet[/#if]) {
            var oldToken = LastConsumedToken;
            var nextToken = NextToken(LastConsumedToken);
            if (nextToken.Type != expectedType) {
                nextToken = HandleUnexpectedTokenType(expectedType, nextToken[#if settings.faultTolerant], tolerant, followSet[/#if]);
            }
            LastConsumedToken = nextToken;
            _nextTokenType = null;
[#if settings.treeBuildingEnabled]
            if (BuildTree && TokensAreNodes) {
            }
  [#list grammar.openNodeScopeHooks as hook]
            ${hook}(LastConsumedToken);
  [/#list]
            PushNode(LastConsumedToken);
  [#list grammar.closeNodeScopeHooks as hook]
            ${hook}(LastConsumedToken);
  [/#list]
[/#if]
[#if settings.faultTolerant]
            // Check whether the very next token is in the follow set of the last consumed token
            // and if it is not, we check one token ahead to see if skipping the next token remedies
            // the problem.
            if (followSet && IsTolerant) {
                nextToken = NextToken(LastConsumedToken);
                if nextToken.Type not in followSet:
                    nextNext = NextToken(nextToken);
                    if nextNext.Type in followSet:
                        nextToken.skipped = true;
                        if (DebugFaultTolerant) {
                            Log(LogLevel.INFO, "Skipping token {0} at: {1}", nextToken.Type, nextToken.Location);
                        }
                        LastConsumedToken.Next = nextNext;
            }
[/#if]
            return LastConsumedToken;
        }

        private Token HandleUnexpectedTokenType(TokenType expectedType, Token nextToken[#if settings.faultTolerant], bool tolerant, HashSet<TokenType> followSet[/#if]) {
[#if !settings.faultTolerant]
            throw new ParseException(this, null, nextToken, Utils.EnumSet(expectedType));
[#else]
            if (!TolerantParsing) {
                throw new ParseException(this, null, nextToken, Utils.EnumSet(expectedType));
            }
            var nextNext = NextToken(nextToken);
            if (nextNext.Type == expectedType) {
                [#-- REVISIT. Here we skip one token (as well as any InvalidToken) but maybe (probably!) this behavior
                should be configurable. But we need to experiment, because this is really a heuristic question, no?--]
                nextToken.skipped = true;
  [#if settings.treeBuildingEnabled]
                PushNode(nextToken);
  [/#if]
                return nextNext;
            }
            [#-- Since skipping the next token did not work, we will insert a virtual token --]
            if (tolerant || (followSet == null) || followSet.Contains(nextToken.Type)) {
                virtualToken = Token.NewToken(expectedType, tokenSource, 0, 0);
                virtualToken.virtual = true;
                virtualToken.CopyLocationInfo(nextToken);
                if (debugFaultTolerant) {
                    // logger.info('Inserting virtual token of type: %s at: %s', expectedType, virtualToken.location)
                }
  [#if MULTIPLE_LEXICAL_STATE_HANDLING]
                if (tokenSource.DoLexicalStateSwitch(expectedType)) {
                    tokenSource.Reset(virtualToken);
                }
  [/#if]
                return virtualToken;
            }
            throw new ParseException(this, null, nextToken, Utils.EnumSet(expectedType));
[/#if]
        }
