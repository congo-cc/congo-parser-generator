# Building and Running the SQL Expression Parser 

## Building the Rust Parser

### Generate the Rust Source Code

From the **examples/sqlexpr** directory, run the following command to build the rust SQL Expression parser:
```
java -jar ../congocc.jar -n -lang rust -d rust-sqlparser SqlExprParser.ccc
```
### Optionally Add AST Serialization Support

Make the current directory **rust-sqlparser**.  To include serde serialization for ASTs, copy the following text into the generated Cargo.toml file before the [lib] stanza (blank lines before and after).  

```
[dependencies]
serde = { version = "1.0", features = ["derive"], optional = true }

[features]
default = []
serde = ["dep:serde"]
```

### Compiling the Parser

From the **rust-sqlparser** directory, issue *ONE* of the following commands for debug builds.

```
cargo build                              # Debug build
cargo build --features serde             # Debug build with serde serialization support for AST types
cargo build --release                    # Optimized build
cargo build --release --features serde   # Optimized build with serde serialization support for AST types
```
