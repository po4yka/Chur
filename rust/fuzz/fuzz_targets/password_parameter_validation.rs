//! Argon2id parameter and password-byte validation, with no derivation.
//!
//! `CRYPTOGRAPHY.md` §18.3 requires every bound to be checked before Argon2
//! allocates. This target reaches only the check, so a fuzz run never asks for
//! 512 MiB.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_crypto::password::{self, Argon2Params};

#[derive(arbitrary::Arbitrary, Debug)]
struct Input {
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    salt_length: u8,
    password: Vec<u8>,
}

fuzz_target!(|input: Input| {
    if input.password.len() > 2048 {
        return;
    }
    if let Ok(params) = Argon2Params::validated(input.memory_kib, input.iterations, input.parallelism)
    {
        assert!((65_536..=524_288).contains(&params.memory_kib()));
        assert!((3..=10).contains(&params.iterations()));
        assert!((1..=4).contains(&params.parallelism()));
    }
    let salt = vec![0u8; usize::from(input.salt_length)];
    assert_eq!(
        password::check_salt(&salt).is_ok(),
        (16..=32).contains(&salt.len())
    );
    if let Ok(bytes) = password::canonical_bytes(&input.password) {
        assert_eq!(bytes.as_slice(), input.password.as_slice());
        assert!((1..=1024).contains(&bytes.len()));
        assert!(core::str::from_utf8(&bytes).is_ok());
    }
});
