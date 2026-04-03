//! Arithmetic expression parser CLI (parse-only, no evaluation).
//!
//! Parses an arithmetic expression from command-line arguments and dumps the
//! resulting AST.  This is the Arithmetic1 variant which only parses --
//! it does not evaluate expressions.  Use arith2-calc (rust-arith2) for a
//! fully functional evaluator.
//!
//! # Usage
//!
//! ```text
//! arith1-calc <expression>
//! arith1-calc "2 + 3 * 4"       # parses and dumps the AST
//! ```

use ex1::parser::Parser;

use std::env;
use std::process;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();

    if args.is_empty() {
        eprintln!("Usage: arith1-calc <expression>");
        eprintln!();
        eprintln!("Parses an arithmetic expression and dumps the AST.");
        eprintln!("Note: This is the parse-only variant (Arithmetic1).");
        eprintln!("      Use arith2-calc (rust-arith2) for expression evaluation.");
        eprintln!();
        eprintln!("Examples:");
        eprintln!("  arith1-calc \"2 + 3 * 4\"");
        eprintln!("  arith1-calc \"(2 + 3) * 4\"");
        process::exit(1);
    }

    // Join all arguments to allow expressions like: arith1-calc 2 + 3
    let input = args.join(" ");

    // Parse the expression into an AST.
    let ast = match Parser::parse(&input, Some("cli")) {
        Ok(ast) => ast,
        Err(e) => {
            eprintln!("Parse error: {}", e);
            process::exit(1);
        }
    };

    // Dump the AST.
    if let Some(root) = ast.root() {
        println!("AST:");
        println!("{}", ast.dump(root));
    }

    // Attempt evaluation -- this always fails for Arithmetic1, mirroring the Java
    // UnsupportedOperationException behavior.
    match ast.evaluate_root() {
        Ok(value) => {
            println!("The result is: {}", value);
        }
        Err(e) => {
            eprintln!("Evaluation not supported: {}", e);
            eprintln!("Tip: Use arith2-calc (rust-arith2) for expression evaluation.");
            process::exit(1);
        }
    }
}
