package org.congocc;

import java.util.*;

/**
 * Class to hold the various application settings
 * (TODO)
 */

public class AppSettings {
    private Grammar grammar;
    Map<String, Object> settings = new HashMap<>();


    AppSettings(Grammar grammar) {
        this.grammar = grammar;
    }

}