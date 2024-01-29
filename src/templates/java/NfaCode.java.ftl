#var NFA_RANGE_THRESHOLD = 16,
     multipleLexicalStates = lexerData.lexicalStates?size > 1

#--  Generate all the NFA transition code
#--  for the given lexical state

#macro GenerateStateCode lexicalState
  #list lexicalState.canonicalSets as state
     #if state_index=0
       [@GenerateInitialComposite state/]
     #elseif state.numStates = 1
       [@SimpleNfaMethod state.singleState /]
     #else
       [@CompositeNfaMethod state/]
     /#if
  /#list

  #list lexicalState.allNfaStates as state
    #if state.moveRanges?size >= NFA_RANGE_THRESHOLD
      [@GenerateMoveArray state/]
    /#if
  /#list

  private static void NFA_FUNCTIONS_init() {
    #if multipleLexicalStates
      NfaFunction[] functions = new NfaFunction[]
    #else
      nfaFunctions = new NfaFunction[]
    /#if
    {
    #list lexicalState.canonicalSets as state
      ${lexicalState.name}::get${state.methodName}
      [#if state_has_next],[/#if]
    [/#list]
    };
    #if multipleLexicalStates
      functionTableMap.put(LexicalState.${lexicalState.name}, functions);
    /#if
  }
/#macro

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
        [#list nfaState.moveRanges as char]
          ${globals.displayChar(char)}
          [#if char_has_next],[/#if]
        [/#list]
        };
    }
/#macro

#macro GenerateInitialComposite nfaState
    private static TokenType get${nfaState.methodName}(int ch, BitSet nextStates, EnumSet<TokenType> validTypes, EnumSet<TokenType> alreadyMatchedTypes) {
      TokenType type = null;
    #var states = nfaState.orderedStates, lastBlockStartIndex=0
    #list states as state
      #if state_index ==0 || state.moveRanges != states[state_index-1].moveRanges
          [#-- In this case we need a new if or possibly else if --]
         #if state_index == 0 || state::overlaps(states::subList(lastBlockStartIndex, state_index))
           [#-- If there is overlap between this state and any of the states
                 handled since the last lone if, we start a new if-else
                 If not, we continue in the same if-else block as before. --]
           [#set lastBlockStartIndex = state_index]
               if
         #else
               else if
         /#if
           ( [@NFA.NfaStateCondition state /]) {
      /#if
      if (validTypes == null || validTypes.contains(${state.type.label})) {
      #if state.nextStateIndex >= 0
         nextStates.set(${state.nextStateIndex});
      /#if
      #if !state_has_next || state.moveRanges != states[state_index+1].moveRanges
        [#-- We've reached the end of the block. --]
          #if state.nextState.final
            [#--if (validTypes == null || validTypes.contains(${state.type.label}))--]
              type = ${state.type.label};
          /#if
        }
      /#if
       }
    /#list
      return type;
    }
/#macro

[#--
   Generate the method that represents the transitions
   that correspond to an instanceof org.congocc.core.CompositeStateSet
--]
#macro CompositeNfaMethod nfaState
    private static TokenType get${nfaState.methodName}(int ch, BitSet nextStates, EnumSet<TokenType> validTypes, EnumSet<TokenType> alreadyMatchedTypes) {
     #if lexerData::isLazy(nfaState.type)
      if (alreadyMatchedTypes.contains(${nfaState.type.label})) return null;
     /#if
    #if nfaState.hasFinalState
      TokenType type = null;
    /#if
    #var states = nfaState.orderedStates, lastBlockStartIndex=0
    #list states as state
      #if state_index ==0 || state.moveRanges != states[state_index-1].moveRanges
          #-- In this case we need a new if or possibly else if
         #if state_index == 0 || state::overlaps(states::subList(lastBlockStartIndex, state_index))
           [#-- If there is overlap between this state and any of the states
                 handled since the last lone if, we start a new if-else
                 If not, we continue in the same if-else block as before. --]
           #set lastBlockStartIndex = state_index
               if
         #else
               else if
         /#if
           ([@NFA.NfaStateCondition state /]) {
      /#if
      #if state.nextStateIndex >= 0
         nextStates.set(${state.nextStateIndex});
      /#if
      #if !state_has_next || state.moveRanges != states[state_index+1].moveRanges
        #-- We've reached the end of the block.
          #if state.nextState.final
              type = ${state.type.label};
          /#if
        }
      /#if
    /#list
    #if nfaState.hasFinalState
      return type;
    #else
      return  null;
    /#if
    }
/#macro

[#--
   Generate a method for a single, i.e. non-composite NFA state
--]
#macro SimpleNfaMethod state
    private static TokenType get${state.methodName}(int ch, BitSet nextStates, EnumSet<TokenType> validTypes, EnumSet<TokenType> alreadyMatchedTypes) {
     #if lexerData::isLazy(state.type)
      if (alreadyMatchedTypes.contains(${state.type.label})) return null;
     /#if
      if ([@NfaStateCondition state /]) {
         #if state.nextStateIndex >= 0
           nextStates.set(${state.nextStateIndex});
         /#if
         #if state.nextState.final
              return ${state.type.label};
         /#if
      }
      return null;
    }
/#macro

[#--
Generate the condition part of the NFA state transition
If the size of the moveRanges vector is greater than NFA_RANGE_THRESHOLD
it uses the canned binary search routine. For the smaller moveRanges
it just generates the inline conditional expression
--]
#macro NfaStateCondition nfaState
    #if nfaState.moveRanges?size < NFA_RANGE_THRESHOLD
      [@RangesCondition nfaState.moveRanges /]
    #elseif nfaState.hasAsciiMoves && nfaState.hasNonAsciiMoves
      ([@RangesCondition nfaState.asciiMoveRanges/])
      || (ch >=128 && checkIntervals(${nfaState.movesArrayName}, ch))
    #else
      checkIntervals(${nfaState.movesArrayName}, ch)
    /#if
/#macro

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
    #if moveRanges?size==2
       #if singleChar
          ch == ${displayLeft}
       #elseif left +1 == right
          ch == ${displayLeft} || ch == ${displayRight}
       #else
          ch >= ${displayLeft}
          #if right < 1114111
             && ch <= ${displayRight}
          /#if
       /#if
    #else
       ([@RangesCondition moveRanges[0..1]/])||([@RangesCondition moveRanges[2..]/])
    /#if
/#macro

