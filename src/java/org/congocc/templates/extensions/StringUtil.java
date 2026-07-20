package org.congocc.templates.extensions;

import java.io.UnsupportedEncodingException;
import java.util.*;
import org.congocc.templates.core.parser.ParseException;

/**
 *  Some text related utilities.
 *
 *  @version $Id: StringUtil.java,v 1.48 2005/06/01 22:39:08 ddekany Exp $
 */
public class StringUtil {
    /**
     *  HTML encoding (does not convert line breaks).
     *  Replaces all '&gt;' '&lt;' '&amp;' and '"' with entity reference
     */
    public static String HTMLEnc(String s) {
        return XMLOrXHTMLEnc(s, "'");
    }

    /**
     *  XML Encoding.
     *  Replaces all '&gt;' '&lt;' '&amp;', "'" and '"' with entity reference
     */
    public static String XMLEnc(String s) {
        return XMLOrXHTMLEnc(s, "&apos;");
    }

    /**
     *  XHTML Encoding.
     *  Replaces all '&gt;' '&lt;' '&amp;', "'" and '"' with entity reference
     *  suitable for XHTML decoding in common user agents (including legacy
     *  user agents, which do not decode "&apos;" to "'", so "&#39;" is used
     *  instead [see http://www.w3.org/TR/xhtml1/#C_16])
     */
    public static String XHTMLEnc(String s) {
        return XMLOrXHTMLEnc(s, "&#39;");
    }

    private static String XMLOrXHTMLEnc(String s, String aposReplacement) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '<' -> buf.append("&lt;");
                case '>' -> buf.append("&gt;");
                case '&' -> buf.append("&amp;");
                case '"' -> buf.append("&quot;");
                case '\'' -> buf.append(aposReplacement);
                default -> buf.append(ch);
            }
        }
        return buf.toString();
    }

    public static String RTFEnc(String s) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' || ch == '{' || ch == '}') {
                buf.append('\\');
            }
            buf.append(ch);
        }
        return buf.length() == s.length() ? s : buf.toString();
    }

    /**
     * URL encoding (like%20this).
     */
    public static String URLEnc(String s, String charset)
            throws UnsupportedEncodingException {
        int ln = s.length();
        int i;
        for (i = 0; i < ln; i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '_' || c == '-' || c == '.' || c == '!' || c == '~'
                    || c >= '\'' && c <= '*')) {
                break;
            }
        }
        if (i == ln) {
            // Nothing to escape
            return s;
        }

        StringBuilder b = new StringBuilder(ln + ln / 3 + 2);
        b.append(s.substring(0, i));

        int encstart = i;
        for (i++; i < ln; i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '_' || c == '-' || c == '.' || c == '!' || c == '~'
                    || c >= '\'' && c <= '*') {
                if (encstart != -1) {
                    byte[] o = s.substring(encstart, i).getBytes(charset);
                    for (int j = 0; j < o.length; j++) {
                        b.append('%');
                        byte bc = o[j];
                        int c1 = bc & 0x0F;
                        int c2 = (bc >> 4) & 0x0F;
                        b.append((char) (c2 < 10 ? c2 + '0' : c2 - 10 + 'A'));
                        b.append((char) (c1 < 10 ? c1 + '0' : c1 - 10 + 'A'));
                    }
                    encstart = -1;
                }
                b.append(c);
            } else {
                if (encstart == -1) {
                    encstart = i;
                }
            }
        }
        if (encstart != -1) {
            byte[] o = s.substring(encstart, i).getBytes(charset);
            for (int j = 0; j < o.length; j++) {
                b.append('%');
                byte bc = o[j];
                int c1 = bc & 0x0F;
                int c2 = (bc >> 4) & 0x0F;
                b.append((char) (c2 < 10 ? c2 + '0' : c2 - 10 + 'A'));
                b.append((char) (c1 < 10 ? c1 + '0' : c1 - 10 + 'A'));
            }
        }

        return b.toString();
    }

    /**
     * CTL string literal decoding.
     *
     * \\, \", \', \n, \t, \r, \b and \f will be replaced according to
     * Java rules. In additional, it knows \g, \l, \a and \{ which are
     * replaced with &lt;, >, &amp; and { respectively.
     * \x works as hexadecimal character code escape. The character
     * codes are interpreted according to UCS basic plane (Unicode).
     * "f\x006Fo", "f\x06Fo" and "f\x6Fo" will be "foo".
     * "f\x006F123" will be "foo123" as the maximum number of digits is 4.
     *
     * All other \X (where X is any character not mentioned above or End-of-string)
     * will cause a ParseException.
     *
     * @param s String literal <em>without</em> the surrounding quotation marks
     * @return String with all escape sequences resolved
     * @throws ParseException if there string contains illegal escapes
     */
    public static String CTLStringLiteralDec(String s) {
        int idx = s.indexOf('\\');
        if (idx == -1) {
            return s;
        }
        int lidx = s.length() - 1;
        int bidx = 0;
        StringBuilder buf = new StringBuilder(lidx);
        do {
            buf.append(s.substring(bidx, idx));
            if (idx >= lidx) {
                throw new ParseException("The last character of string literal is backslash", 0,0);
            }
            char c = s.charAt(idx + 1);
            switch (c) {
                case '"':
                    buf.append('"');
                    bidx = idx + 2;
                    break;
                case '\'':
                    buf.append('\'');
                    bidx = idx + 2;
                    break;
                case '\\':
                    buf.append('\\');
                    bidx = idx + 2;
                    break;
                case 'n':
                    buf.append('\n');
                    bidx = idx + 2;
                    break;
                case 'r':
                    buf.append('\r');
                    bidx = idx + 2;
                    break;
                case 't':
                    buf.append('\t');
                    bidx = idx + 2;
                    break;
                case 'f':
                    buf.append('\f');
                    bidx = idx + 2;
                    break;
                case 'b':
                    buf.append('\b');
                    bidx = idx + 2;
                    break;
                case 'g':
                    buf.append('>');
                    bidx = idx + 2;
                    break;
                case 'l':
                    buf.append('<');
                    bidx = idx + 2;
                    break;
                case 'a':
                    buf.append('&');
                    bidx = idx + 2;
                    break;
                case '{':
                    buf.append('{');
                    bidx = idx + 2;
                    break;
                case 'x': {
                    idx += 2;
                    int x = idx;
                    int y = 0;
                    int z = lidx > idx + 3 ? idx + 3 : lidx;
                    while (idx <= z) {
                        char b = s.charAt(idx);
                        if (b >= '0' && b <= '9') {
                            y <<= 4;
                            y += b - '0';
                        } else if (b >= 'a' && b <= 'f') {
                            y <<= 4;
                            y += b - 'a' + 10;
                        } else if (b >= 'A' && b <= 'F') {
                            y <<= 4;
                            y += b - 'A' + 10;
                        } else {
                            break;
                        }
                        idx++;
                    }
                    if (x < idx) {
                        buf.append((char) y);
                    } else {
                        throw new ParseException("Invalid \\x escape in a string literal",0,0);
                    }
                    bidx = idx;
                    break;
                }
                default:
                    throw new ParseException("Invalid escape sequence (\\" + c + ") in a string literal",0,0);
            }
            idx = s.indexOf('\\', bidx);
        } while (idx != -1);
        buf.append(s.substring(bidx));

        return buf.toString();
    }

    public static String capitalize(String s) {
        StringTokenizer st = new StringTokenizer(s, " \t\r\n", true);
        StringBuilder buf = new StringBuilder(s.length());
        while (st.hasMoreTokens()) {
            String tok = st.nextToken();
            buf.append(tok.substring(0, 1).toUpperCase());
            buf.append(tok.substring(1).toLowerCase());
        }
        return buf.toString();
    }

    public static boolean getYesNo(String s) {
        if (s.startsWith("\"")) {
            s = s.substring(1, s.length() -1);

        }
        if (s.equalsIgnoreCase("n")
                || s.equalsIgnoreCase("no")
                || s.equalsIgnoreCase("f")
                || s.equalsIgnoreCase("false")) {
            return false;
        }
        else if  (s.equalsIgnoreCase("y")
                || s.equalsIgnoreCase("yes")
                || s.equalsIgnoreCase("t")
                || s.equalsIgnoreCase("true")) {
            return true;
        }
        throw new IllegalArgumentException("Illegal boolean value: " + s);
    }

    /**
     * Removes the line-break from the end of the string.
     */
    public static String chomp(String s) {
        if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
        char lastChar = s.length() == 0 ? 0 : s.charAt(s.length()-1);
        return (lastChar != '\n' && lastChar != '\r') ? s : s.substring(0, s.length() - 1);
    }

    /**
     * Quotes string as Java Language string literal.
     * Returns string <code>"null"</code> if <code>s</code>
     * is <code>null</code>.
     */
    public static String jQuote(String s) {
        if (s == null) {
            return "null";
        }
        int ln = s.length();
        StringBuilder b = new StringBuilder(ln + 4);
        b.append('"');
        for (int i = 0; i < ln; i++) {
            char c = s.charAt(i);
            if (c == '"') {
                b.append("\\\"");
            } else if (c == '\\') {
                b.append("\\\\");
            } else if (c < 0x20) {
                if (c == '\n') {
                    b.append("\\n");
                } else if (c == '\r') {
                    b.append("\\r");
                } else if (c == '\f') {
                    b.append("\\f");
                } else if (c == '\b') {
                    b.append("\\b");
                } else if (c == '\t') {
                    b.append("\\t");
                } else {
                    b.append("\\u00");
                    int x = c / 0x10;
                    b.append((char) (x < 0xA ? x + '0' : x - 0xA + 'A'));
                    x = c & 0xF;
                    b.append((char) (x < 0xA ? x + '0' : x - 0xA + 'A'));
                }
            } else {
                b.append(c);
            }
        } // for each characters
        b.append('"');
        return b.toString();
    }

    /**
     * Escapes the <code>String</code> with the escaping rules of Java language
     * string literals, so it is safe to insert the value into a string literal.
     * The resulting string will not be quoted.
     *
     * <p>In additional, all characters under UCS code point 0x20, that has no
     * dedicated escape sequence in Java language, will be replaced with UNICODE
     * escape (<tt>\<!-- -->u<i>XXXX</i></tt>).
     *
     * @see #jQuote(String)
     */
    public static String javaStringEncode(String s) {
        int ln = s.length();
        for (int i = 0; i < ln; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                StringBuilder b = new StringBuilder(ln + 4);
                b.append(s.substring(0, i));
                while (true) {
                    if (c == '"') {
                        b.append("\\\"");
                    } else if (c == '\\') {
                        b.append("\\\\");
                    } else if (c < 0x20) {
                        if (c == '\n') {
                            b.append("\\n");
                        } else if (c == '\r') {
                            b.append("\\r");
                        } else if (c == '\f') {
                            b.append("\\f");
                        } else if (c == '\b') {
                            b.append("\\b");
                        } else if (c == '\t') {
                            b.append("\\t");
                        } else {
                            b.append("\\u00");
                            int x = c / 0x10;
                            b.append((char)
                                    (x < 0xA ? x + '0' : x - 0xA + 'a'));
                            x = c & 0xF;
                            b.append((char)
                                    (x < 0xA ? x + '0' : x - 0xA + 'a'));
                        }
                    } else {
                        b.append(c);
                    }
                    i++;
                    if (i >= ln) {
                        return b.toString();
                    }
                    c = s.charAt(i);
                }
            } // if has to be escaped
        } // for each characters
        return s;
    }

    /**
     * Escapes a <code>String</code> according the JavaScript string literal
     * escaping rules. The resulting string will not be quoted.
     *
     * <p>It escapes both <tt>'</tt> and <tt>"</tt>.
     * In additional it escapes <tt>></tt> as <tt>\></tt> (to avoid
     * <tt>&lt;/script></tt>). Furthermore, all characters under UCS code point
     * 0x20, that has no dedicated escape sequence in JavaScript language, will
     * be replaced with hexadecimal escape (<tt>\x<i>XX</i></tt>).
     */
    public static String javaScriptStringEnc(String s) {
        int ln = s.length();
        for (int i = 0; i < ln; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '\\' || c == '>' || c < 0x20) {
                StringBuilder buf = new StringBuilder(ln + 4);
                buf.append(s.substring(0, i));
                while (true) {
                    if (c == '"') {
                        buf.append("\\\"");
                    } else if (c == '\'') {
                        buf.append("\\'");
                    } else if (c == '\\') {
                        buf.append("\\\\");
                    } else if (c == '>') {
                        buf.append("\\>");
                    } else if (c < 0x20) {
                        if (c == '\n') {
                            buf.append("\\n");
                        } else if (c == '\r') {
                            buf.append("\\r");
                        } else if (c == '\f') {
                            buf.append("\\f");
                        } else if (c == '\b') {
                            buf.append("\\b");
                        } else if (c == '\t') {
                            buf.append("\\t");
                        } else {
                            buf.append("\\x");
                            int x = c / 0x10;
                            buf.append((char)
                                    (x < 0xA ? x + '0' : x - 0xA + 'A'));
                            x = c & 0xF;
                            buf.append((char)
                                    (x < 0xA ? x + '0' : x - 0xA + 'A'));
                        }
                    } else {
                        buf.append(c);
                    }
                    i++;
                    if (i >= ln) {
                        return buf.toString();
                    }
                    c = s.charAt(i);
                }
            } // if has to be escaped
        } // for each characters
        return s;
    }

}
