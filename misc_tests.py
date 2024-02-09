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


def run_command(cmd, **kwargs):
    logger.debug('Running: %s', ' '.join(cmd))
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
        Test that the expected files are generated for a grammar.
        """
        wd = self.workdir
        spec = os.path.join('examples', 'lua', '*.ccc')
        files = glob.glob(spec)
        for p in files:
            shutil.copy(p, wd)
        # First, generate set of files
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', 'Lua.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
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
        cmd[-1:-1] = ['-p', 'FAULT_TOLERANT=true']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        actual = self.collect_files(start)
        expected2 = {
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
            'ast/InvalidNode.java',
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
            'ParsingProblem.java',
            'Token.java',
            'TokenSource.java',
        }
        self.assertEqual(actual, expected2)
        self.assertEqual(expected2 - expected, {'ParsingProblem.java', 'ast/InvalidNode.java'})
        # remove the fault tolerant spec
        cmd[-3:-1] = []
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        actual = self.collect_files(start)
        self.assertEqual(actual, expected)  # back to the original set

    def test_nested_lookahead(self):
        # Copy test files to working directory
        wd = self.workdir
        sd = os.path.join('tests', 'nested_lookahead')
        p = os.path.join(sd, '**/*')
        for fn in glob.glob(p, recursive=True):
            if os.path.isdir(fn):
                continue
            dp = os.path.join(wd, os.path.relpath(fn, sd))
            ensure_dir(dp)
            shutil.copy2(fn, dp)
            # change the .NET version in the project for CI runners
            if 'CI' in os.environ and dp.endswith('.csproj'):
                with open(dp, encoding='utf-8') as f:
                    s = f.read()
                s = s.replace('net5.0', 'net7.0')
                with open(dp, 'w', encoding='utf-8') as f:
                    f.write(s)


        # Generate Java parser, non-glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Compile the files
        cmd = 'javac nla_test/TestParser.java Tester.java'.split()
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Run the tests with the Java parser
        cmd = 'java Tester nla-good.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-good.txt: glitchy = false: lookahead succeeded, parse succeeded')
        cmd = 'java Tester nla-bad.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-bad.txt: glitchy = false: lookahead failed, parse succeeded')

        # Generate Java parser, glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-p', 'LEGACY_GLITCHY_LOOKAHEAD=true', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Compile the files (no need to recompile the tester)
        cmd = 'javac nla_test/TestParser.java'.split()
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Run the tests with the Java parser
        cmd = 'java Tester nla-good.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-good.txt: glitchy = true: lookahead succeeded, parse succeeded')
        cmd = 'java Tester nla-bad.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-bad.txt: glitchy = true: lookahead succeeded, parse failed')

        # Generate Python parser, non-glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-lang', 'python', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Run the tests with the Python parser
        cmd = 'python3 tester.py nla-good.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-good.txt: glitchy = false: lookahead succeeded, parse succeeded')
        cmd = 'python3 tester.py nla-bad.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-bad.txt: glitchy = false: lookahead failed, parse succeeded')

        # Generate Python parser, glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-p', 'LEGACY_GLITCHY_LOOKAHEAD=true', '-lang', 'python', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Run the tests with the Python parser
        cmd = 'python3 tester.py nla-good.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-good.txt: glitchy = true: lookahead succeeded, parse succeeded')
        cmd = 'python3 tester.py nla-bad.txt'.split()
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-bad.txt: glitchy = true: lookahead succeeded, parse failed')

        # if 'CI' in os.environ:  # Temporarily don't run the tests while .NET version issues are investigated
            # return

        # Generate C# parser, non-glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-lang', 'csharp', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Compile the files
        td = os.path.join(wd, 'cs-tester')
        tester = os.path.join(td, 'bin', 'tester')
        cmd = 'dotnet build -o bin -v quiet --nologo'.split()
        p = run_command(cmd, cwd=td)
        self.assertEqual(p.returncode, 0)

        # Run the tests with the C# parser
        cmd = [tester, 'nla-good.txt']
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-good.txt: glitchy = false: lookahead succeeded, parse succeeded')
        cmd = [tester, 'nla-bad.txt']
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-bad.txt: glitchy = false: lookahead failed, parse succeeded')

        # Generate C# parser, glitchy
        cmd = ['java', '-jar', CONGO_JAR, '-q', '-n', '-p', 'LEGACY_GLITCHY_LOOKAHEAD=true', '-lang', 'csharp', 'NLA.ccc']
        p = run_command(cmd, cwd=wd)
        self.assertEqual(p.returncode, 0)
        # Compile the files
        cmd = 'dotnet build -o bin -v quiet --nologo'.split()
        p = run_command(cmd, cwd=td)
        self.assertEqual(p.returncode, 0)        # Run the tests with the C# parser
        cmd = [tester, 'nla-good.txt']
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-good.txt: glitchy = true: lookahead succeeded, parse succeeded')
        cmd = [tester, 'nla-bad.txt']
        out = subprocess.check_output(cmd, cwd=wd).decode('utf-8').strip()
        self.assertEqual(out, 'nla-bad.txt: glitchy = true: lookahead succeeded, parse failed')

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
