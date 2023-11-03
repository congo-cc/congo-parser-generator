package org.congocc.app;

import java.util.*;
import org.congocc.parser.Node;

public class Errors {
	private int parseErrorCount;
    private int warningCount;

    private final List<String> errorMessages = new ArrayList<>();
    private final List<String> warningMessages = new ArrayList<>();
    private final List<String> infoMessages = new ArrayList<>();


	/**
	 * @return the warning count during grammar parsing.
	 */
	public int getWarningCount() {
		return warningCount;
	}

	/**
	 * @return the parse error count during grammar parsing.
	 */
	public int getParseErrorCount() {
		return parseErrorCount;
	}

    public void addError(String errorMessage) {
        errorMessages.add(errorMessage);
    }

    public void addError(Node location, String errorMessage) {
        String locationString = location == null ? "" : location.getLocation();
        errorMessages.add("Error: " + locationString + ":" + errorMessage);
    }

    public void addWarning(String warningMessage) {
        warningMessages.add(warningMessage);
    }

    public void addWarning(Node location, String warningMessage) {
        String locationString = location == null ? "" : location.getLocation();
        warningMessages.add("Warning: " + locationString + ":" + warningMessage);
    }

    public void addInfo(String infoMessage) {
        infoMessages.add(infoMessage);
    }

    public void addInfo(Node location, String infoMessage) {
        String locationString = location == null ? "" : location.getLocation();
        infoMessages.add("Info: " + locationString + ":" + infoMessage);
    }

	/**
	 * @return the total error count during grammar parsing.
	 */
	public int getErrorCount() {
        return errorMessages.size();
	}

    public List<String> getInfoMessages() {return infoMessages;}
    public List<String> getWarningMessages() {return warningMessages;}
    public List<String> getErrorMessages() {return errorMessages;}
}