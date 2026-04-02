//! Arithmetic expression evaluator CLI.
//!
//! Parses an arithmetic expression from command-line arguments, evaluates it,
//! and prints the result.  Supports `+`, `-`, `*`, `/`, parentheses, and
//! decimal numbers.  Operator precedence follows standard math rules:
//! multiplication and division bind tighter than addition and subtraction.
//!
//! # Usage
//!
//! ```text
//! arith2-calc <expression>
//! arith2-calc "2 + 3 * 4"       # prints: 14
//! arith2-calc "(2 + 3) * 4"     # prints: 20
//! arith2-calc "3.14 * 2.0"      # prints: 6.28
//! ```

use ex2::parser::Parser;

use std::env;
use std::process;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();

    if args.is_empty() {
        eprintln!("Usage: arith2-calc <expression>");
        eprintln!();
        eprintln!("Evaluates an arithmetic expression and prints the result.");
        eprintln!("Supports: +, -, *, /, parentheses, decimal numbers.");
        eprintln!();
        eprintln!("Examples:");
        eprintln!("  arith2-calc \"2 + 3 * 4\"");
        eprintln!("  arith2-calc \"(2 + 3) * 4\"");
        process::exit(1);
    }

    // Join all arguments to allow expressions like: arith2-calc 2 + 3
    let input = args.join(" ");

    // Parse the expression into an AST.
    let ast = match Parser::parse(&input, Some("cli")) {
        Ok(ast) => ast,
        Err(e) => {
            eprintln!("Parse error: {}", e);
            process::exit(1);
        }
    };

    // Dump the AST for informational output.
    if let Some(root) = ast.root() {
        eprintln!("AST:");
        eprintln!("{}", ast.dump(root));
    }

    // Evaluate the expression and print the result.
    match ast.evaluate_root() {
        Ok(value) => {
            // Print integer-valued results without a decimal point for cleanliness.
            if value.fract() == 0.0 && value.abs() < 1e15 {
                println!("{}", value as i64);
            } else {
                println!("{}", value);
            }
        }
        Err(e) => {
            eprintln!("Evaluation error: {}", e);
            process::exit(1);
        }
    }
}
