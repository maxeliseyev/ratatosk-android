//! Records the version of `ratatosk-core` this bridge was built against, so the
//! app can tell whether its `.so` is older than the core checkout it ships
//! with (`RatatoskCore.nativeVersion`). The core is a path dependency, so the
//! version is read from its manifest at build time rather than from a crate
//! constant (the core does not export one).

use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let core = manifest_dir().join("../../ratatosk/crates/core/Cargo.toml");
    println!("cargo:rerun-if-changed={}", core.display());
    println!(
        "cargo:rustc-env=RATATOSK_CORE_VERSION={}",
        core_version(&core).unwrap_or_else(|| "unknown".to_string())
    );
}

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn core_version(core: &Path) -> Option<String> {
    let manifest = fs::read_to_string(core).ok()?;
    let package = section(&manifest, "package")?;
    if let Some(version) = field(package, "version") {
        return Some(version);
    }
    // `version.workspace = true`: take it from the ratatosk root manifest.
    if !package
        .lines()
        .any(|l| l.trim().starts_with("version.workspace"))
    {
        return None;
    }
    let root = core.parent()?.parent()?.parent()?.join("Cargo.toml");
    println!("cargo:rerun-if-changed={}", root.display());
    let root_manifest = fs::read_to_string(root).ok()?;
    field(section(&root_manifest, "workspace.package")?, "version")
}

/// The body of `[name]`, up to the next section header.
fn section<'a>(text: &'a str, name: &str) -> Option<&'a str> {
    let header = format!("[{name}]");
    let start = text.find(&header)? + header.len();
    let rest = &text[start..];
    let end = rest.find("\n[").unwrap_or(rest.len());
    Some(&rest[..end])
}

fn field(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let rest = line.trim().strip_prefix(key)?.trim_start();
        let value = rest.strip_prefix('=')?.trim();
        let value = value.trim_matches('"').trim_matches('\'').to_string();
        (!value.is_empty()).then_some(value)
    })
}
