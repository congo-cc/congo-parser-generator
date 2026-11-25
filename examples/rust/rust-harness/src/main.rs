//! Command line of the harness for the Rust parser generated from Rust.ccc.
//!
//! ```text
//! rust-harness dump <output directory> <file or directory>...
//!     Writes the Rust parser's tree of each .rs file to <output directory>/<name>.tree.
//! rust-harness compare <Java dump directory> <file or directory>...
//!     Compares the Rust parser's tree of each .rs file with the Java parser's
//!     (written by `java TreeDump -o <Java dump directory> ...`); exits with
//!     status 1 if a tree differs or a Java dump is missing.
//! rust-harness bench <rounds> <file or directory>...
//!     Parses all the files <rounds> times to warm up, then <rounds> more
//!     times, and prints the median and fastest time of a round, in the same
//!     format as `java TreeDump -bench`.
//! ```
//!
//! The parsing runs on a thread with a large stack, so that the depth of the
//! recursive descent is limited by the input rather than by the default
//! stack size of the main thread.

use std::path::Path;
use std::process::ExitCode;
use std::time::Instant;

use rust_harness::{Input, collect_inputs, dump, excerpt, first_difference, read_source};

const USAGE: &str = "Usage: rust-harness dump <output directory> <file or directory>...
       rust-harness compare <Java dump directory> <file or directory>...
       rust-harness bench <rounds> <file or directory>...";

/// The stack size of the parsing thread.
const STACK_SIZE: usize = 512 * 1024 * 1024;

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
    if args.len() < 3 || !matches!(args[0].as_str(), "dump" | "compare" | "bench") {
        eprintln!("{USAGE}");
        return ExitCode::from(2);
    }
    let mut inputs = Vec::new();
    for arg in &args[2..] {
        match collect_inputs(Path::new(arg)) {
            Ok(found) => inputs.extend(found),
            Err(e) => {
                eprintln!("rust-harness: {e}");
                return ExitCode::from(2);
            }
        }
    }
    if inputs.is_empty() {
        eprintln!("rust-harness: no .rs files found in {}; give the files or directories to parse.", args[2..].join(" "));
        return ExitCode::from(2);
    }
    match args[0].as_str() {
        "dump" => write_dumps(Path::new(&args[1]), &inputs),
        "compare" => compare(Path::new(&args[1]), &inputs),
        _ => match args[1].parse::<usize>() {
            Ok(rounds) if rounds > 0 => benchmark(&inputs, rounds),
            _ => {
                eprintln!("rust-harness: the number of rounds must be a positive integer, not '{}'.\n{USAGE}", args[1]);
                ExitCode::from(2)
            }
        },
    }
}

fn write_dumps(output_directory: &Path, inputs: &[Input]) -> ExitCode {
    let mut failures = 0;
    for input in inputs {
        let target = output_directory.join(format!("{}.tree", input.name));
        let result = read_source(&input.path).and_then(|text| {
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(&target, dump(&input.name, &text))
        });
        if let Err(e) = result {
            eprintln!(
                "rust-harness: cannot dump {} to {}: {e}; check that the file is readable and the output directory is writable.",
                input.path.display(),
                target.display()
            );
            failures += 1;
        }
    }
    println!("Wrote {} tree dumps to {}", inputs.len() - failures, output_directory.display());
    if failures == 0 { ExitCode::SUCCESS } else { ExitCode::FAILURE }
}

fn compare(java_directory: &Path, inputs: &[Input]) -> ExitCode {
    let mut identical = 0;
    let mut problems = Vec::new();
    for input in inputs {
        let java_dump = java_directory.join(format!("{}.tree", input.name));
        let java = match std::fs::read_to_string(&java_dump) {
            Ok(java) => java,
            Err(e) => {
                problems.push(format!(
                    "{}: cannot read the Java parser's tree {}: {e}; run `java TreeDump -o {} ...` first.",
                    input.name,
                    java_dump.display(),
                    java_directory.display()
                ));
                continue;
            }
        };
        let text = match read_source(&input.path) {
            Ok(text) => text,
            Err(e) => {
                problems.push(format!("{}: cannot read {}: {e}", input.name, input.path.display()));
                continue;
            }
        };
        let rust = dump(&input.name, &text);
        match first_difference(&java, &rust) {
            None => identical += 1,
            Some(difference) => {
                let mut message = format!(
                    "{}: the trees differ at line {} of the dumps\n    Java: {}\n    Rust: {}",
                    input.name, difference.line, difference.java, difference.rust
                );
                // Show the input at the offsets of the Java parser's node.
                let fields: Vec<&str> = difference.java.split(' ').collect();
                if fields.len() >= 4 {
                    if let (Ok(begin), Ok(end)) =
                        (fields[fields.len() - 2].parse::<usize>(), fields[fields.len() - 1].parse::<usize>())
                    {
                        let shown = excerpt(&text, begin, end.min(begin + 120));
                        message.push_str(&format!("\n    Input at {begin}..{end}: {shown:?}"));
                    }
                }
                problems.push(message);
            }
        }
    }
    for problem in &problems {
        println!("{problem}");
    }
    println!("{identical} of {} files have identical trees", inputs.len());
    if problems.is_empty() { ExitCode::SUCCESS } else { ExitCode::FAILURE }
}

fn benchmark(inputs: &[Input], rounds: usize) -> ExitCode {
    let mut texts = Vec::new();
    let mut bytes = 0usize;
    for input in inputs {
        match std::fs::read(&input.path) {
            Ok(data) => {
                bytes += data.len();
                texts.push(org_parsers_rust::lexer::string_from_bytes(&data));
            }
            Err(e) => {
                eprintln!("rust-harness: cannot read {}: {e}", input.path.display());
                return ExitCode::from(2);
            }
        }
    }
    let mut times = Vec::with_capacity(rounds);
    let mut failures = 0usize;
    for round in 0..2 * rounds {
        let start = Instant::now();
        for text in &texts {
            if org_parsers_rust::Parser::parse(text, Some("input")).is_err() {
                failures += 1;
            }
        }
        let elapsed = start.elapsed();
        if round >= rounds {
            times.push(elapsed);
        }
    }
    times.sort();
    let median = times[rounds / 2].as_secs_f64() * 1e3;
    let fastest = times[0].as_secs_f64() * 1e3;
    println!(
        "rust: {} files, {bytes} bytes, {rounds} rounds after {rounds} warm-up rounds: median {median:.2} ms, fastest {fastest:.2} ms per round ({:.1} MB/s)",
        texts.len(),
        bytes as f64 / 1e6 / (median / 1e3)
    );
    if failures > 0 {
        println!("rust: {} files failed to parse", failures / (2 * rounds));
    }
    ExitCode::SUCCESS
}
