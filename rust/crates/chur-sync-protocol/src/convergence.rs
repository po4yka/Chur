//! Causal comparison and deterministic scalar convergence.

use std::collections::{BTreeMap, BTreeSet};

use chur_core::{ChurStatus, Error, Id, Result};
use chur_crypto::Commitment;

use crate::operation::Operation;

/// Minimal authenticated causal position retained by materialized state.
#[derive(Clone, PartialEq, Eq)]
pub struct CausalStamp {
    operation_id: Id,
    device_id: Id,
    device_sequence: u64,
    observed_heads: Vec<(Id, u64)>,
    digest: Commitment,
}

impl CausalStamp {
    /// Extracts the signed causal position and tie-break digest.
    #[must_use]
    pub fn from_operation(operation: &Operation) -> Self {
        Self {
            operation_id: *operation.operation_id(),
            device_id: *operation.device_id(),
            device_sequence: operation.device_sequence(),
            observed_heads: operation
                .observed_heads()
                .iter()
                .map(|head| (*head.device_id(), head.device_sequence()))
                .collect(),
            digest: operation.digest(),
        }
    }

    /// Stable add-token and idempotency identifier.
    #[must_use]
    pub const fn operation_id(&self) -> &Id {
        &self.operation_id
    }

    /// Authoring device.
    #[must_use]
    pub const fn device_id(&self) -> &Id {
        &self.device_id
    }

    /// Author-local sequence.
    #[must_use]
    pub const fn device_sequence(&self) -> u64 {
        self.device_sequence
    }

    /// Signed operation digest used only as a concurrent tie-break.
    #[must_use]
    pub const fn digest(&self) -> &Commitment {
        &self.digest
    }

    /// Whether this operation directly observes the named accepted head.
    #[must_use]
    pub fn observes(&self, device_id: &Id, sequence: u64) -> bool {
        if &self.device_id == device_id {
            return self.device_sequence >= sequence;
        }
        self.observed_heads
            .binary_search_by_key(device_id, |(observed_id, _)| *observed_id)
            .is_ok_and(|index| self.observed_heads[index].1 >= sequence)
    }
}

/// Causal relation of the left operation to the right operation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CausalRelation {
    /// Both values came from the same complete operation.
    Same,
    /// The left operation happened before the right operation.
    Before,
    /// The left operation happened after the right operation.
    After,
    /// Neither operation observed the other.
    Concurrent,
}

/// Compares two authenticated accepted operation positions.
pub fn causal_relation(left: &CausalStamp, right: &CausalStamp) -> Result<CausalRelation> {
    if left.operation_id == right.operation_id {
        if left.digest == right.digest {
            return Ok(CausalRelation::Same);
        }
        return Err(Error::new(
            ChurStatus::AuthenticationFailed,
            "operation identifier names different signed records",
        ));
    }
    if left.device_id == right.device_id {
        if left.device_sequence == right.device_sequence {
            return Err(Error::new(
                ChurStatus::SyncChainFork,
                "one device sequence has two operations",
            ));
        }
        return Ok(if left.device_sequence < right.device_sequence {
            CausalRelation::Before
        } else {
            CausalRelation::After
        });
    }
    if left.digest == right.digest {
        return Err(Error::new(
            ChurStatus::AuthenticationFailed,
            "distinct operations have the same operation digest",
        ));
    }
    let left_after = left.observes(&right.device_id, right.device_sequence);
    let right_after = right.observes(&left.device_id, left.device_sequence);
    match (left_after, right_after) {
        (false, false) => Ok(CausalRelation::Concurrent),
        (true, false) => Ok(CausalRelation::After),
        (false, true) => Ok(CausalRelation::Before),
        (true, true) => Err(Error::new(
            ChurStatus::AuthenticationFailed,
            "accepted operation positions contain a causal cycle",
        )),
    }
}

/// Result of applying one value to a convergent materialized field.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MergeOutcome {
    /// The value became one of the causal maxima.
    Applied,
    /// The exact operation was already represented.
    Duplicate,
    /// A causally later value was already represented.
    Obsolete,
    /// A remove token's add operation has not been materialized yet.
    PendingCause,
}

struct ScalarVersion<T> {
    stamp: CausalStamp,
    value: T,
}

/// Multi-value register with one deterministic displayed winner.
pub struct ScalarRegister<T> {
    versions: Vec<ScalarVersion<T>>,
}

impl<T> ScalarRegister<T> {
    /// Empty register.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            versions: Vec::new(),
        }
    }

    /// Applies one value and retains only causally maximal versions.
    pub fn apply(&mut self, stamp: CausalStamp, value: T) -> Result<MergeOutcome> {
        let mut relations = Vec::with_capacity(self.versions.len());
        for version in &self.versions {
            let relation = causal_relation(&version.stamp, &stamp)?;
            if relation == CausalRelation::Same {
                return Ok(MergeOutcome::Duplicate);
            }
            if relation == CausalRelation::After {
                return Ok(MergeOutcome::Obsolete);
            }
            relations.push(relation);
        }
        let mut index = 0usize;
        self.versions.retain(|_| {
            let keep = relations[index] != CausalRelation::Before;
            index += 1;
            keep
        });
        self.versions.push(ScalarVersion { stamp, value });
        Ok(MergeOutcome::Applied)
    }

    /// Value selected for display by the greater concurrent operation digest.
    #[must_use]
    pub fn displayed(&self) -> Option<&T> {
        self.versions
            .iter()
            .max_by_key(|version| version.stamp.digest)
            .map(|version| &version.value)
    }

    /// Number of retained concurrent causal maxima.
    #[must_use]
    pub const fn conflict_count(&self) -> usize {
        self.versions.len()
    }
}

impl<T> Default for ScalarRegister<T> {
    fn default() -> Self {
        Self::new()
    }
}

/// Observed-remove set shared by memberships, tags, and favorites.
pub struct ObservedRemoveSet<E> {
    adds: BTreeMap<E, BTreeMap<Id, CausalStamp>>,
    removals: BTreeMap<E, BTreeSet<Id>>,
    applied: BTreeMap<Id, Commitment>,
}

impl<E: Clone + Ord> ObservedRemoveSet<E> {
    /// Empty set.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            adds: BTreeMap::new(),
            removals: BTreeMap::new(),
            applied: BTreeMap::new(),
        }
    }

    /// Adds the containing operation's identifier as a unique token.
    pub fn add(&mut self, element: E, stamp: CausalStamp) -> Result<MergeOutcome> {
        if let Some(outcome) = self.duplicate_or_reuse(&stamp)? {
            return Ok(outcome);
        }
        let token = *stamp.operation_id();
        self.applied.insert(token, *stamp.digest());
        self.adds
            .entry(element.clone())
            .or_default()
            .insert(token, stamp);
        Ok(
            if self
                .removals
                .get(&element)
                .is_some_and(|tokens| tokens.contains(&token))
            {
                MergeOutcome::Obsolete
            } else {
                MergeOutcome::Applied
            },
        )
    }

    /// Removes exactly the add tokens observed by the author.
    pub fn remove(
        &mut self,
        element: E,
        stamp: CausalStamp,
        removed_tokens: &[Id],
    ) -> Result<MergeOutcome> {
        if let Some(outcome) = self.duplicate_or_reuse(&stamp)? {
            return Ok(outcome);
        }
        if removed_tokens.is_empty() {
            self.applied.insert(*stamp.operation_id(), *stamp.digest());
            return Ok(MergeOutcome::Applied);
        }
        let Some(adds) = self.adds.get(&element) else {
            return Ok(MergeOutcome::PendingCause);
        };
        for token in removed_tokens {
            let Some(add) = adds.get(token) else {
                return Ok(MergeOutcome::PendingCause);
            };
            ensure_observed_remove(add, &stamp)?;
        }
        self.applied.insert(*stamp.operation_id(), *stamp.digest());
        self.removals
            .entry(element)
            .or_default()
            .extend(removed_tokens.iter().copied());
        Ok(MergeOutcome::Applied)
    }

    /// Whether at least one unremoved add token remains.
    #[must_use]
    pub fn contains(&self, element: &E) -> bool {
        self.adds.get(element).is_some_and(|adds| {
            adds.keys().any(|token| {
                self.removals
                    .get(element)
                    .is_none_or(|removals| !removals.contains(token))
            })
        })
    }

    /// Sorted current tokens to place in a remove operation.
    #[must_use]
    pub fn add_tokens(&self, element: &E) -> Vec<Id> {
        self.adds.get(element).map_or_else(Vec::new, |adds| {
            adds.keys()
                .filter(|token| {
                    self.removals
                        .get(element)
                        .is_none_or(|removals| !removals.contains(token))
                })
                .copied()
                .collect()
        })
    }

    fn duplicate_or_reuse(&self, stamp: &CausalStamp) -> Result<Option<MergeOutcome>> {
        let Some(digest) = self.applied.get(stamp.operation_id()) else {
            return Ok(None);
        };
        if digest == stamp.digest() {
            return Ok(Some(MergeOutcome::Duplicate));
        }
        Err(Error::new(
            ChurStatus::AuthenticationFailed,
            "operation identifier was reused in observed-remove state",
        ))
    }
}

fn ensure_observed_remove(add: &CausalStamp, remove: &CausalStamp) -> Result<()> {
    if causal_relation(add, remove)? == CausalRelation::Before {
        return Ok(());
    }
    Err(Error::new(
        ChurStatus::AuthenticationFailed,
        "remove names an add token it did not causally observe",
    ))
}

impl<E: Clone + Ord> Default for ObservedRemoveSet<E> {
    fn default() -> Self {
        Self::new()
    }
}
