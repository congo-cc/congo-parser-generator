#var NFA_RANGE_THRESHOLD = 16,
     multipleLexicalStates = lexerData.lexicalStates?size > 1

#--  Generate all the NFA transition code
#--  for the given lexical state

#macro GenerateStateCode lexicalState
  #list lexicalState.canonicalSets as state
     #if state_index == 0
       ${GenerateInitialComposite(state)}
     #elif state.numStates = 1
       ${SimpleNfaMethod(state.singleState)}
     #else
       ${CompositeNfaMethod(state)}
     #endif
  #endlist

  #list lexicalState.allNfaStates as state
    #if state.moveRanges?size >= NFA_RANGE_THRESHOLD
      ${GenerateMoveArray(state)}
    #endif
  #endlist

  #-- We break the initialization into multiple chunkSize
  #-- if we have more than a couple of thousand NFA states in order to 
  #-- get around the Code Too Large problem
  #-- This is pretty rare, and I thought it never happened, except
  #-- user ngx reported running into this with his SQL grammar

  #var chunkSize = 2000 #-- arbitrary number. I think it could actually be up to a bit over 6000.
  #var numChunks = 1 + (lexicalState.canonicalSets?size / chunkSize)?int
  #var canonicalSets = lexicalState.canonicalSets

  private static void NFA_FUNCTIONS_init() {
    NfaFunction[] functions = new NfaFunction[${numChunks > 1 ?: lexicalState.canonicalSets?size}]
    #if numChunks == 1 
    {
     #list canonicalSets as state
      ${lexicalState.name}::get${state.methodName}
      ${state_has_next ?: ","}
     #endlist
    };
    #else
    ;
    #list 1..numChunks as index 
        NFA_FUNCTIONS_init${index}(functions);
    #endlist
    #endif
    #if multipleLexicalStates
      functionTableMap.put(LexicalState.${lexicalState.name}, functions);
    #else
      nfaFunctions = functions;
    #endif
  }

  #if numChunks > 1
     #list 1..numChunks as index
     private static void NFA_FUNCTIONS_init${index}(NfaFunction[] funcArray) {
         #list 1..chunkSize as index2
            #var offset = index*chunkSize + index2 -chunkSize -1
            #if offset >= canonicalSets?size
              #break
            #endif
            #var state = canonicalSets[offset]
            funcArray[${offset}] = ${lexicalState.name}::get${state.methodName};
         #endlist
     }
     #endlist
  #endif

#endmacro

[#--
   Generate the array representing the characters
   that this NfaState "accepts".
   This corresponds to the moveRanges field in
   org.congocc.core.NfaState
--]
#macro GenerateMoveArray nfaState
  #var moveRanges = nfaState.moveRanges
  #var arrayName = nfaState.movesArrayName
    private static final int[] ${arrayName} = ${arrayName}_init();

    private static int[] ${arrayName}_init() {
        return new int[]
        {
        #list nfaState.moveRanges as char
          ${globals.displayChar(char)}
          [#if char_has_next],[/#if]
        #endlist
        };
    }
#endmacro

#macro GenerateInitialComposite nfaState
    private static TokenType get${nfaState.methodName}(int ch, BitSet nextStates, EnumSet<TokenType> validTypes, EnumSet<TokenType> alreadyMatchedTypes) {
      TokenType type = null;
    #var states = nfaState.orderedStates, lastBlockStartIndex = 0
    #list states as state
      #if state_index == 0 || state.moveRanges != states[state_index - 1].moveRanges
          #-- In this case we need a new if or possibly else if 
         #if state_index == 0 || state::overlaps(states::subList(lastBlockStartIndex, state_index))
           [#-- If there is overlap between this state and any of the states
                 handled since the last lone if, we start a new if-else
                 If not, we continue in the same if-else block as before. --]
           [#set lastBlockStartIndex = state_index]
               if
         #else
               else if
         #endif
           ( ${NFA.NfaStateCondition(state)} ) {
      #endif
      if (validTypes == null || validTypes.contains(${state.type.label})) {
      #if state.nextStateIndex >= 0
         nextStates.set(${state.nextStateIndex});
      #endif
      #if !state_has_next || state.moveRanges != states[state_index + 1].moveRanges
        #-- We've reached the end of the block.
          #if state.nextState.final
            [#--if (validTypes == null || validTypes.contains(${state.type.label}))--]
              type = ${state.type.label};
          #endif
        }
      #endif
       }
    #endlist
      return type;
    }
#endmacro

[#--
   Generate the method that represents the transitions
   that correspond to an instanceof org.congocc.core.CompositeStateSet
--]
#macro CompositeNfaMethod nfaState
    private static TokenType get${nfaState.methodName}(int ch, BitSet nextStates, EnumSet<TokenType> validTypes, EnumSet<TokenType> alreadyMatchedTypes) {
     #if lexerData::isLazy(nfaState.type)
      if (alreadyMatchedTypes.contains(${nfaState.type.label})) return null;
     #endif
    #if nfaState.hasFinalState
      TokenType type = null;
    #endif
    #var states = nfaState.orderedStates, lastBlockStartIndex = 0
    #list states as state
      #if state_index == 0 || state.moveRanges != states[state_index - 1].moveRanges
          #-- In this case we need a new if or possibly else if
         #if state_index == 0 || state::overlaps(states::subList(lastBlockStartIndex, state_index))
           [#-- If there is overlap between this state and any of the states
                 handled since the last lone if, we start a new if-else
                 If not, we continue in the same if-else block as before. --]
           #set lastBlockStartIndex = state_index
               if
         #else
               else if
         #endif
           (${NFA.NfaStateCondition(state)}) {
      #endif
      #if state.nextStateIndex >= 0
         nextStates.set(${state.nextStateIndex});
      #endif
      #if !state_has_next || state.moveRanges != states[state_index + 1].moveRanges
        #-- We've reached the end of the block.
          #if state.nextState.final
              type = ${state.type.label};
          #endif
        }
      #endif
    #endlist
    #if nfaState.hasFinalState
      return type;
    #else
      return  null;
    #endif
    }
#endmacro

[#--
   Generate a method for a single, i.e. non-composite NFA state
--]
#macro SimpleNfaMethod state
    private static TokenType get${state.methodName}(int ch, BitSet nextStates, EnumSet<TokenType> validTypes, EnumSet<TokenType> alreadyMatchedTypes) {
     #if lexerData::isLazy(state.type)
      if (alreadyMatchedTypes.contains(${state.type.label})) return null;
     #endif
      if (${NfaStateCondition(state)}) {
         #if state.nextStateIndex >= 0
           nextStates.set(${state.nextStateIndex});
         #endif
         #if state.nextState.final
              return ${state.type.label};
         #endif
      }
      return null;
    }
#endmacro

[#--
Generate the condition part of the NFA state transition
If the size of the moveRanges vector is greater than NFA_RANGE_THRESHOLD
it uses the canned binary search routine. For the smaller moveRanges
it just generates the inline conditional expression
--]
#macro NfaStateCondition nfaState
    #if nfaState.moveRanges?size < NFA_RANGE_THRESHOLD
      ${RangesCondition(nfaState.moveRanges)}
    #elif nfaState.hasAsciiMoves && nfaState.hasNonAsciiMoves
      ${RangesCondition(nfaState.asciiMoveRanges)}
      || (ch >= 128 && checkIntervals(${nfaState.movesArrayName}, ch))
    #else
      checkIntervals(${nfaState.movesArrayName}, ch)
    #endif
#endmacro

[#--
This is a recursive macro that generates the code corresponding
to the accepting condition for an NFA state. It is used
if NFA state's moveRanges array is smaller than NFA_RANGE_THRESHOLD
(which is set to 16 for now)
--]
#macro RangesCondition moveRanges
    #var left = moveRanges[0], right = moveRanges[1]
    #var displayLeft = globals.displayChar(left),
         displayRight = globals.displayChar(right)
    #var singleChar = left == right
    #if moveRanges?size == 2
       #if singleChar
          ch == ${displayLeft}
       #elif left + 1 == right
          ch == ${displayLeft} || ch == ${displayRight}
       #else
          ch >= ${displayLeft}
          #if right < 1114111
             && ch <= ${displayRight}
          #endif
       #endif
    #else
       ( ${RangesCondition(moveRanges[0..1])} || ${RangesCondition(moveRanges[2..])} )
    #endif
#endmacro

