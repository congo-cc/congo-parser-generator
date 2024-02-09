import sys

from nla_testparser import Parser, ParseException

p = Parser(sys.argv[1])
try:
    p.parse_Root()
    p.parse_status = 'parse succeeded'
except ParseException:
    p.parse_status = 'parse failed'
print('%s: glitchy = %s: %s, %s' % (sys.argv[1], str(p.legacy_glitchy_lookahead).lower(), p.lookahead_status, p.parse_status))
