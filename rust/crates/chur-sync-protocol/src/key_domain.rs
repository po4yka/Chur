//! Derived operation keys and opaque selectors of ADR-0051.

use chur_core::Id;
use chur_core::Result;
use chur_core::limits::ID_LEN;
use chur_crypto::kdf;
use chur_crypto::{Context, Key, Label};

/// One in-memory routing and encryption domain for sync operations.
pub struct KeyDomain {
    selector: Id,
    operation_key: Key,
}

impl KeyDomain {
    /// Derives the root-operation domain from a vault root secret.
    pub fn root(root: &Key, vault_id: &Id) -> Result<Self> {
        Self::derive(
            root,
            Label::RootSyncOperations,
            Label::RootSyncSelector,
            &Context::vault(vault_id),
        )
    }

    /// Derives one collection-operation domain from its collection key epoch.
    pub fn collection(collection_key: &Key, collection_id: &Id, epoch: u64) -> Result<Self> {
        Self::derive(
            collection_key,
            Label::CollectionSyncOperations,
            Label::CollectionSyncSelector,
            &Context::collection_metadata(collection_id, epoch),
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

    fn derive(
        parent: &Key,
        operation_label: Label,
        selector_label: Label,
        context: &Context,
    ) -> Result<Self> {
        let operation_key = kdf::derive_from(parent, operation_label, context)?;
        let selector_material = kdf::derive_from(parent, selector_label, context)?;
        Ok(Self {
            selector: selector_from_material(&selector_material)?,
            operation_key,
        })
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
}
