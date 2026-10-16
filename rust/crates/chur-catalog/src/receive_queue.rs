//! Durable fair dispatch of pending media, independent of key custody.
use crate::{
    CatalogDb,
    db::{as_sqlite_integer, from_sqlite_integer, map_sqlite},
};
use chur_core::{Id, Result, ensure};
use rusqlite::params;
/// The ABI download plan limit, distinct from transport page sizes.
pub(crate) const PAGE_MAX: usize = 256;
pub(crate) fn dispatched(db: &mut CatalogDb, collection: &Id, objects: &[Id]) -> Result<()> {
    ensure!(
        objects.len() <= PAGE_MAX,
        InvalidInput,
        "receive dispatch page is too large"
    );
    if objects.is_empty() {
        return Ok(());
    }
    db.transaction(|tx| {
        let mut ticket:i64=tx.query_row("SELECT coalesce(max(ticket),0) FROM object_receive_dispatch WHERE collection_id = ?1",
            [collection.as_bytes().as_slice()],|row|row.get(0)).map_err(|error|map_sqlite(error,"receive dispatch counter could not be read"))?;
        // Move every returned object behind undispatched objects. Place this
        // batch's first item last, so an abort on its download cannot keep the
        // same object at the head on every later visit to the batch.
        for object in objects.iter().skip(1).chain(objects.first()) {
            ticket=as_sqlite_integer(from_sqlite_integer(ticket,"receive dispatch counter is negative")?.checked_add(1)
                .ok_or_else(||chur_core::err!(ResourceLimitExceeded,"receive dispatch counter overflowed"))?,"receive dispatch counter overflowed")?;
            tx.execute("INSERT INTO object_receive_dispatch VALUES (?1, ?2, ?3) ON CONFLICT(collection_id, object_id) DO UPDATE SET ticket = excluded.ticket",
                params![collection.as_bytes().as_slice(),object.as_bytes().as_slice(),ticket])
                .map_err(|error|map_sqlite(error,"receive dispatch could not be advanced"))?;
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]
    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        schema,
        sharing_log::{self, DurableCollectionOperationLog},
    };
    use chur_crypto::{Key, random};
    fn object(index: u16) -> Id {
        let mut bytes = [1; 16];
        bytes[14..].copy_from_slice(&index.to_be_bytes());
        Id::new(bytes).expect("object")
    }
    #[test]
    fn unavailable_first_4096_rows_do_not_hide_later_media_across_restart() {
        let path = std::env::temp_dir().join(format!(
            "chur-fair-receive-{}.db",
            random::id().expect("path").to_hex()
        ));
        let key = CatalogKey::derive(&Key::new([71; 32]), &object(1)).expect("catalog key");
        let collection = object(5000);
        let selector = object(5001);
        let mut db = CatalogDb::open(&CatalogLocation::File(&path), &key).expect("catalog");
        schema::open_at_current_version(&mut db, 1).expect("schema");
        crate::sharing::provision(&mut db, object(1), collection, 1)
            .expect("collection authorization");
        DurableCollectionOperationLog::provision(&mut db, collection, 1, selector).expect("stream");
        // This fixture tests the durable dispatch table and SQL frontier, not
        // operation authentication. The shared 66-object test authenticates
        // real signed content and accepts asynchronous HPKE grants.
        db.transaction(|tx| {
            tx.execute("UPDATE sharing_operation_streams SET object_index_ready = 1 WHERE key_selector = ?1",[selector.as_bytes().as_slice()]).expect("projection ready");
            for index in 1..=4097 { tx.execute("INSERT INTO sharing_object_projection(key_selector, object_id, revision, dirty) VALUES (?1, ?2, 1, 1)",
                params![selector.as_bytes().as_slice(),object(index).as_bytes().as_slice()]).expect("pending object projection"); }
            Ok(())
        }).expect("pending queue");
        let mut reached = false;
        for pass in 0..17 {
            let page = sharing_log::dirty_object_ids(&db, &selector, 4096)
                .expect("bounded frontier")
                .into_iter()
                .take(PAGE_MAX)
                .map(|(id, _)| id)
                .collect::<Vec<_>>();
            reached |= page.contains(&object(4097));
            dispatched(&mut db, &collection, &page).expect("durable dispatch");
            let next =
                sharing_log::dirty_object_ids(&db, &selector, 4096).expect("next bounded page");
            assert_ne!(
                next[0].0, page[0],
                "an abort at the first download must rotate the next head"
            );
            if pass == 7 {
                db.close().expect("close pending queue");
                db = CatalogDb::open(&CatalogLocation::File(&path), &key)
                    .expect("restart pending queue");
            }
        }
        assert!(
            reached,
            "a valid later object must be dispatched while every older object remains unavailable"
        );
        let count: i64 = db
            .connection()
            .query_row(
                "SELECT count(*) FROM sharing_object_projection WHERE dirty = 1",
                [],
                |row| row.get(0),
            )
            .expect("queue count");
        assert_eq!(
            count, 4097,
            "scheduling must not discard pending work or keys"
        );
        db.close().expect("close");
        std::fs::remove_file(path).expect("cleanup");
    }
}
