// Phase 1: comments inside macro_rules bodies (unparsed tokens in stream)
macro_rules! with_comments {
    () => {
        // line comment inside macro body
        /* block comment */
        42
    };
}

const ANSWER: i32 = with_comments!();
