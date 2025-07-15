#var MULTIPLE_LEXICAL_STATE_HANDLING = (lexerData.numLexicalStates > 1)

private ArrayList<NonTerminalCall> parsingStack = new ArrayList<>();
private final ArrayList<NonTerminalCall> lookaheadStack = new ArrayList<>();

#if settings.faultTolerant
  private EnumSet<TokenType> currentFollowSet;
#endif

private void pushOntoCallStack(String methodName, String fileName, int line, int column) {
   parsingStack.add(new NonTerminalCall("${settings.parserClassName}", getToken(1).getLocation(), fileName, methodName, line, column[#if settings.faultTolerant], currentFollowSet[/#if]));
}

private void popCallStack() {
    NonTerminalCall ntc = parsingStack.remove(parsingStack.size() - 1);
    this.currentlyParsedProduction = ntc.productionName;
   #if settings.faultTolerant
    this.outerFollowSet = (EnumSet<TokenType>) ntc.followSet;
   #endif
}

private void restoreCallStack(int prevSize) {
    while (parsingStack.size() > prevSize) {
       popCallStack();
    }
}

private ListIterator<NonTerminalCall> stackIteratorForward() {
    final ListIterator<NonTerminalCall> parseStackIterator = parsingStack.listIterator();
    final ListIterator<NonTerminalCall> lookaheadStackIterator = lookaheadStack.listIterator();
    return new ListIterator<NonTerminalCall>() {
        public boolean hasNext() {
            return parseStackIterator.hasNext() || lookaheadStackIterator.hasNext();
        }
        public NonTerminalCall next() {
            return parseStackIterator.hasNext() ? parseStackIterator.next() : lookaheadStackIterator.next();
        }
        public NonTerminalCall previous() {
           return lookaheadStackIterator.hasPrevious() ? lookaheadStackIterator.previous() : parseStackIterator.previous();
        }
        public boolean hasPrevious() {
            return lookaheadStackIterator.hasPrevious() || parseStackIterator.hasPrevious();
        }
        public void add(NonTerminalCall ntc) {throw new UnsupportedOperationException();}
        public void set(NonTerminalCall ntc) {throw new UnsupportedOperationException();}
        public void remove() {throw new UnsupportedOperationException();}
        public int previousIndex() {throw new UnsupportedOperationException();}
        public int nextIndex() {throw new UnsupportedOperationException();}
    };
}

private ListIterator<NonTerminalCall> stackIteratorBackward() {
    final ListIterator<NonTerminalCall> parseStackIterator = parsingStack.listIterator(parsingStack.size());
    final ListIterator<NonTerminalCall> lookaheadStackIterator = lookaheadStack.listIterator(lookaheadStack.size());
    return new ListIterator<NonTerminalCall>() {
        public boolean hasNext() {
            return lookaheadStackIterator.hasPrevious() || parseStackIterator.hasPrevious();
        }
        public NonTerminalCall next() {
            return lookaheadStackIterator.hasPrevious() ? lookaheadStackIterator.previous() : parseStackIterator.previous();
        }
        public NonTerminalCall previous() {
           return parseStackIterator.hasNext() ? parseStackIterator.next() : lookaheadStackIterator.next();
        }
        public boolean hasPrevious() {
            return parseStackIterator.hasNext() || lookaheadStackIterator.hasNext();
        }
        public void add(NonTerminalCall ntc) {throw new UnsupportedOperationException();}
        public void set(NonTerminalCall ntc) {throw new UnsupportedOperationException();}
        public void remove() {throw new UnsupportedOperationException();}
        public int previousIndex() {throw new UnsupportedOperationException();}
        public int nextIndex() {throw new UnsupportedOperationException();}
    };
}


private void pushOntoLookaheadStack(String methodName, String fileName, int line, int column) {
    lookaheadStack.add(new NonTerminalCall("${settings.parserClassName}", getToken(1).getLocation(), fileName, methodName, line, column[#if settings.faultTolerant], null[/#if]));
}

private void popLookaheadStack() {
    NonTerminalCall ntc = lookaheadStack.remove(lookaheadStack.size() - 1);
    this.currentLookaheadProduction = ntc.productionName;
}

void dumpLookaheadStack(PrintStream ps) {
    ListIterator<NonTerminalCall> it = lookaheadStack.listIterator(lookaheadStack.size());
    while (it.hasPrevious()) {
        ps.print(it.previous());
    }
}

void dumpCallStack(PrintStream ps) {
    ListIterator<NonTerminalCall> it = parsingStack.listIterator(parsingStack.size());
    while (it.hasPrevious()) {
        ps.print(it.previous());
    }
}

void dumpLookaheadCallStack(PrintStream ps) {
    ps.println("Current Parser Production is: " + currentlyParsedProduction);
    ps.println("Current Lookahead Production is: " + currentLookaheadProduction);
    ps.println("---Lookahead Stack---");
    dumpLookaheadStack(ps);
    ps.println("---Call Stack---");
    dumpCallStack(ps);
}

#if settings.faultTolerant
   #if settings.faultTolerantDefault
    private boolean tolerantParsing = true;
   #else
    private boolean tolerantParsing = false;
   #endif
    // Are we pending a recovery routine to
    // get back on the rails?
    private boolean pendingRecovery;

    private java.util.List<ParsingProblem> parsingProblems = new java.util.ArrayList<>();

    public java.util.List<ParsingProblem> getParsingProblems() {
        return parsingProblems;
    }

    public boolean hasProblems() {
        return !parsingProblems.isEmpty();
    }

    void addParsingProblem(ParsingProblem problem) {
        parsingProblems.add(problem);
    }
#endif

    public boolean isParserTolerant() {
       #if settings.faultTolerant
        return tolerantParsing;
       #else
        return false;
       #endif
    }

    public void setParserTolerant(boolean tolerantParsing) {
        #if settings.faultTolerant
          this.tolerantParsing = tolerantParsing;
        #else
          if (tolerantParsing) {
            throw new UnsupportedOperationException("This parser was not built with that feature!");
          }
        #endif
    }

      private ${settings.baseTokenClassName} consumeToken(TokenType expectedType
        #if settings.faultTolerant
          , boolean tolerant, EnumSet<TokenType> followSet, Runnable recoveryAction 
        #endif
      )
      [#if settings.useCheckedException] throws ParseException [/#if]
      {
        ${settings.baseTokenClassName} nextToken = nextToken(lastConsumedToken);
        if (nextToken.getType() != expectedType) {
            nextToken = handleUnexpectedTokenType(expectedType, nextToken
            #if settings.faultTolerant
               , tolerant, followSet
            #endif
            ) ;
        }
        this.lastConsumedToken = nextToken;
        this.nextTokenType = null;
#if settings.treeBuildingEnabled
      if (buildTree && tokensAreNodes) {
      lastConsumedToken.open();
  #list grammar.openNodeScopeHooks as hook
     ${hook}(lastConsumedToken);
  #endlist
          pushNode(lastConsumedToken);
     lastConsumedToken.close();
  #list grammar.closeNodeScopeHooks as hook
     ${hook}(lastConsumedToken);
  #endlist
      }
#endif
#if settings.faultTolerant
// Check whether the very next token is in the follow set of the last consumed token
// and if it is not, we check one token ahead to see if skipping the next token remedies
// the problem.
      if (followSet != null && isParserTolerant()) {
         nextToken = nextToken(lastConsumedToken);
         if (!followSet.contains(nextToken.getType())) {
            ${settings.baseTokenClassName} nextNext = nextToken(nextToken);
            if (followSet.contains(nextNext.getType())) {
               nextToken.setSkipped(true);
            }
         }
      }
      if (lastConsumedToken.isSkipped() || lastConsumedToken.isVirtual() || nextToken.isSkipped()) {
         recoveryAction.run();
      }
#endif
      return lastConsumedToken;
  }

  private ${settings.baseTokenClassName} handleUnexpectedTokenType(TokenType expectedType, ${settings.baseTokenClassName} nextToken
      #if settings.faultTolerant
        , boolean tolerant, EnumSet<TokenType> followSet
      #endif
      )
      #if settings.useCheckedException
         throws ParseException
      #endif
      {
      #if !settings.faultTolerant
       throw new ParseException(nextToken, EnumSet.of(expectedType), parsingStack);
      #else
       if (!this.tolerantParsing) {
          throw new ParseException(nextToken, EnumSet.of(expectedType), parsingStack);
       }
       ${settings.baseTokenClassName} nextNext = nextToken(nextToken);
       if (nextNext.getType() == expectedType) {
             [#-- REVISIT. Here we skip one token (as well as any InvalidToken) but maybe (probably!) this behavior
             should be configurable. But we need to experiment, because this is really a heuristic question, no?--]
             nextToken.setSkipped(true);
#if settings.treeBuildingEnabled
             pushNode(nextToken);
#endif
             return nextNext;
       }
         #-- Since skipping the next token did not work, we will insert a virtual token
       if (tolerant || followSet == null || followSet.contains(nextToken.getType())) {
           ${settings.baseTokenClassName} virtualToken = ${settings.baseTokenClassName}.newToken(expectedType, token_source, 0, 0);
           virtualToken.setVirtual(true);
           virtualToken.copyLocationInfo(nextToken);
#if lexerData.hasLexicalStateTransitions
           if (token_source.doLexicalStateSwitch(expectedType)) {
              token_source.reset(virtualToken);
           }
#endif
           return virtualToken;
       }
       throw new ParseException(nextToken, EnumSet.of(expectedType), parsingStack);
      #endif
  }

  private class ParseState {
       ${settings.baseTokenClassName} lastConsumed;
       ArrayList<NonTerminalCall> parsingStack;
   #if MULTIPLE_LEXICAL_STATE_HANDLING
       LexicalState lexicalState;
   #endif
       EnumSet<TokenType> activeTokenTypes;
   #if settings.treeBuildingEnabled
       NodeScope nodeScope;
   #endif
       ParseState() {
           this.lastConsumed = ${settings.parserClassName}.this.lastConsumedToken;
          @SuppressWarnings("unchecked")
           ArrayList<NonTerminalCall> parsingStack = (ArrayList<NonTerminalCall>) ${settings.parserClassName}.this.parsingStack.clone();
           this.parsingStack = parsingStack;
#if MULTIPLE_LEXICAL_STATE_HANDLING
           this.lexicalState = token_source.lexicalState;
#endif
           if (${settings.parserClassName}.this.token_source.activeTokenTypes!=null) {
               activeTokenTypes = ${settings.parserClassName}.this.token_source.activeTokenTypes.clone();
           }
#if settings.treeBuildingEnabled
           this.nodeScope = currentNodeScope.clone();
#endif
       }
  }

  private ArrayList<ParseState> parseStateStack = new ArrayList<>();

  private void stashParseState() {
      parseStateStack.add(new ParseState());
  }

  private ParseState popParseState() {
      return parseStateStack.remove(parseStateStack.size() - 1);
  }

  private void restoreStashedParseState() {
     ParseState state = popParseState();
#if settings.treeBuildingEnabled
     currentNodeScope = state.nodeScope;
#endif
     ${settings.parserClassName}.this.parsingStack = state.parsingStack;
    if (state.lastConsumed != null) {
        //REVISIT
         lastConsumedToken = state.lastConsumed;
    }
#if MULTIPLE_LEXICAL_STATE_HANDLING
     token_source.reset(lastConsumedToken, state.lexicalState);
#else
     token_source.reset(lastConsumedToken);
#endif
     token_source.activeTokenTypes = state.activeTokenTypes;
  }

