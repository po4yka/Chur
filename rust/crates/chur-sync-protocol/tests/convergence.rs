//! Deterministic scalar convergence checks.

#![allow(clippy::expect_used, clippy::panic)]

use chur_core::Id;
use chur_crypto::{Key, Nonce};
use chur_sync_protocol::convergence::{CausalStamp, MergeOutcome, ScalarRegister};
use chur_sync_protocol::operation::{DeviceSigningKey, ObservedHead, Operation};

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("identifier")
}

fn operation(
    key: &DeviceSigningKey,
    marker: u8,
    device: Id,
    sequence: u64,
    previous: [u8; 32],
    observed: Vec<ObservedHead>,
) -> Operation {
    Operation::seal(
        id(marker),
        id(1),
        device,
        sequence,
        previous,
        observed,
        id(9),
        &Key::new([8; 32]),
        Nonce::new([marker; 24]),
        &[marker],
    )
    .expect("operation")
    .sign(key)
}

#[test]
fn scalar_register_keeps_only_causal_maxima_and_uses_digest_for_concurrency() {
    let key_a = DeviceSigningKey::from_seed([2; 32]);
    let key_b = DeviceSigningKey::from_seed([3; 32]);
    let a1 = operation(&key_a, 4, id(2), 1, [0; 32], Vec::new());
    let b1 = operation(&key_b, 5, id(3), 1, [0; 32], Vec::new());
    let resolver = operation(
        &key_a,
        6,
        id(2),
        2,
        a1.digest(),
        vec![ObservedHead::new(id(3), 1)],
    );
    let mut register = ScalarRegister::new();

    assert!(
        register
            .apply(CausalStamp::from_operation(&b1), "b")
            .expect("b")
            == MergeOutcome::Applied
    );
    assert!(
        register
            .apply(CausalStamp::from_operation(&a1), "a")
            .expect("a")
            == MergeOutcome::Applied
    );
    assert_eq!(register.conflict_count(), 2);
    let expected = if a1.digest() > b1.digest() { "a" } else { "b" };
    assert_eq!(register.displayed(), Some(&expected));
    let mut reverse = ScalarRegister::new();
    reverse
        .apply(CausalStamp::from_operation(&a1), "a")
        .expect("a");
    reverse
        .apply(CausalStamp::from_operation(&b1), "b")
        .expect("b");
    assert_eq!(reverse.displayed(), register.displayed());
    assert_eq!(reverse.conflict_count(), register.conflict_count());

    assert!(
        register
            .apply(CausalStamp::from_operation(&resolver), "resolved")
            .expect("resolver")
            == MergeOutcome::Applied
    );
    assert_eq!(register.conflict_count(), 1);
    assert_eq!(register.displayed(), Some(&"resolved"));
    assert!(
        register
            .apply(CausalStamp::from_operation(&a1), "a")
            .expect("old replay")
            == MergeOutcome::Obsolete
    );
}
