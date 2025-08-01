${"#"}pragma warning disable 414, 168, 659
// ReSharper disable InconsistentNaming
#import "CommonUtils.inc.ctl" as CU
#var MULTIPLE_LEXICAL_STATE_HANDLING = (lexerData.numLexicalStates > 1)
#var csPackage = globals::getPreprocessorSymbol('cs.package', settings.parserPackage)

#-- Just using the name of the default lexical state to deduce (or guess at)
#-- the name of the file extension!
#var extension = lexerData::getLexicalStateName(0)::toLowerCase()
#if extension == "csharp" || extension == "python"
   #set extension = extension::substring(0,2)
#endif

#var rootProduction = "Module"
#if extension == "cs" || extension == "java"
   #set rootProduction = "CompilationUnit"
#elif extension = "lua"
   #set rootProduction = "Root"
#endif

using ${csPackage};
using System;
using System.IO;

if (args.Length == 0) {
    Console.WriteLine("Usage: <program> " + "files or directories with files to parse.");
}
long start = System.DateTime.Now.Ticks;
int successes = 0;
int failures = 0;

foreach(string arg in args) {
    if (arg.EndsWith(".${extension}") && File.Exists(arg)) {
        try {
           Parser p = new Parser(arg);
           p.Parse${rootProduction}();
           Console.WriteLine("parsed " + arg + " successfully.");
           successes++;
        }
        catch (ParseException e) {
            Console.WriteLine("Problem parsing file: " + arg);
            Console.WriteLine(e);
            failures++;
        }
    }
    else foreach(var f in Directory.EnumerateFiles(arg, "*.${extension}", SearchOption.AllDirectories))
    {
        try {
            Parser p = new Parser(f);
            p.Parse${rootProduction}();
            Console.WriteLine("parsed " + f + " successfully.");
            successes++;
        } catch (ParseException e) {
            Console.WriteLine("Problem parsing file: " + f);
            Console.WriteLine(e.ToString());
            failures++;
        }
    }
}

Console.WriteLine("Successfully parsed " + successes + " files.");
Console.WriteLine("Failed on: " + failures + " files.");

long duration = (System.DateTime.Now.Ticks - start)/10000;
Console.WriteLine("Duration: " + duration + " milliseconds.");

namespace ${csPackage} {
    using System;
    using System.Linq;
    using System.Collections.Generic;
    using System.Diagnostics;
    using System.Text;
    using static TokenType;
${globals::translateParserImports()}

    public class ParseException : Exception {
        public Parser Parser { get; private set; }
        public Token Token { get; private set; }
        public HashSet<TokenType> Expected { get; private set; }
        private IList<NonTerminalCall> callStack;

        public ParseException(Parser parser, HashSet<TokenType> expected) : this(parser, null, null) {}

        public ParseException(Parser parser, string message, Token token = null, HashSet<TokenType> expected = null) : base(message) {
            Parser = parser;
            if (expected == null) expected = new HashSet<TokenType>();
            if (token == null) {
                token = parser.LastConsumedToken;
                if ((token != null) && (token.Next != null)) {
                    token = token.Next;
                }
            }
            Token = token;
            Expected = expected;
            callStack = new List<NonTerminalCall>(parser.ParsingStack);
        }

        public ParseException(string message) : base(message) {
            // TODO REVISIT - this is only here because CTL.ccc
            // throws with this signature
        }

        public override String ToString() {
            var oneOf = (Expected.Count == 1) ? "" : "one of ";
            var e = new List<TokenType>(Expected);
            var parts = e.ConvertAll<string>(e => e.ToString());
            var s = string.Join(", ", parts.ToArray());

            return $"{Message}\nUnexpected {Token} ({Token.Type}) at {Token.Location}: expected {oneOf}{s}";
        }
    }

#if settings.treeBuildingEnabled
    internal class NodeScope : List<Node> {
        private readonly NodeScope _parentScope;
        private readonly Parser _parser;

        public bool IsRootScope { get { return _parentScope == null; } }

        public Node RootNode {
            get {
                var ns = this;
                while (ns._parentScope != null) {
                    ns = ns._parentScope;
                }
                return (ns.Count == 0) ? null : ns[0];
            }
        }

        public uint NestingLevel {
            get {
                uint result = 0;
                var parent = this;
                while (parent._parentScope != null) {
                    result++;
                    parent = parent._parentScope;
                }
                return result;
            }
        }

        public NodeScope(Parser parser) {
            _parser = parser;
            _parentScope = parser.CurrentNodeScope;
            parser.CurrentNodeScope = this;
        }

        internal Node Peek() {
            Node result = null;

            if (Count > 0) {
                result = this[^1];
            }
            else if (_parentScope != null) {
                result = _parentScope.Peek();
            }
            return result;
        }

        internal Node Pop() {
            Node result = null;

            if (Count > 0) {
                result = Utils.Pop(this);
            }
            else if (_parentScope != null) {
                result = _parentScope.Pop();
            }
            return result;
        }

        internal void Poke(Node n) {
            if (Count == 0) {
                if (_parentScope != null) {
                    _parentScope.Poke(n);
                }
            }
            else {
                this[Count - 1] = n;
            }
        }

        internal void Close() {
            Debug.Assert(_parentScope != null);
            _parentScope.AddRange(this);
            _parser.CurrentNodeScope = _parentScope;
        }

        internal NodeScope Clone() {
            throw new NotImplementedException("NodeScope.Clone not yet implemented");
        }
    }

#endif
    //
    // Class that represents entering a grammar production
    //
    internal class NonTerminalCall {
        public Parser Parser { get; private set; }
        public string SourceFile { get; private set; }
        public string ProductionName { get; private set; }
        public uint Line { get; private set; }
        public uint Column { get; private set; }
        // REVISIT: Node.NodeType when tree building?
#if settings.faultTolerant
        public ISet<TokenType> FollowSet { get; private set; }
/#if

        internal NonTerminalCall(Parser parser, string fileName, string productionName, uint line, uint column[#if settings.faultTolerant], ISet<TokenType> followSet[/#if]) {
            Parser = parser;
            SourceFile = fileName;
            ProductionName = productionName;
            Line = line;
            Column = column;
#if settings.faultTolerant
            FollowSet = followSet;
/#if
        }
/*
        private (string productionName, string sourceFile, uint line) CreateStackTraceElement() {
            return (ProductionName, SourceFile, Line);
        }
 */
    }

    internal class ParseState {
        public Parser Parser { get; private set; }
        public Token LastConsumed { get; private set; }
        public IList<NonTerminalCall> ParsingStack { get; private set; }
[#if MULTIPLE_LEXICAL_STATE_HANDLING]
        public LexicalState LexicalState {get; private set; }
[/#if]
[#if settings.treeBuildingEnabled]
        public NodeScope NodeScope { get; private set; }
[/#if]

        internal ParseState(Parser parser) {
            Parser = parser;
            LastConsumed = parser.LastConsumedToken;
            ParsingStack = new List<NonTerminalCall>(parser.ParsingStack);
[#if MULTIPLE_LEXICAL_STATE_HANDLING]
            LexicalState = parser.tokenSource.LexicalState;
[/#if]
[#if settings.treeBuildingEnabled]
            NodeScope = parser.CurrentNodeScope.Clone();
[/#if]
        }
    }

    public class Parser[#if unwanted!false] : IObservable<LogInfo>[/#if] {

        private const uint UNLIMITED = (1U << 31) - 1;

        public string InputSource { get; private set; }
        public Token LastConsumedToken { get; private set; }
        private Token currentLookaheadToken;
        public bool ScanToEnd { get; private set; }
        internal IList<NonTerminalCall> ParsingStack { get; private set; } = new List<NonTerminalCall>();
        internal readonly Lexer tokenSource;
[#if settings.treeBuildingEnabled]
        public bool BuildTree { get; set; } = ${CU.bool(settings.treeBuildingDefault)};
        public bool TokensAreNodes { get; set; } = ${CU.bool(settings.tokensAreNodes)};
        public bool UnparsedTokensAreNodes { get; set; } = ${CU.bool(settings.unparsedTokensAreNodes)};
        internal NodeScope CurrentNodeScope { get; set; }
[/#if]
#if settings.legacyGlitchyLookahead
        private readonly bool _legacyGlitchyLookahead = true;
#else
        private readonly bool _legacyGlitchyLookahead = false;
/#if

        // This property is for testing only
        public bool LegacyGlitchyLookahead {
            get { return _legacyGlitchyLookahead; }
        }

        private TokenType? _nextTokenType;
        private uint _remainingLookahead;
        private bool _hitFailure;
        private string _currentlyParsedProduction;
        private string _currentLookaheadProduction;
        private bool _passedPredicate;
        private int _passedPredicateThreshold = -1;
        private uint _lookaheadRoutineNesting;
#if settings.faultTolerant
        internal ISet<TokenType> OuterFollowSet { get; private set; }
        private ISet<TokenType> _currentFollowSet;
/#if
        private readonly IList<NonTerminalCall> _lookaheadStack = new List<NonTerminalCall>();
        private readonly IList<ParseState> _parseStateStack = new List<ParseState>();
#if settings.faultTolerant
   [#if settings.faultTolerantDefault]
        private bool _tolerantParsing = true;
   [#else]
        private bool _tolerantParsing = false;
   [/#if]
        private bool _pendingRecovery = false;
        private readonly IList<Node> _parsingProblems = new List<Node>();
/#if

[#if unwanted!false]
        private readonly IList<IObserver<LogInfo>> observers = new List<IObserver<LogInfo>>();

        public IDisposable Subscribe(IObserver<LogInfo> observer)
        {
            if (!observers.Contains(observer)) {
                observers.Add(observer);
            }
            return new Unsubscriber<LogInfo>(observers, observer);
        }

        internal void Log(LogLevel level, string message, params object[] arguments) {
            var info = new LogInfo(level, message, arguments);
            foreach (var observer in observers) {
                observer.OnNext(info);
            }
        }

[/#if]
        public Parser(string inputSource) {
            InputSource = inputSource;
            tokenSource = new Lexer(inputSource);
[#if settings.lexerUsesParser]
            tokenSource.Parser = this;
[/#if]
            LastConsumedToken = Lexer.DummyStartToken;
            LastConsumedToken.TokenSource = tokenSource;
[#if settings.treeBuildingEnabled]
            new NodeScope(this); // attaches NodeScope instance to Parser instance
[/#if]
${globals::translateParserInitializers()}
        }

[#if settings.faultTolerant]
        public void AddParsingProblem(BaseNode problem) {
            _parsingProblems.Add(problem);
        }

        public bool IsTolerant {
            get { return _tolerantParsing; }
            set { _tolerantParsing = value; }
        }
[#else]
        public bool IsTolerant {
            get { return false; }
        }
[/#if]




        private void PushLastTokenBack() {
[#if settings.treeBuildingEnabled]
            if (PeekNode().Equals(LastConsumedToken)) {
                PopNode();
            }
[/#if]
            LastConsumedToken = LastConsumedToken.PreviousToken;
        }

        private void StashParseState() {
            _parseStateStack.Add(new ParseState(this));
        }

        private ParseState PopParseState() {
            return _parseStateStack.Pop();
        }

        private void RestoreStashedParseState() {
            var state = PopParseState();
[#if settings.treeBuildingEnabled]
            CurrentNodeScope = state.NodeScope;
            ParsingStack = state.ParsingStack;
[/#if]
            if (state.LastConsumed != null) {
                // REVISIT
                LastConsumedToken = state.LastConsumed;
            }
[#if MULTIPLE_LEXICAL_STATE_HANDLING]
            tokenSource.Reset(LastConsumedToken, state.LexicalState);
[#else]
            tokenSource.Reset(LastConsumedToken);
[/#if]
        }

[#if settings.treeBuildingEnabled]
        public bool IsTreeBuildingEnabled { get { return BuildTree; } }

   [#embed "TreeBuildingCode.inc.ctl"]
[#else]
        public bool IsTreeBuildingEnabled { get { return false; } }

[/#if]
        internal void PushOntoCallStack(string methodName, string fileName, uint line, uint column) {
            ParsingStack.Add(new NonTerminalCall(this, fileName, methodName, line, column[#if settings.faultTolerant], _currentFollowSet[/#if]));
        }

        internal void PopCallStack() {
            var ntc = ParsingStack.Pop();
            _currentlyParsedProduction = ntc.ProductionName;
#if settings.faultTolerant
            OuterFollowSet = ntc.FollowSet;
/#if
        }

        internal void RestoreCallStack(int prevSize) {
            while (ParsingStack.Count > prevSize) {
                PopCallStack();
            }
        }

        // If the next token is cached, it returns that
        // Otherwise, it goes to the lexer.
        private Token NextToken(Token tok) {
            Token result = tokenSource.GetNextToken(tok);
            while (result.IsUnparsed) {
[#list grammar.parserTokenHooks as methodName]
                result = ${methodName}(result);
[/#list]
                result = tokenSource.GetNextToken(result);
            }
[#list grammar.parserTokenHooks as methodName]
            result = ${methodName}(result);
[/#list]
            _nextTokenType = null;
            return result;
        }

        internal Token GetNextToken() {
            return GetToken(1);
        }

        /**
        * If we are in a lookahead, it looks ahead/behind from the currentLookaheadToken
        * Otherwise, it is the lastConsumedToken
        */
        public Token GetToken(int index) {
            var t = (currentLookaheadToken == null) ? LastConsumedToken : currentLookaheadToken;
            for (var i = 0; i < index; i++) {
                t = NextToken(t);
            }
            for (var i = 0; i > index; i--) {
                t = t.PreviousToken;
                if (t == null) break;
            }
            return t;
        }

        internal string TokenImage(int n) {
            return GetToken(n).ToString();
        }

        internal TokenType GetTokenType(int n) {
            return GetToken(n).Type;
        }

        internal bool CheckNextTokenImage(string img, params string[] additionalImages) {
            var nextImage = TokenImage(1);

            if (nextImage.Equals(img)) {
                return true;
            }
            foreach (var ai in additionalImages) {
                if (nextImage.Equals(ai)) {
                    return true;
                }
            }
            return false;
        }

        internal bool CheckNextTokenType(TokenType tt, params TokenType[] additionalTypes) {
            var nextType = GetToken(1).Type;

            if (nextType == tt) {
                return true;
            }
            foreach (var at in additionalTypes) {
                if (nextType == at) {
                    return true;
                }
            }
            return false;
        }

        internal TokenType NextTokenType {
            get {
                if (_nextTokenType == null) {
                    _nextTokenType = NextToken(LastConsumedToken).Type;
                }
                return _nextTokenType.Value;
            }
        }

        internal void UncacheTokens() {
            tokenSource.UncacheTokens(GetToken(0));
        }

        internal bool ActivateTokenTypes(TokenType type, params TokenType[] types) {
            var result = false;
            var att = tokenSource.ActiveTokenTypes;
            if (!att.Contains(type)) {
                result = true;
                att.Add(type);
            }
            foreach (var tt in types) {
                if (!att.Contains(tt)) {
                    result = true;
                    att.Add(tt);
                }
            }
            tokenSource.Reset(GetToken(0));
            _nextTokenType = null;
            return result;
        }

        internal bool DeactivateTokenTypes(TokenType type, params TokenType[] types) {
            var result = false;
            var att = tokenSource.ActiveTokenTypes;

            if (att.Contains(type)) {
                result = true;
                att.Remove(type);
            }
            foreach (var tt in types) {
                if (att.Contains(tt)) {
                    result = true;
                    att.Remove(tt);
                }
            }
            tokenSource.Reset(GetToken(0));
            _nextTokenType = null;
            return result;
        }

        private void Fail(string message) {
            if (currentLookaheadToken == null) {
                throw new ParseException(this, message);
            }
            _hitFailure = true;
        }

        /**
        *Are we in the production of the given name, either scanning ahead or parsing?
        */
        private bool IsInProduction(params string[] prodNames) {
            if (_currentlyParsedProduction != null) {
                foreach (var name in prodNames) {
                    if (_currentlyParsedProduction.Equals(name)) return true;
                }
            }
            if (_currentLookaheadProduction != null ) {
                foreach (var name in prodNames) {
                    if (_currentLookaheadProduction.Equals(name)) return true;
                }
            }
            var it = new BackwardIterator<NonTerminalCall>(ParsingStack, _lookaheadStack);
            while (it.HasNext()) {
                var ntc = it.Next();
                foreach (var name in prodNames) {
                    if (ntc.ProductionName.Equals(name)) {
                        return true;
                    }
                }
            }
            return false;
        }
[#import "ParserProductions.inc.ctl" as ParserCode]
[@ParserCode.Productions /]
[#import "LookaheadRoutines.inc.ctl" as LookaheadCode]
[@LookaheadCode.Generate/]

[#embed "ErrorHandling.inc.ctl"]

[#if settings.treeBuildingEnabled]
    //
    // AST nodes
    //
[#list globals.sortedNodeClassNames as node]
  [#if !injector::hasInjectedCode(node)]
    [#if globals::nodeIsInterface(node)]
    public interface ${node} : Node {}
    [#else]
    public class ${node} : BaseNode {
        public ${node}(Lexer tokenSource) : base(tokenSource) {}
    }
    [/#if]

  [#else]
${globals::translateInjectedClass(node)}

  [/#if]
[/#list]
[/#if]

${globals::translateParserInjections(true)}
${globals::translateParserInjections(false)}

    }
}
