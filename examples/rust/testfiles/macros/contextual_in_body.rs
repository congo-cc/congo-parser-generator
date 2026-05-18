// Phase 2: contextual spellings inside a macro body (usually lexed as Ident; AnyToken also accepts UNION/RAW/MACRO_RULES/BREAK)
macro_rules! swallow_keywords {
    () => {
        union;
        raw;
        macro_rules;
        break;
        macro_rules! nested { () => {} }
    };
}
