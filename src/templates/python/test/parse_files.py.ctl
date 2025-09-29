#var pyPackage = globals::getParserOutputDirectory()
#var extension = globals::getStringSetting("TEST_EXTENSION", "")
#var testProduction = globals::getStringSetting("TEST_PRODUCTION", "")
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright (C) 2024-2025 Vinay Sajip (vinay_sajip@yahoo.co.uk)
#
import argparse
import glob
import logging
import os
import sys
import time
import traceback

def setupPath():
    p = os.path.dirname(os.path.abspath(__file__))  # test directory
    p = os.path.dirname(p)  # parser output directory
    p = os.path.dirname(p)  # parent of parser output directory
    sys.path.append(p)


setupPath()


from ${pyPackage} import Parser

failures = successes = 0
start_time = time.time()


def parse_file(fn):
    global successes, failures

    parser = Parser(fn)
    try :
        parser.parse_${testProduction}()
        successes += 1
        print('The Python impl parsed %s.' % fn)
    except Exception as e:
        failures += 1
        s = type(e).__name__
        sys.stderr.write('The Python impl Failed to parse %s: %s\n' % (fn, s))
        traceback.print_exc(file=sys.stderr)


def process(options):
    for fn in options.inputs:
        if os.path.isfile(fn):
            parse_file(fn)
        elif not os.path.isdir(fn):
            raise ValueError('Not a file or directory: %s' % fn)
        else:
            p = os.path.join(fn, '**/*.${extension}')
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
    print('\nThe Python impl successfully parsed %s files.' % successes)
    print('The Python impl failed on %s files.' % failures)
    print('Duration: %.3f milliseconds.' % (1000 * (time.time() - start_time)))
    sys.exit(rc)
