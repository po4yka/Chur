# Chur Sync Server Operator Guide

> **Status:** Phase 3 reference deployment

The Chur project operates no sync service. This process is for a deployment that the user controls. It stores opaque identifiers, signed encrypted records, ciphertext lengths, transfer state, and ciphertext objects. It cannot decrypt vault content.

## Start

Run from `rust/`:

```sh
cargo run --locked --release -p chur-sync-server
```

Set `CHUR_SYNC_BOOTSTRAP_TOKEN` to 32 random bytes encoded as 64 hexadecimal characters before start. Keep it in the process secret store, not in a command argument or the data directory. It authorizes only the first signed membership and transport-token binding for a new opaque vault identity.

The default listener is `127.0.0.1:7780` and the default data directory is `./chur-sync-data`. Configure it with these environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `CHUR_SYNC_BIND` | `127.0.0.1:7780` | Listener address |
| `CHUR_SYNC_DATA` | `chur-sync-data` | SQLite database and opaque object root |
| `CHUR_SYNC_BOOTSTRAP_TOKEN` | none | Required operator control-plane secret |
| `CHUR_SYNC_MAX_OBJECT_BYTES` | `1099511627776` | Maximum ciphertext object length |
| `CHUR_SYNC_MAX_ACCOUNT_BYTES` | `2199023255552` | Maximum reserved bytes for one vault identity |

`GET /healthz` returns `204` when the service state is available.

## HTTP v1

All identifiers in paths are 32 hexadecimal characters. All request and response bodies are `application/octet-stream`. Except for bootstrap and signed deletion, a route requires `Authorization: Bearer <64 hex characters>`.

| Method and path | Body or query | Result |
| --- | --- | --- |
| `POST /v1/vaults/{vault}/bootstrap` | new transport token, enrollment, outer operation | first membership |
| `GET /v1/vaults/{vault}/memberships?after={generation}` | none | membership record page |
| `POST /v1/vaults/{vault}/memberships/enroll` | new device token, enrollment, outer operation | successor membership |
| `POST /v1/vaults/{vault}/memberships/revoke` | revocation, outer operation | successor membership |
| `POST /v1/vaults/{vault}/operations` | one canonical operation | stored operation |
| `GET /v1/vaults/{vault}/operations/{device}?after={sequence}` | none | operation page |
| `POST /v1/vaults/{vault}/checkpoints` | one canonical checkpoint | stored checkpoint |
| `GET /v1/vaults/{vault}/checkpoints` | none | latest checkpoint page |
| `GET /v1/vaults/{vault}/checkpoints/{commitment}` | none | exact checkpoint |
| `POST /v1/vaults/{vault}/token` | 32-byte replacement token | rotated caller token |

Bootstrap uses `Authorization: Bootstrap <CHUR_SYNC_BOOTSTRAP_TOKEN>`. Its body and enrollment body are `new_token:bytes[32] || first_length:u32be || first_record || outer_operation`. Revocation omits `new_token`. A record page is `count:u32be`, followed by `length:u32be || canonical_record` for each item. The response is bounded to 256 records and 16 MiB. An error body is one signed big-endian `ChurStatus` value.

## Network boundary

Keep the process on a loopback or private address. Put a reverse proxy with TLS 1.3 in front of it. Do not expose the default cleartext listener to an untrusted network. Back up the complete data directory as one unit because SQLite and object files share one logical store.

## Retention and observed data

The process writes no request log and sends no telemetry. A reverse proxy can observe client IP addresses, request times, opaque vault and device identifiers, object counts, and transfer sizes. Disable its access log unless abuse control requires it. If enabled, delete request logs within 30 days and do not send them to a third party.

A valid signed deletion authorization removes an object immediately. An account authorization removes the account database rows and object directory immediately. The retained authorization is the deletion audit record; an acknowledgment is not proof that storage media erased every prior block.

## Files and access

Give the process user exclusive read and write access to `CHUR_SYNC_DATA`. Do not put plaintext, TLS private keys, transport tokens, or reverse-proxy logs in that directory. The SQLite database stores SHA-256 transport-token digests, not tokens.
