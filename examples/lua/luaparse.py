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
import time
import traceback

from luaparser import Parser

failures = successes = 0
start_time = time.time()

def parse_file(fn):
    parser = Parser(fn)
    global failures, successes
    try :
        parser.parse_Root()
        successes= successes +1
        print('The python lua parser parsed %s' % fn)
    except Exception as e:
        failures +=1
        s = ' %s:' % type(e).__name__
        sys.stderr.write('Failed:%s %s\n' % (s, e))
        traceback.print_exc()


def process(options):
    for fn in options.inputs:
        if os.path.isfile(fn):
            parse_file(fn)
        elif not os.path.isdir(fn):
            raise ValueError('Not a file or directory: %s' % fn)
        else:
            p = os.path.join(fn, '**/*.lua')
            for fn in glob.iglob(p, recursive=True):
                parse_file(fn)


def main():
    fn = os.path.basename(__file__)
    fn = os.path.splitext(fn)[0]
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
    print('\nParsed ' + successes.__str__() + ' lua files successfully')
    print('Failed on ' + failures.__str__() + ' files.')
    print('Duration: ' + (time.time()-start_time).__str__() + ' seconds.\n')
    sys.exit(rc)
