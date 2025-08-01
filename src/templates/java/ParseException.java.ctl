/* Generated by: ${generated_by}. ${filename} ${settings.copyrightBlurb} */

package ${settings.parserPackage};

import java.io.PrintStream;
import java.io.PrintWriter;

#var BASE_EXCEPTION_TYPE = settings.useCheckedException ?: "Exception" : "RuntimeException"
#var LOCATION_TYPE = settings.baseTokenClassName

#var TOKEN_TYPE_SET = "EnumSet<TokenType>",
                      BaseToken = settings.baseTokenClassName,
                      BaseTokenType = "TokenType"

#if settings.treeBuildingEnabled || settings.rootAPIPackage??
  #set TOKEN_TYPE_SET = "Set<? extends Node.NodeType>"
  #set BaseToken = "Node.TerminalNode"
  #set BaseTokenType = "Node.NodeType"
  #set LOCATION_TYPE = "Node"
#else
  import ${settings.parserPackage}.${BaseToken}.TokenType;
#endif

import java.util.*;

public class ParseException extends ${BASE_EXCEPTION_TYPE} {

  // The location where we tripped up.
  private ${LOCATION_TYPE} location;
  //We were expecting one of these token types
  private ${TOKEN_TYPE_SET} expectedTypes;

  private List<NonTerminalCall> callStack = new ArrayList<>();

  public ParseException(${LOCATION_TYPE} location, ${TOKEN_TYPE_SET} expectedTypes, List<NonTerminalCall> callStack) {
      this.location = location;
      this.expectedTypes = expectedTypes;
      if (callStack != null) {
         this.callStack = new ArrayList<>(callStack);
      }
  }

  public ParseException(${LOCATION_TYPE} location) {
     this.location = location;
  }

  public ParseException(String message) {
      super(message);
  }

  public ParseException() {
      super();
  }

  public ParseException(String message, List<NonTerminalCall> callStack) {
    super(message);
    if (callStack != null) {
        this.callStack = new ArrayList<>(callStack);
    }
  }

  public ParseException(String message, ${LOCATION_TYPE} location, List<NonTerminalCall> callStack) {
     super(message);
     this.location = location;
     if (callStack != null) {
        this.callStack = new ArrayList<>(callStack);
     }
  }

  public void printStackTrace(PrintStream ps) {
      ps.println(getMessage());
      ps.print(getCustomStackTrace());
    #-- printJavaStackTrace(ps);
  }

  public void printStackTrace(PrintWriter pw) {
      pw.println(getMessage());
      pw.print(getCustomStackTrace());
     #-- printJavaStackTrace(pw);
  }

  public void printJavaStackTrace(PrintStream ps) {
     super.printStackTrace(ps);
  }

  public void printJavaStackTrace(PrintWriter pw) {
     super.printStackTrace(pw);
  }


  public boolean hitEOF() {
      return location != null && location.getType() != null && location.getType().isEOF();
  }

  private boolean javaLocationsFilledIn;

  void fillInJavaCodeLocations() {
     if (javaLocationsFilledIn) return;
     List<StackTraceElement> stackTrace = new ArrayList<>();
     for (StackTraceElement elem : getStackTrace()) {
        stackTrace.add(elem);
     }
     List<NonTerminalCall> callStack = new ArrayList<>(this.callStack);
     Collections.reverse(callStack);
     while (!callStack.isEmpty()) {
         NonTerminalCall ntc = callStack.remove(callStack.size()-1);
         while (!stackTrace.isEmpty()) {
             StackTraceElement elem = stackTrace.remove(stackTrace.size()-1);
             if (elem.getMethodName().equals(ntc.productionName) && elem.getClassName().endsWith("." + ntc.parserClassName)) {
                 ntc.javaSourceLine = elem.getLineNumber();
                 break;
             }
         }
     }
     javaLocationsFilledIn = true;
  }

  @Override
  public String getMessage() {
     StringBuilder buf = new StringBuilder();
     buf.append("Encountered an error");
     if (location != null) {
        buf.append(" at ");
        buf.append(location.getLocation());
     }
     if (super.getMessage() != null) {
        buf.append("\n");
        buf.append(super.getMessage());
     }
     if (hitEOF()) {
        buf.append("\nUnexpected end of input.");
     }
     if (location == null || location.getType() == null || expectedTypes == null || expectedTypes.contains(location.getType())) {
        return buf.toString();
     }
     String content = location.toString();
     if (content == null || content.length() == 0) {
        buf.append("\n Found token of type " + location.getType());
     }
     else {
       if (content.length() > 32) content = content.substring(0, 32) + "...";
       buf.append("\nFound string \"" + addEscapes(content) + "\" of type " + location.getType());
     }
     if (expectedTypes.size() == 1) {
        buf.append("\nWas expecting: " + expectedTypes.iterator().next());
     }
     else {
        buf.append("\nWas expecting one of the following:\n");
        boolean isFirst = true;
        for (${BaseTokenType} type : expectedTypes) {
           if (!isFirst) buf.append(", ");
           isFirst = false;
           buf.append(type);
        }
     }
     return buf.toString();
  }

  // TODO: Make this configurable
  public String getCustomStackTrace() {
     fillInJavaCodeLocations();
     StringBuilder buf = new StringBuilder();
     for (int i = callStack.size() - 1; i>=0; i--) {
        buf.append("        ");
        buf.append(callStack.get(i));
     }
     return buf.toString();
  }

  /**
   * Returns the token which causes the parse error and null otherwise.
   * @return the token which causes the parse error and null otherwise.
   */
   public ${LOCATION_TYPE} getLocation() {
      return location;
   }

   /**
    * @Deprecated Use #getLocation
    * We just keep this around in case anybody using it.
    */
   public ${LOCATION_TYPE} getToken() {
      return location;
   }

   private static String addEscapes(String str) {
      StringBuilder retval = new StringBuilder();
      for (int ch : str.codePoints().toArray()) {
        switch (ch) {
           case '\b':
              retval.append("\\b");
              continue;
           case '\t':
              retval.append("\\t");
              continue;
           case '\n':
              retval.append("\\n");
              continue;
           case '\f':
              retval.append("\\f");
              continue;
           case '\r':
              retval.append("\\r");
              continue;
           case '\"':
              retval.append("\\\"");
              continue;
           case '\'':
              retval.append("\\\'");
              continue;
           case '\\':
              retval.append("\\\\");
              continue;
           default:
              if (Character.isISOControl(ch)) {
                 String s = "0000" + java.lang.Integer.toString(ch, 16);
                 retval.append("\\u" + s.substring(s.length() - 4));
              } else {
                 retval.appendCodePoint(ch);
              }
              continue;
        }
      }
      return retval.toString();
  }
}
