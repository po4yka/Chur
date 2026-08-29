//! Derived operation keys and opaque selectors of ADR-0051.

use std::collections::BTreeMap;

use chur_core::limits::ID_LEN;
use chur_core::{ChurStatus, Error, Id, Result};
use chur_crypto::kdf;
use chur_crypto::{Context, Key, Label};

/// One in-memory routing and encryption domain for sync operations.
pub struct KeyDomain {
    selector: Id,
    operation_key: Key,
    collection_id: Id,
    collection_epoch: u64,
}

/// The unlocked session's map from opaque selectors to operation keys.
pub struct KeyDirectory(BTreeMap<Id, KeyDomain>);

impl KeyDirectory {
    /// Starts a directory with the required root-operation domain.
    pub fn new(root: &Key, vault_id: &Id) -> Result<Self> {
        let root = KeyDomain::root(root, vault_id)?;
        Ok(Self(BTreeMap::from([(*root.selector(), root)])))
    }

    /// Adds a derived collection epoch and rejects a selector collision.
    pub fn insert(&mut self, domain: KeyDomain) -> Result<()> {
        if let Some(existing) = self.0.get(domain.selector()) {
            if existing.same_domain(&domain) {
                return Ok(());
            }
            return Err(Error::new(
                ChurStatus::AuthenticationFailed,
                "sync key selector collision",
            ));
        }
        self.0.insert(*domain.selector(), domain);
        Ok(())
    }

    /// Resolves an operation key without revealing the selector's domain.
    pub fn operation_key(&self, selector: &Id) -> Result<&Key> {
        Ok(self.domain(selector)?.operation_key())
    }

    /// Resolves the complete authenticated key domain for one selector.
    pub fn domain(&self, selector: &Id) -> Result<&KeyDomain> {
        self.0.get(selector).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "sync operation uses an unknown key selector",
            )
        })
    }
}

impl KeyDomain {
    /// Derives the root-operation domain from a vault root secret.
    pub fn root(root: &Key, vault_id: &Id) -> Result<Self> {
        Self::derive(
            root,
            Label::RootSyncOperations,
            Label::RootSyncSelector,
            &Context::vault(vault_id),
            *vault_id,
            0,
        )
    }

    /// Derives one collection-operation domain from its collection key epoch.
    pub fn collection(collection_key: &Key, collection_id: &Id, epoch: u64) -> Result<Self> {
        Self::derive(
            collection_key,
            Label::CollectionSyncOperations,
            Label::CollectionSyncSelector,
            &Context::collection_metadata(collection_id, epoch),
            *collection_id,
            epoch,
        )
    }

    /// The opaque routing value carried by an operation.
    #[must_use]
    pub const fn selector(&self) -> &Id {
        &self.selector
    }

    /// The key that seals and opens operation payloads in this domain.
    #[must_use]
    pub const fn operation_key(&self) -> &Key {
        &self.operation_key
    }

    /// Collection identifier, or the vault identifier for the root domain.
    #[must_use]
    pub const fn collection_id(&self) -> &Id {
        &self.collection_id
    }

    /// Collection epoch, or zero for the root domain.
    #[must_use]
    pub const fn collection_epoch(&self) -> u64 {
        self.collection_epoch
    }

    fn derive(
        parent: &Key,
        operation_label: Label,
        selector_label: Label,
        context: &Context,
        collection_id: Id,
        collection_epoch: u64,
    ) -> Result<Self> {
        let operation_key = kdf::derive_from(parent, operation_label, context)?;
        let selector_material = kdf::derive_from(parent, selector_label, context)?;
        Ok(Self {
            selector: selector_from_material(&selector_material)?,
            operation_key,
            collection_id,
            collection_epoch,
        })
    }

    fn same_domain(&self, other: &Self) -> bool {
        self.collection_id == other.collection_id
            && self.collection_epoch == other.collection_epoch
            && self.operation_key == other.operation_key
    }
}

fn selector_from_material(material: &Key) -> Result<Id> {
    let mut selector = [0; ID_LEN];
    selector.copy_from_slice(&material.expose()[..ID_LEN]);
    if selector == [0; ID_LEN] {
        selector[ID_LEN - 1] = 1;
    }
    Id::new(selector)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]

    use super::*;

    #[test]
    fn an_all_zero_prefix_is_normalized() {
        assert_eq!(
            selector_from_material(&Key::zeroed()).unwrap().as_bytes(),
            &[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]
        );
    }

    #[test]
    fn a_collision_with_another_key_is_rejected() {
        let selector = Id::new([1; ID_LEN]).unwrap();
        let mut directory = KeyDirectory(BTreeMap::from([(
            selector,
            KeyDomain {
                selector,
                operation_key: Key::new([2; 32]),
                collection_id: Id::new([4; ID_LEN]).unwrap(),
                collection_epoch: 1,
            },
        )]));

        assert!(
            directory
                .insert(KeyDomain {
                    selector,
                    operation_key: Key::new([3; 32]),
                    collection_id: Id::new([4; ID_LEN]).unwrap(),
                    collection_epoch: 1,
                })
                .is_err()
        );
    }
}
