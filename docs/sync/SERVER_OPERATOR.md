# Chur Sync Server Operator Guide

> **Status:** Phase 3 reference deployment

The Chur project operates no sync service. This process is for a deployment that the user controls. It stores opaque identifiers, signed encrypted records, ciphertext lengths, transfer state, and ciphertext objects. It cannot decrypt vault content.

## Start

Run from `rust/`:

```sh
cargo run --locked --release -p chur-sync-server
```

The default listener is `127.0.0.1:7780` and the default data directory is `./chur-sync-data`. Configure it with these environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `CHUR_SYNC_BIND` | `127.0.0.1:7780` | Listener address |
| `CHUR_SYNC_DATA` | `chur-sync-data` | SQLite database and opaque object root |
| `CHUR_SYNC_MAX_OBJECT_BYTES` | `1099511627776` | Maximum ciphertext object length |
| `CHUR_SYNC_MAX_ACCOUNT_BYTES` | `2199023255552` | Maximum reserved bytes for one vault identity |

`GET /healthz` returns `204` when the service state is available.

## Network boundary

Keep the process on a loopback or private address. Put a reverse proxy with TLS 1.3 in front of it. Do not expose the default cleartext listener to an untrusted network. Back up the complete data directory as one unit because SQLite and object files share one logical store.

## Retention and observed data

The process writes no request log and sends no telemetry. A reverse proxy can observe client IP addresses, request times, opaque vault and device identifiers, object counts, and transfer sizes. Disable its access log unless abuse control requires it. If enabled, delete request logs within 30 days and do not send them to a third party.

A valid signed deletion authorization removes an object immediately. An account authorization removes the account database rows and object directory immediately. The retained authorization is the deletion audit record; an acknowledgment is not proof that storage media erased every prior block.

## Files and access

Give the process user exclusive read and write access to `CHUR_SYNC_DATA`. Do not put plaintext, TLS private keys, transport tokens, or reverse-proxy logs in that directory. The SQLite database stores SHA-256 transport-token digests, not tokens.
