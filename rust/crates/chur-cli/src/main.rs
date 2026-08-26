//! Chur command-line foundation.
//!
//! First-class architecture component (ARCHITECTURE.md section 9): the storage
//! format must be testable and recoverable independently of Android and iOS
//! UI code. It must never print plaintext secrets by default.
//!
//! Vector generation, inspection, verification, migration, and repair
//! subcommands land with their owning format crates (ROADMAP Phase 0).

fn main() {
    // Subcommands arrive per crate milestone; the binary exists so build
    // wiring, packaging, and CI targets can be established first.
}
