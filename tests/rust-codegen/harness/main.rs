//! The feature harness of the Rust code generation tests (tests/rust-codegen).
//!
//! RustCodegenTest copies this file into a crate whose `Cargo.toml` has one
//! dependency per feature grammar (the crate generated from
//! `tests/rust-codegen/features/<name>.ccc`), and writes `src/features.rs`,
//! which instantiates [`feature_dumper!`] for each of them and defines
//! `fn dumper(feature: &str) -> Option<&'static Dumper>`.
//!
//! ```text
//! rcg-feature-harness <feature> <output directory> <input file>...
//! ```
//!
//! parses every input with the Rust parser of the feature and writes its tree
//! to `<output directory>/<input file name>.tree`, in the format of
//! `examples/rust/TreeDump.java`, which RustCodegenTest also writes for the
//! Java parser generated from the same grammar:
//!
//! ```text
//! congocc-tree 1
//! content <number of code points> <FNV-1a 64-bit hash of the UTF-8 bytes>
//! <depth> <node name> <begin> <end>          (a node, depth first, the root at depth 0)
//! <depth> Token <token type> <begin> <end>   (a token)
//! ```
//!
//! The offsets count code points, so that they compare with the Java
//! parser's UTF-16 offsets.  When the parse fails, the node lines are replaced
//! by `error <line>:<column>` (the location that the parser reports) or
//! `crash <message>` (a panic).

use std::fmt::Write as _;
use std::path::Path;
use std::process::ExitCode;

/// The first line of every dump; the same as in TreeDump.java.
const FORMAT_HEADER: &str = "congocc-tree 1";

/// The parse functions of a feature's crate.
pub struct Dumper {
    /// Decodes a file the way the generated lexer does (`string_from_bytes`).
    pub read: fn(&[u8]) -> String,
    /// Parses a text and returns its dump.
    pub dump: fn(&str, &str) -> String,
}

/// The 64-bit FNV-1a hash of `bytes` (the same as in TreeDump.java).
pub fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// For each byte offset of `text` (and its length), the number of code
/// points before it.
pub fn code_point_index(text: &str) -> Vec<usize> {
    let mut index = vec![0; text.len() + 1];
    let mut code_points = 0;
    for (offset, c) in text.char_indices() {
        for slot in &mut index[offset..offset + c.len_utf8()] {
            *slot = code_points;
        }
        code_points += 1;
    }
    index[text.len()] = code_points;
    index
}

/// The message of a panic, for the `crash` line of a dump.
pub fn panic_message(panic: &(dyn std::any::Any + Send)) -> String {
    panic
        .downcast_ref::<String>()
        .map(String::as_str)
        .or_else(|| panic.downcast_ref::<&str>().copied())
        .unwrap_or("a panic without a message")
        .lines()
        .next()
        .unwrap_or("")
        .to_string()
}

/// Defines the module `$module` with the dump functions of the crate `$krate`
/// and a `DUMPER` constant that refers to them.
macro_rules! feature_dumper {
    ($module:ident, $krate:ident) => {
        mod $module {
            use super::*;
            use $krate::lexer::Lexer;
            use $krate::{Ast, NodeKind, Parser};

            fn read(bytes: &[u8]) -> String {
                $krate::lexer::string_from_bytes(bytes)
            }

            fn dump(name: &str, text: &str) -> String {
                // The text the parser sees: the input after the lexer's rewriting.
                let content = Lexer::new(text, name).source().to_string();
                let mut out = String::new();
                let _ = writeln!(out, "{FORMAT_HEADER}");
                let _ = writeln!(out, "content {} {:016x}", content.chars().count(), fnv1a64(content.as_bytes()));
                match std::panic::catch_unwind(|| Parser::parse(text, Some(name))) {
                    Ok(Ok(ast)) => {
                        if ast.source() != content {
                            out.push_str("crash the tree's source text differs from the lexer's\n");
                        } else {
                            dump_nodes(&ast, &mut out);
                        }
                    }
                    Ok(Err(error)) => {
                        let _ = writeln!(out, "error {}:{}", error.line, error.column);
                    }
                    Err(panic) => {
                        let _ = writeln!(out, "crash {}", panic_message(panic.as_ref()));
                    }
                }
                out
            }

            fn dump_nodes(ast: &Ast, out: &mut String) {
                let Some(root) = ast.root() else {
                    out.push_str("crash the parse succeeded without building a tree\n");
                    return;
                };
                let index = code_point_index(ast.source());
                // An explicit stack, as in TreeDump.java.
                let mut stack = vec![(root, 0usize)];
                let mut children = Vec::new();
                while let Some((id, depth)) = stack.pop() {
                    let node = ast.node(id);
                    let (begin, end) = (index[node.begin_offset as usize], index[node.end_offset as usize]);
                    match ast.kind(id) {
                        NodeKind::Token(_) => {
                            let kind = ast.token_type(id).expect("a token node has a token type");
                            let _ = writeln!(out, "{depth} Token {kind:?} {begin} {end}");
                        }
                        kind => {
                            let _ = writeln!(out, "{depth} {} {begin} {end}", kind.name());
                        }
                    }
                    children.clear();
                    children.extend(ast.children(id));
                    stack.extend(children.iter().rev().map(|&child| (child, depth + 1)));
                }
            }

            pub const DUMPER: Dumper = Dumper { read, dump };
        }
    };
}

include!("features.rs");

/// The stack size of the parsing thread: deep inputs must not overflow it.
const STACK_SIZE: usize = 64 * 1024 * 1024;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    std::thread::Builder::new()
        .name("parser".into())
        .stack_size(STACK_SIZE)
        .spawn(move || run(&args))
        .expect("cannot start the parsing thread")
        .join()
        .unwrap_or(ExitCode::from(3))
}

fn run(args: &[String]) -> ExitCode {
    if args.len() < 3 {
        eprintln!("Usage: rcg-feature-harness <feature> <output directory> <input file>...");
        return ExitCode::from(2);
    }
    let Some(dumper) = dumper(&args[0]) else {
        eprintln!("rcg-feature-harness: no feature '{}'; RustCodegenTest writes the list in src/features.rs.", args[0]);
        return ExitCode::from(2);
    };
    // A panic is part of the dump ("crash ..."); do not print it as well.
    std::panic::set_hook(Box::new(|_| {}));
    let output = Path::new(&args[1]);
    for input in &args[2..] {
        let path = Path::new(input);
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| input.clone());
        let bytes = match std::fs::read(path) {
            Ok(bytes) => bytes,
            Err(e) => {
                eprintln!("rcg-feature-harness: cannot read {input}: {e}");
                return ExitCode::FAILURE;
            }
        };
        let text = (dumper.read)(&bytes);
        let target = output.join(format!("{name}.tree"));
        if let Err(e) = std::fs::write(&target, (dumper.dump)(&name, &text)) {
            eprintln!("rcg-feature-harness: cannot write {}: {e}; check that the directory exists and is writable.", target.display());
            return ExitCode::FAILURE;
        }
    }
    ExitCode::SUCCESS
}
