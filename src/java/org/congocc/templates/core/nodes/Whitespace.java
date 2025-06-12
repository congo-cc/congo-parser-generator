package org.congocc.templates.core.nodes;

import org.congocc.templates.core.parser.*;
import org.congocc.templates.core.nodes.generated.Interpolation;
import org.congocc.templates.core.nodes.generated.Macro;
import org.congocc.templates.core.nodes.generated.Text;
import static org.congocc.templates.core.parser.Token.TokenType.*;


public class Whitespace extends Text {

    private Boolean ignored;

    public Whitespace(TokenType type, CTLLexer tokenSource, int beginOffset, int endOffset) {
        super(type, tokenSource, beginOffset, endOffset);
    }

    public boolean isIgnored() {
        if (ignored == null) {
            ignored = isNonOutputtingLine() 
               || getType() == TRAILING_WHITESPACE && checkForExplicitRightTrim() 
               || getType() == NON_TRAILING_WHITESPACE && getBeginColumn() == 1 && checkForExplicitLeftTrim();
        }
        return ignored;
    }

    public void setIgnored(boolean b) {
        ignored = b;
    }

    private boolean checkForExplicitLeftTrim() {
        Token tok = nextCachedToken();
        while (tok != null && tok.getBeginLine() == this.getBeginLine()) {
            if (tok.getType() == TRIM || tok.getType() == LTRIM) {
                return true;
            }
            tok = tok.nextCachedToken();
        }
        return false;
    }

    private boolean checkForExplicitRightTrim() {
        Token tok = previousCachedToken();
        while (tok != null && tok.getBeginLine() == this.getBeginLine()) {
            if (tok.getType() == TRIM || tok.getType() == RTRIM) {
                return true;
            }
            tok = tok.previousCachedToken();
        }
        return false;
    }

    private boolean isNonOutputtingLine() {
        if (spansLine()) return false;
        Token tok = previousCachedToken();
        while (tok != null && tok.getEndLine() == getBeginLine()) {
            if (tok.firstAncestorOfType(Macro.class) != this.firstAncestorOfType(Macro.class)) {
                tok = tok.previousCachedToken();
                continue;
            }
            if (tok.getType() == CLOSE_BRACE && tok.getParent() instanceof Interpolation) {
                return false;
            }
            if (tok.getType() == REGULAR_PRINTABLE || tok.getType() == PROBLEMATIC_CHAR || tok.getType() == NOPARSE) {
                return false;
            }
            tok = tok.previousCachedToken();
        }
        tok = nextCachedToken();
        while (tok != null && tok.getBeginLine() == getBeginLine()) {
            if (tok.firstAncestorOfType(Macro.class) != this.firstAncestorOfType(Macro.class)) {
                tok = tok.nextCachedToken();
                continue;
            }
            if (tok.getType() == OUTPUT_ESCAPE) {
                return false;
            }
            if (tok.getType() == REGULAR_PRINTABLE || tok.getType() == PROBLEMATIC_CHAR || tok.getType() == NOPARSE) {
                return false;
            }
            tok = tok.nextCachedToken();
        }
        return true;
    }

    private boolean spansLine() {
        return getBeginColumn() == 1 && charAt(length() - 1) == '\n';
    }
}
