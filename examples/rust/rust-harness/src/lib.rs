//! Test and benchmark harness for the Rust parser that CongoCC generates from
//! `examples/rust/Rust.ccc`.
//!
//! The harness checks that the generated Rust parser builds the same syntax
//! trees as the generated Java parser for the same input.  Both parsers'
//! trees are written in the same canonical text format: the Java parser's by
//! `TreeDump.java` (next to the grammar), the Rust parser's by [`dump`].
//! The format is described in `TreeDump.java`: a header line, a line that
//! identifies the text the parser saw, then one line per node in depth-first
//! order with code point offsets, or a single `error <line>:<column>` line
//! when the input does not parse.
//!
//! # Example
//!
//! ```no_run
//! use std::path::Path;
//!
//! let text = rust_harness::read_source(Path::new("testfiles/ast.rs")).expect("cannot read the file");
//! let tree = rust_harness::dump("ast.rs", &text);
//! assert!(tree.starts_with(rust_harness::FORMAT_HEADER));
//! ```

use std::fmt::Write as _;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use org_parsers_rust::ast::{Ast, NodeKind};
use org_parsers_rust::lexer::{Lexer, string_from_bytes};
use org_parsers_rust::parser::Parser;

/// The first line of every dump (the same as `TreeDump.FORMAT_HEADER`).
pub const FORMAT_HEADER: &str = "congocc-tree 1";

/// A file to parse and the name of its dump.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Input {
    /// Where the file is.
    pub path: PathBuf,
    /// The path of the file relative to the directory it was found in (with
    /// `/` separators), or its file name; the dump is `<name>.tree`.
    pub name: String,
}

/// Collects the `.rs` files of `path`: the file itself, or all the `.rs`
/// files under the directory, recursively, sorted by name.
///
/// # Errors
///
/// Fails if `path` does not exist or a directory cannot be read.
pub fn collect_inputs(path: &Path) -> io::Result<Vec<Input>> {
    let metadata = fs::metadata(path).map_err(|e| {
        io::Error::new(e.kind(), format!("{}: {} (check the command line)", path.display(), e))
    })?;
    if !metadata.is_dir() {
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        return Ok(vec![Input { path: path.to_path_buf(), name }]);
    }
    let mut files = Vec::new();
    walk(path, &mut files)?;
    files.sort();
    Ok(files
        .into_iter()
        .map(|file| {
            let name = file.strip_prefix(path).unwrap_or(&file).to_string_lossy().replace('\\', "/");
            Input { path: file, name }
        })
        .collect())
}

fn walk(directory: &Path, files: &mut Vec<PathBuf>) -> io::Result<()> {
    for entry in fs::read_dir(directory)? {
        let path = entry?.path();
        if path.is_dir() {
            walk(&path, files)?;
        } else if path.extension().is_some_and(|e| e == "rs") {
            files.push(path);
        }
    }
    Ok(())
}

/// Reads a source file the way the Java parser's `new Parser(Path)` does:
/// the bytes are decoded by the generated lexer's [`string_from_bytes`],
/// which handles byte order marks and invalid UTF-8 as Java's
/// `TokenSource.stringFromBytes` does.
///
/// # Errors
///
/// Fails if the file cannot be read.
pub fn read_source(path: &Path) -> io::Result<String> {
    Ok(string_from_bytes(&fs::read(path)?))
}

/// The 64-bit FNV-1a hash of `bytes` (the same as `TreeDump.fnv1a64`).
pub fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// Returns, for each byte offset in `text` (and its length), the number of
/// code points before it; the bytes inside a multi-byte character map to
/// that character's index.
pub fn code_point_index(text: &str) -> Vec<u32> {
    let mut index = vec![0u32; text.len() + 1];
    let mut code_points = 0u32;
    for (offset, ch) in text.char_indices() {
        for slot in &mut index[offset..offset + ch.len_utf8()] {
            *slot = code_points;
        }
        code_points += 1;
    }
    index[text.len()] = code_points;
    index
}

/// Parses `text` with the generated Rust parser and returns its dump.
///
/// A panic of the parser is caught and reported as a `crash` line (the
/// panic message is also printed to standard error by the panic hook).
pub fn dump(name: &str, text: &str) -> String {
    // The text the parser sees: the input after the lexer's rewriting.
    let content = Lexer::new(text, name).source().to_string();
    let mut out = String::with_capacity(content.len() * 2);
    out.push_str(FORMAT_HEADER);
    out.push('\n');
    let _ = writeln!(out, "content {} {:016x}", content.chars().count(), fnv1a64(content.as_bytes()));
    match std::panic::catch_unwind(|| Parser::parse(text, Some(name))) {
        Ok(Ok(ast)) => {
            if ast.source() != content {
                let _ = writeln!(out, "crash the tree's source text differs from the lexer's");
            } else {
                dump_nodes(&ast, &mut out);
            }
        }
        Ok(Err(error)) => {
            let _ = writeln!(out, "error {}:{}", error.line, error.column);
        }
        Err(panic) => {
            let message = panic
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| panic.downcast_ref::<&str>().copied())
                .unwrap_or("a panic without a message");
            let _ = writeln!(out, "crash {}", message.lines().next().unwrap_or(""));
        }
    }
    out
}

/// Appends the node lines of the dump of `ast`.
fn dump_nodes(ast: &Ast, out: &mut String) {
    let Some(root) = ast.root() else {
        out.push_str("crash the parse succeeded without building a tree\n");
        return;
    };
    let index = code_point_index(ast.source());
    // An explicit stack instead of recursion, as in TreeDump.java.
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

/// The first difference between two dumps.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Difference {
    /// The 1-based line number of the first differing line.
    pub line: usize,
    /// The Java parser's line (empty if its dump is shorter).
    pub java: String,
    /// The Rust parser's line (empty if its dump is shorter).
    pub rust: String,
}

/// Compares two dumps line by line.
///
/// # Example
///
/// ```
/// let difference = rust_harness::first_difference("a\nb\n", "a\nc\n").unwrap();
/// assert_eq!((difference.line, difference.java.as_str(), difference.rust.as_str()), (2, "b", "c"));
/// ```
pub fn first_difference(java: &str, rust: &str) -> Option<Difference> {
    let mut java_lines = java.lines();
    let mut rust_lines = rust.lines();
    let mut line = 0;
    loop {
        line += 1;
        match (java_lines.next(), rust_lines.next()) {
            (None, None) => return None,
            (j, r) if j == r => continue,
            (j, r) => {
                return Some(Difference {
                    line,
                    java: j.unwrap_or("").to_string(),
                    rust: r.unwrap_or("").to_string(),
                });
            }
        }
    }
}

/// Returns the text between two code point offsets of `text` (clamped to
/// its length), for showing the input where two dumps differ.
pub fn excerpt(text: &str, begin: usize, end: usize) -> String {
    text.chars().skip(begin).take(end.saturating_sub(begin)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fnv1a64_matches_the_reference_values() {
        // Reference values of the FNV-1a 64-bit hash.
        assert_eq!(fnv1a64(b""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(fnv1a64(b"a"), 0xaf63_dc4c_8601_ec8c);
        assert_eq!(fnv1a64(b"foobar"), 0x8594_4171_f739_67e8);
    }

    #[test]
    fn code_point_index_counts_code_points_not_bytes() {
        // a (1 byte), é (2 bytes), 😀 (4 bytes), b (1 byte)
        let index = code_point_index("aé😀b");
        assert_eq!(index, vec![0, 1, 1, 2, 2, 2, 2, 3, 4]);
        assert_eq!(code_point_index(""), vec![0]);
    }

    // The expected dumps below were written by the Java parser (java
    // TreeDump -o ...) for the same inputs, so these tests check the Rust
    // parser against the Java parser on small inputs that exercise the
    // format: multi-byte characters, a syntax error and an empty input.

    #[test]
    fn dump_of_a_valid_input_matches_the_java_parser() {
        let expected = "\
congocc-tree 1
content 10 953df15980bffeae
0 Crate 0 10
1 ItemWithAttributes 0 9
2 Function 0 9
3 Token FN 0 2
3 Token ID 3 4
3 FunctionParameters 4 6
4 Token LPAREN 4 5
4 Token RPAREN 5 6
3 BlockExpression 7 9
4 Token LBRACE 7 8
4 Token RBRACE 8 9
1 Token EOF 10 10
";
        // é is two bytes in UTF-8 but one code point: the identifier is 3..4.
        assert_eq!(dump("accent.rs", "fn é() {}\n"), expected);
    }

    #[test]
    fn dump_of_an_invalid_input_reports_the_error_location_like_the_java_parser() {
        let expected = "congocc-tree 1\ncontent 12 a8024d5ebfc149cc\nerror 1:10\n";
        assert_eq!(dump("bad.rs", "fn main( {}\n"), expected);
    }

    #[test]
    fn dump_of_an_empty_input_matches_the_java_parser() {
        // ENSURE_FINAL_EOL makes the content "\n"; the only node is the EOF
        // token, because smart node creation leaves out the Crate node, which
        // would have a single child.
        let expected = "congocc-tree 1\ncontent 1 af63c74c8601c8dd\n0 Token EOF 1 1\n";
        assert_eq!(dump("empty.rs", ""), expected);
        assert_eq!(format!("{:016x}", fnv1a64(b"\n")), "af63c74c8601c8dd");
    }

    #[test]
    fn first_difference_finds_changed_missing_and_extra_lines() {
        assert_eq!(first_difference("a\nb\n", "a\nb\n"), None);
        assert_eq!(
            first_difference("a\nb\n", "a\n"),
            Some(Difference { line: 2, java: "b".into(), rust: String::new() })
        );
        assert_eq!(
            first_difference("a\n", "a\nc\n"),
            Some(Difference { line: 2, java: String::new(), rust: "c".into() })
        );
        assert_eq!(
            first_difference("x\n", "y\n"),
            Some(Difference { line: 1, java: "x".into(), rust: "y".into() })
        );
    }

    #[test]
    fn excerpt_uses_code_point_offsets_and_clamps() {
        assert_eq!(excerpt("aé😀b", 1, 3), "é😀");
        assert_eq!(excerpt("abc", 2, 10), "c");
        assert_eq!(excerpt("abc", 3, 1), "");
    }

    #[test]
    fn collect_inputs_finds_rs_files_recursively_with_relative_names() {
        let root = std::env::temp_dir().join(format!("rust-harness-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("sub")).unwrap();
        fs::write(root.join("b.rs"), "").unwrap();
        fs::write(root.join("sub/a.rs"), "").unwrap();
        fs::write(root.join("notes.txt"), "").unwrap();
        let names: Vec<String> = collect_inputs(&root).unwrap().into_iter().map(|i| i.name).collect();
        assert_eq!(names, vec!["b.rs".to_string(), "sub/a.rs".to_string()]);
        let single = collect_inputs(&root.join("b.rs")).unwrap();
        assert_eq!(single, vec![Input { path: root.join("b.rs"), name: "b.rs".into() }]);
        assert!(collect_inputs(&root.join("missing")).is_err());
        fs::remove_dir_all(&root).unwrap();
    }
}
