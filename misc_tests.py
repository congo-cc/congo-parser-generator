#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright (C) 2024 Vinay Sajip (vinay_sajip@yahoo.co.uk)
#
import argparse
import glob
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

DEBUGGING = 'PY_DEBUG' in os.environ

logger = logging.getLogger(__name__)


CONGO_JAR = os.path.join(os.getcwd(), 'congocc.jar')


def ensure_dir(p):
    d = os.path.dirname(p)
    if not os.path.exists(d):
        os.makedirs(d)


def decode_and_clean(b):
    return b.decode('utf-8').strip().replace('\r\n', '\n')


def run_command(cmd, **kwargs):
    if isinstance(cmd, str):
        cmd = cmd.split()
    out_wanted = kwargs.pop('out', False)
    logger.debug('Running: %s', ' '.join(cmd))
    if out_wanted:
        if kwargs.get('check', True):  # must run without errors
            # Normalize newlines across platforms
            return decode_and_clean(subprocess.check_output(cmd, **kwargs))
        else:
            kwargs.setdefault('stdout', subprocess.PIPE)
            kwargs.setdefault('stderr', subprocess.PIPE)
    else:  # must run without errors
        kwargs.setdefault('check', True)
    return subprocess.run(cmd, **kwargs)


class BaseTestCase(unittest.TestCase):
    def setUp(self):
        self.workdir = tempfile.mkdtemp(prefix='congocc-test-misc-')

    def tearDown(self):
        shutil.rmtree(self.workdir)

    def collect_files(self, start):
        p = os.path.join(start, '**')
        files = glob.glob(p, recursive=True)
        result = set()
        for fn in files:
            if os.path.isdir(fn):
                continue
            r = os.path.relpath(fn, start)
            if os.name == 'nt':
                r = r.replace(os.sep, '/')
            result.add(r)
        return result

    def test_file_generation(self):
        """
        Test that the expected files are generated for a grammar
        """
        wd = self.workdir
        spec = os.path.join('examples', 'lua', '*.ccc')
        files = glob.glob(spec)
        for p in files:
            shutil.copy(p, wd)
        # First, generate set of files
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', 'Lua.ccc']
        p = run_command(cmd, cwd=wd)
        start = os.path.join(wd, 'org', 'parsers', 'lua')
        actual = self.collect_files(start)
        expected = {
            'ast/AdditiveExpression.java',
            'ast/AndExpression.java',
            'ast/ANY_CHAR.java',
            'ast/Args.java',
            'ast/Assignment.java',
            'ast/Attribute.java',
            'ast/AttributeNameList.java',
            'ast/BaseNode.java',
            'ast/Block.java',
            'ast/BreakStatement.java',
            'ast/ComparisonExpression.java',
            'ast/Delimiter.java',
            'ast/DoBlock.java',
            'ast/EmptyStatement.java',
            'ast/Expression.java',
            'ast/ExpressionList.java',
            'ast/Field.java',
            'ast/FieldList.java',
            'ast/ForStatement.java',
            'ast/FunctionBody.java',
            'ast/FunctionCall.java',
            'ast/FunctionDeclaration.java',
            'ast/FunctionDef.java',
            'ast/FunctionName.java',
            'ast/GotoStatement.java',
            'ast/IfStatement.java',
            'ast/KeyWord.java',
            'ast/Label.java',
            'ast/LastStatement.java',
            'ast/Literal.java',
            'ast/LocalAttributeAssignment.java',
            'ast/LocalFunctionDeclaration.java',
            'ast/LongString.java',
            'ast/LONGSTRING_START.java',
            'ast/MultiLineComment.java',
            'ast/MULTILINE_START.java',
            'ast/MultiplicativeExpression.java',
            'ast/NameAndArgs.java',
            'ast/Name.java',
            'ast/NameList.java',
            'ast/Operator.java',
            'ast/OrExpression.java',
            'ast/ParamList.java',
            'ast/PowerExpression.java',
            'ast/PrefixExp.java',
            'ast/PrimaryExpression.java',
            'ast/RepeatStatement.java',
            'ast/Root.java',
            'ast/SHEBANG.java',
            'ast/SingleLineComment.java',
            'ast/SINGLE_LINE_COMMENT_START.java',
            'ast/Statement.java',
            'ast/StringCatExpression.java',
            'ast/TableConstructor.java',
            'ast/UnaryExpression.java',
            'ast/Var.java',
            'ast/VarList.java',
            'ast/VarOrExp.java',
            'ast/VarSuffix.java',
            'ast/WhileStatement.java',
            'ast/WS.java',
            'InvalidToken.java',
            'LuaLexer.java',
            'LuaParser.java',
            'Node.java',
            'NonTerminalCall.java',
            'ParseException.java',
            'Token.java',
            'TokenSource.java',
        }
        self.assertEqual(actual, expected)
        # Now, run the same command but in fault-tolerant mode
        cmd[-1:-1] = ['-p', 'FT=true']
        p = run_command(cmd, cwd=wd)
        actual = self.collect_files(start)
        expected2 = expected | {
            'ast/InvalidNode.java',
            'ParsingProblem.java',
        }
        self.assertEqual(actual, expected2)
        # remove the fault tolerant spec
        cmd[-3:-1] = []
        p = run_command(cmd, cwd=wd)
        actual = self.collect_files(start)
        self.assertEqual(actual, expected)  # back to the original set

    def copy_files(self, sd, dd, *, specs='**/*', special_processor=None):
        """
        Copy selected files from source directory to destination, and allow
        some special processing to be done on the copies.
        """
        if isinstance(specs, str):
            specs = specs.split()
        for spec in specs:
            p = os.path.join(sd, spec)
            for fn in glob.glob(p, recursive=True):
                if os.path.isdir(fn):
                    continue
                dp = os.path.join(dd, os.path.relpath(fn, sd))
                ensure_dir(dp)
                shutil.copy2(fn, dp)
                if special_processor:
                    special_processor(dp)

    def test_nested_lookahead(self):
        """
        Test that nested lookahead works
        """
        wd = self.workdir
        sd = os.path.join('tests', 'nested_lookahead')
        if 'CI' not in os.environ:
            special_handling = None
        else:
            def special_handling(dp):
                if not dp.endswith('.csproj'):
                    return
                with open(dp, encoding='utf-8') as f:
                    s = f.read()
                s = s.replace('net5.0', 'net8.0')
                with open(dp, 'w', encoding='utf-8') as f:
                    f.write(s)
        self.copy_files(sd, wd, special_processor=special_handling)

        GOOD = 'nla-good.txt: glitchy = false: lookahead succeeded, parse succeeded'
        BAD = 'nla-bad.txt: glitchy = false: lookahead failed, parse succeeded'
        GLITCHY_GOOD = 'nla-good.txt: glitchy = true: lookahead succeeded, parse succeeded'
        GLITCHY_BAD = 'nla-bad.txt: glitchy = true: lookahead succeeded, parse failed'

        def run_tester(cmd, glitchy):
            good = GLITCHY_GOOD if glitchy else GOOD
            bad = GLITCHY_BAD if glitchy else BAD
            cmd = cmd.split()
            cmd.append('nla-good.txt')
            out = run_command(cmd, cwd=wd, out=True)
            self.assertEqual(out, good)
            cmd[-1] = 'nla-bad.txt'
            out = run_command(cmd, cwd=wd, out=True)
            self.assertEqual(out, bad)

        env = os.environ.copy()
        env['CONGOCC_PYTHON_REAPER_OFF'] = 'true'
        env['CONGOCC_CSHARP_REAPER_OFF'] = 'true'
        # Generate Java parser, non-glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        # Compile the files
        cmd = 'javac nla_test/TestParser.java Tester.java'.split()
        p = run_command(cmd, cwd=wd)
        # Run the tests with the Java parser
        run_tester('java Tester', False)

        # Generate Java parser, glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-p', 'LEGACY_GLITCHY_LOOKAHEAD=true', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        # Compile the files (no need to recompile the tester)
        cmd = 'javac nla_test/TestParser.java'.split()
        p = run_command(cmd, cwd=wd)
        # Run the tests with the Java parser
        run_tester('java Tester', True)

        # Generate Python parser, non-glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-lang', 'python', 'NLA.ccc']
        p = run_command(cmd, cwd=wd, env=env)
        # Run the tests with the Python parser
        run_tester('python3 tester.py', False)

        # Generate Python parser, glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-p', 'LEGACY_GLITCHY_LOOKAHEAD=true', '-lang', 'python', 'NLA.ccc']
        p = run_command(cmd, cwd=wd, env=env)
        # Run the tests with the Python parser
        run_tester('python3 tester.py', True)

        # Generate C# parser, non-glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-lang', 'csharp', 'NLA.ccc']
        p = run_command(cmd, cwd=wd, env=env)
        # Compile the files
        td = os.path.join(wd, 'cs-tester')
        tester = os.path.join(td, 'bin', 'tester')
        cmd = 'dotnet build -o bin -v quiet --nologo -property:WarningLevel=0'.split()
        p = run_command(cmd, cwd=td, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

        # Run the tests with the C# parser
        run_tester(tester, False)

        # Generate C# parser, glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-p', 'LEGACY_GLITCHY_LOOKAHEAD=true', '-lang', 'csharp', 'NLA.ccc']
        p = run_command(cmd, cwd=wd, env=env)
        # Compile the files
        cmd = 'dotnet build -o bin -v quiet --nologo -property:WarningLevel=0'.split()
        p = run_command(cmd, cwd=td, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        # Run the tests with the C# parser
        run_tester(tester, True)

    def test_unparsed(self):
        """
        Test capturing unparsed tokens in the AST
        """
        self.maxDiff = None
        wd = self.workdir
        sd = os.path.join('tests', 'unparsed')
        self.copy_files(sd, wd)
        sd = os.path.join('examples', 'csharp')
        self.copy_files(sd, wd, specs='*.ccc')
        # Generate Java parsers
        gcmd = ['java', '-jar', CONGO_JAR, '-q', '-n', 'PPDirectiveLine.ccc']
        p = run_command(gcmd, cwd=wd)
        gcmd[-1] = 'CSharp.ccc'
        p = run_command(gcmd, cwd=wd)
        # Compile them
        ccmd = 'javac org/parsers/csharp/CSharpParser.java CSParse.java'.split()
        p = run_command(ccmd, cwd=wd)
        # Run the C# parser
        rcmd = 'java CSParse dummy.cs'.split()
        out = run_command(rcmd, cwd=wd, out=True)
        OUT = '''
<CompilationUnit (2, 1)-(8, 2)>
  <NamespaceDeclaration (2, 1)-(8, 1)>
    KeyWord: (2, 1) - (2, 9): namespace
    <QualifiedIdentifier (2, 11)-(2, 17)>
      Identifier: (2, 11) - (2, 13): foo
      Operator: (2, 14) - (2, 14): .
      Identifier: (2, 15) - (2, 17): bar
    <NamespaceBody (2, 19)-(8, 1)>
      Delimiter: (2, 19) - (2, 19): {
      Delimiter: (8, 1) - (8, 1): }
  Token: (9, 1) - (9, 1): EOF
'''.strip()
        self.assertEqual(out, OUT)
        p = run_command('java CSParse valid.cs', cwd=wd, out=True, check=False)
        self.assertEqual(p.returncode, 0)
        self.assertEqual(p.stderr, b'')
        OUT = '''
<CompilationUnit (3, 1)-(11, 2)>
  <NamespaceDeclaration (3, 1)-(11, 1)>
    KeyWord: (3, 1) - (3, 9): namespace
    <QualifiedIdentifier (3, 11)-(3, 17)>
      Identifier: (3, 11) - (3, 13): foo
      Operator: (3, 14) - (3, 14): .
      Identifier: (3, 15) - (3, 17): bar
    <NamespaceBody (3, 19)-(11, 1)>
      Delimiter: (3, 19) - (3, 19): {
      <ClassDeclaration (6, 5)-(6, 16)>
        KeyWord: (6, 5) - (6, 9): class
        Identifier: (6, 11) - (6, 13): Foo
        Delimiter: (6, 15) - (6, 15): {
        Delimiter: (6, 16) - (6, 16): }
      Delimiter: (11, 1) - (11, 1): }
  Token: (12, 1) - (12, 1): EOF
'''.strip()
        self.assertEqual(decode_and_clean(p.stdout), OUT)

        # Now repeat, but with unparsed tokens as nodes.

        # import pdb; pdb.set_trace()
        gcmd[-1:-1] = '-p UNPARSED_TOKENS_ARE_NODES=true'.split()
        p = run_command(gcmd, cwd=wd)
        p = run_command(ccmd, cwd=wd)
        out = run_command(rcmd, cwd=wd, out=True)
        # REVISIT the hashes around the comment should not be there
        OUT = '''
<CompilationUnit (1, 1)-(8, 2)>
  <NamespaceDeclaration (1, 1)-(8, 1)>
    PPLine: (1, 1) - (1, 25): #pragma warn disable 999
    KeyWord: (2, 1) - (2, 9): namespace
    <QualifiedIdentifier (2, 11)-(2, 17)>
      Identifier: (2, 11) - (2, 13): foo
      Operator: (2, 14) - (2, 14): .
      Identifier: (2, 15) - (2, 17): bar
    <NamespaceBody (2, 19)-(8, 1)>
      Delimiter: (2, 19) - (2, 19): {
      PPLine: (3, 1) - (3, 9): #if true
      Comment: (4, 5) - (4, 28): /* This is a comment. */
      PPLine: (5, 1) - (5, 6): #else
      IGNORED_LINE: (6, 1) - (6, 24): // This is another.
      PPLine: (7, 1) - (7, 7): #endif
      Delimiter: (8, 1) - (8, 1): }
  Token: (9, 1) - (9, 1): EOF
'''.strip()
        self.assertEqual(out, OUT)
        p = run_command('java CSParse invalid1.cs', cwd=wd, out=True, check=False)
        self.assertEqual(p.returncode, 1)
        self.assertEqual(p.stdout, b'')
        self.assertIn(b'error at (or somewhere around) invalid1.cs:2:1', p.stderr)
        p = run_command('java CSParse invalid2.cs', cwd=wd, out=True, check=False)
        self.assertEqual(p.returncode, 1)
        self.assertEqual(p.stdout, b'')
        self.assertIn(b'error at (or somewhere around) invalid2.cs:3:1', p.stderr)
        p = run_command('java CSParse invalid3.cs', cwd=wd, out=True, check=False)
        self.assertEqual(p.returncode, 1)
        self.assertEqual(p.stdout, b'')
        self.assertIn(b'error at (or somewhere around) invalid3.cs:3:1', p.stderr)
        p = run_command('java CSParse invalid4.cs', cwd=wd, out=True, check=False)
        self.assertEqual(p.returncode, 1)
        self.assertEqual(p.stdout, b'')
        self.assertIn(b'error at (or somewhere around) invalid4.cs:3:1', p.stderr)


def process(options):
    unittest.main()


def main():
    fn = os.path.basename(__file__)
    fn = os.path.splitext(fn)[0]
    lfn = os.path.expanduser('~/logs/%s.log' % fn)
    if os.path.isdir(os.path.dirname(lfn)):
        logging.basicConfig(level=logging.DEBUG, filename=lfn, filemode='w',
                            format='%(message)s')
    adhf = argparse.ArgumentDefaultsHelpFormatter
    ap = argparse.ArgumentParser(formatter_class=adhf, prog=fn)
    # aa = ap.add_argument
    # aa('input', metavar='INPUT', help='File to process')
    # aa('--flag', '-f', default=False, action='store_true', help='Boolean option')
    options = ap.parse_known_args()
    process(options)


if __name__ == '__main__':
    try:
        rc = main()
    except KeyboardInterrupt:
        rc = 2
    except Exception as e:
        if DEBUGGING:
            s = ' %s:' % type(e).__name__
        else:
            s = ''
        sys.stderr.write('Failed:%s %s\n' % (s, e))
        if DEBUGGING: import traceback; traceback.print_exc()
        rc = 1
    sys.exit(rc)
