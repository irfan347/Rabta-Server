# Rabta Network Vision

Network Vision is an on-device schematic of existing Nearby observations, available from Nearby → Network Vision and Connectivity diagnostics → Network Vision. It uses native Kotlin/Views, MeshBridge, Mesh/Router snapshots and the existing opt-in Radar transport. Opening Vision does not start services, advertising, discovery, pairing or connections.

## What the diagram means

- The current phone is the center. Other nodes come only from currently discovered IDs or actual direct connections. Connection requests, saved contacts, message recipients and relay packet origins do not create nodes.
- Peer IDs are deduplicated. A discovery-loss callback removes a disconnected peer; an actual connected peer remains until disconnection. Opt-out, account mismatch, stopped service and blocked peers suppress stale/private nodes.
- Lines are drawn only from this phone to current direct peers. Teal means the signed key matches the existing pin; violet means verification is pending. Trust on first use is not server-certified identity. Node positions and edge lengths never represent location or distance.
- The diagram shows up to 12 actual peers, with the complete count and a batched accessible peer list. Selecting a listed peer brings it into the diagram. Tap a node or use its accessible button to inspect live state.
- Screen-local Peer N aliases replace names and IDs. Phone numbers, endpoint IDs, message IDs/bodies, authentication data and keys are never displayed. No network map is uploaded or persisted.

## Relay and quality limits

Existing packet signatures authenticate the envelope origin/content. They do not attest the path or the unsigned hop field. The existing protocol cannot supply verified remote relay links, so Vision explicitly says these are unavailable and draws no remote peer-to-peer edges. Signature-checked relay acceptance and valid recipient receipt counters/events are actual Router observations, not topology proof.

Nearby does not expose per-link RSSI, radio selection or radio RTT to this application. No quality bars, distance estimates or invented latency are shown. When a current-service queued message receives a valid recipient receipt, the screen can show elapsed local queue-to-receipt time. This includes queue, recipient storage and any relay delay; it is explicitly **not radio RTT**. Restored queues with no current-service start timestamp remain unmeasured.

## Events, privacy and battery

A bounded RAM-only trace records queue acceptance, durable incoming storage, valid signed recipient delivery receipts and accepted signed relay envelopes. It records no body, name, key or credential. It holds at most 40 recent events, 512 dedup keys, 256 queue-start observations and 200 latest receipt timings; it clears with Router stop. Delivery is never inferred from local send completion. Events cannot add peers or route links.

The Activity reads snapshots every 2 seconds only while resumed. Direct-link animation is capped at 6.25 frames/second and stops when paused, hidden/offscreen, detached, screen-off, battery saver, Android animations disabled, or no direct peers. Discovered-but-unconnected nodes do not animate fake connections. Motion is labeled as link activity, not message traffic volume.

## Verification and checkpoint

Upstream source checkpoint: `b5a17726a351f051a21c9dec2b6409e9a5a26371`; local selected-source checkpoint: `checkpoint/pre-network-vision`. This execution workspace was empty after runtime replacement. Shell clone failed because the configured proxy was unavailable; affected source files were restored exactly through the GitHub connector for focused editing. Uploads are based on the complete upstream tree and preserve all other files. This local cache is not a replacement application or a production-data backup.

JVM tests cover real-state filtering, discovery/connection loss, key state, account/opt-out/block isolation, deduplication, no topology inferred from counters/events, aliases, bounded telemetry and receipt timing. Android tests use the real Activity, MeshBridge, Router and Keystore with a simulated transport to exercise discovery, direct connection, signed key exchange, loss, opt-out, graph taps, actual signed recipient receipt handling and private-data suppression.

Local Android build is unavailable (`gradle: command not found`). Existing GitHub Actions builds the complete checkout and runs JVM/device suites on API 29 and 34. Actual run results are recorded in the PR. These tests do not certify physical radios, remote relay topology, real network quality or battery consumption. Two-phone discovery/disconnect/opt-out/radio-toggle tests and three-phone carry/relay tests remain physical-device requirements. Do not clear production storage to run them.
