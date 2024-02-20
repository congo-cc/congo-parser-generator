#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright (C) 2024 Vinay Sajip (vinay_sajip@yahoo.co.uk)
#
import argparse
import glob
import logging
import os
import sys

from csharpparser import Parser

DEBUGGING = 'PY_DEBUG' in os.environ

logger = logging.getLogger(__name__)

def parse_file(fn):
    parser = Parser(fn)
    parser.parse_CompilationUnit()
    print('Parser in Python successfully parsed %s' % fn)


def process(options):
    for fn in options.inputs:
        if os.path.isfile(fn):
            parse_file(fn)
        elif not os.path.isdir(fn):
            raise ValueError('Not a file or directory: %s' % fn)
        else:
            p = os.path.join(fn, '**/*.cs')
            for fn in glob.iglob(p, recursive=True):
                parse_file(fn)


def main():
    fn = os.path.basename(__file__)
    fn = os.path.splitext(fn)[0]
    lfn = os.path.expanduser('~/logs/%s.log' % fn)
    if os.path.isdir(os.path.dirname(lfn)):
        logging.basicConfig(level=logging.DEBUG, filename=lfn, filemode='w',
                            format='%(message)s')
    adhf = argparse.ArgumentDefaultsHelpFormatter
    ap = argparse.ArgumentParser(formatter_class=adhf, prog=fn)
    aa = ap.add_argument
    aa('inputs', default=[], metavar='INPUT', nargs='+', help='File to process')
    # aa('--flag', '-f', default=False, action='store_true', help='Boolean option')
    options = ap.parse_args()
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
