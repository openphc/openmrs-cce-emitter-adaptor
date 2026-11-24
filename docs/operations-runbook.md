# Operations Runbook — OpenMRS CCE Emitter Adaptor

## Service Info

| Property | Value |
|----------|-------|
| Service | `openmrs-cce-emitter-adaptor` |
| Port | `8080` (default) |
| Health | `GET /actuator/health` |
| Metrics | `GET /actuator/prometheus` |

---

## Common Operations

```bash
# Start / Stop / Restart
docker compose up -d openmrs-cce-emitter-adaptor
docker compose stop openmrs-cce-emitter-adaptor
docker compose restart openmrs-cce-emitter-adaptor

# View logs
docker compose logs -f openmrs-cce-emitter-adaptor

# Check health
curl -s http://localhost:8080/actuator/health | jq

# Inspect checkpoints
docker exec openmrs-cce-emitter-adaptor cat /app/data/checkpoints.json | jq
```

### Reset Checkpoints (Re-poll from scratch)

```bash
docker compose stop openmrs-cce-emitter-adaptor
docker exec openmrs-cce-emitter-adaptor rm /app/data/checkpoints.json
docker compose start openmrs-cce-emitter-adaptor
```

> **Warning:** Deleting the checkpoint file triggers the sliding window seed — the service will re-poll only the last ~30 seconds of data (`intervalSeconds + overlapSeconds`), not all historical resources. May produce duplicates for resources updated in that window (handled by CCE deduplication).

### Rewind a Specific Resource Type's Checkpoint

The checkpoint file inside the container is owned by uid `999` (`appuser`). When editing from the host, write through a short-lived helper container so ownership is preserved:

```bash
jq '.checkpoints.ServiceRequest.lastUpdated = "2026-05-11T07:00:00Z"' data/checkpoints.json \
  | docker run --rm -i -v "$PWD/data:/data" alpine \
      sh -c 'cat > /data/checkpoints.json && chown 999:999 /data/checkpoints.json'
docker compose restart openmrs-cce-emitter-adaptor
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| No `poll.executed` metric increase | Polling disabled | Check `POLLING_FHIR_ENABLED` |
| `401 Unauthorized` from OpenMRS | Bad credentials or expired token | Check `OPENMRS_AUTH_USERNAME` / `OPENMRS_AUTH_PASSWORD`; for OAuth2 verify `OPENMRS_AUTH_OAUTH2_TOKEN_URL` and client credentials |
| `0 resources detected` | Checkpoint ahead of data | Reset checkpoints |
| `forward.failure` rising | OpenHIM unreachable | Check `OPENHIM_BASE_URL`; verify OpenHIM is running |
| `forward.failure` jumps but **no** retry log lines | OpenHIM returned a non-retriable 4xx (e.g. `401`, `404`, `409`) | Inspect the `Forward {type}/{id} aborting retries — non-retriable HTTP {code}` log; fix OpenHIM channel auth/route. Retries are intentionally skipped for `4xx` (except `408`, `429`). |
| `SSL handshake failed` | Self-signed cert | Set `OPENHIM_SSL_TRUST_ALL=true` (non-production) |
| Checkpoint file empty after restart | Volume not mounted | Mount `/app/data` as Docker volume |
| Same resources re-emitted every poll | Checkpoint not advancing past second boundary | Verify `CheckpointStore` is bumping by `+1s` on save and `POLLING_FHIR_OVERLAP_SECONDS=0`. OpenMRS fhir2 treats `_lastUpdated=gt{T}` as inclusive at the second boundary. |
| `forward.skipped{reason="missing-national-id"}` rising | Test/demo patients without a `national-id` identifier | Either populate the identifier in OpenMRS, set `PATIENT_NATIONAL_ID_ON_MISSING=forward-as-is` (diagnostics only), or `PATIENT_NATIONAL_ID_ENABLED=false` (non-Rwanda envs). |
| Order updates seen as duplicate emissions | OpenMRS order revisions are append-only | `OrderRevisionResolver` suppresses pure-discontinue tombstones; verify `POLLING_FHIR_FOLLOW_PRIOR_ORDER=true`. |

---

## Key Metrics to Monitor

| Metric | Healthy | Investigate |
|--------|---------|-------------|
| `openmrs_emitter_poll_executed_total` | Increasing | Flat (polling stopped) |
| `openmrs_emitter_forward_failure_total` | 0 or very low | Any increase |
| `openmrs_emitter_poll_duration_seconds` | < 10s | > 30s |
| `openmrs_emitter_checkpoint_age_seconds` | < 120s | > 600s |

---

## Recommended Alerts

```yaml
groups:
  - name: openmrs-emitter
    rules:
      - alert: OpenmrsEmitterPollStalled
        expr: increase(openmrs_emitter_poll_executed_total[5m]) == 0
        for: 5m
        labels: { severity: warning }

      - alert: OpenmrsEmitterForwardFailures
        expr: increase(openmrs_emitter_forward_failure_total[5m]) > 10
        for: 2m
        labels: { severity: critical }

      - alert: OpenmrsEmitterDown
        expr: up{job="openmrs-cce-emitter-adaptor"} == 0
        for: 1m
        labels: { severity: critical }
```

---

## Beckn Mode

> **Status:** Implemented on branch `sygnal-actions` (package `org.openphc.cce.emitter.beckn`). Not yet tested against real onix containers. See [beckn-integration.md](beckn-integration.md).

This section applies when `OPENMRS_EMITTER_TARGET=beckn`, the default on this branch. The emitter then sends every resource to the HIE as Sygnal `sygnal/event/report` through `onix-openmrs`, and referrals to Spice as standard Beckn (`on_status` through `onix-bpp`, `update` through `onix-bap`). Since 2026-11-23 the box of the referral messages is a `sygnal:CoordinationEvent` with an `@context`, in place of `cce:CceClinicalEvent`. With `openhim`, everything above is unchanged.

### Checks

```bash
# Which target is the emitter using?
docker exec openmrs-cce-emitter-adaptor printenv OPENMRS_EMITTER_TARGET

# Can the emitter reach the onix callers? (any HTTP code means reachable; 000 means not)
docker exec openmrs-cce-emitter-adaptor curl -s -o /dev/null -w '%{http_code}\n' http://onix-openmrs:8082/sygnal/caller/sygnal/event/report
docker exec openmrs-cce-emitter-adaptor curl -s -o /dev/null -w '%{http_code}\n' http://onix-bap:8081/bap/caller/update
docker exec openmrs-cce-emitter-adaptor curl -s -o /dev/null -w '%{http_code}\n' http://onix-bpp:8082/bpp/caller/on_status

# One line per message
docker compose logs openmrs-cce-emitter-adaptor | grep 'beckn action='

# Beckn metrics
curl -s http://localhost:8085/actuator/prometheus | grep openmrs_beckn_
```

The log lines carry ids and results only. Message bodies and patient data are not logged. To follow one message across systems, search for its `messageId` or event id in the onix and HIE logs. Each retry has a new `messageId`; the event id stays the same.

An accepted event is answered `202` (Sygnal leg) or `200` (Beckn legs) with `{ "message": { "status": "ACK", … } }`; the Sygnal leg has no callback. A `Nack` body looks like `{ "message": { "status": "NACK", "messageId": "…", "error": { "code": "…", "message": "…" } } }`. The log line carries `error.code` and `error.message`. `400` answers (CCE leg: `CTX_*`, `SCH_*`, `BIZ_GENERIC_ERROR`) are not retried. `500` answers (CCE leg: `NET_DOWNSTREAM_UNAVAILABLE`, `NET_INTERNAL_ERROR`) are retried. Spice's codes are handled the same way, by HTTP status.

In Beckn mode the health endpoint does not check OpenHIM. It checks `onix` instead: a `GET` to each onix caller in use (`sygnal`, `bap`, `bpp`); any HTTP answer is `UP`, no answer is `DOWN` (`curl -s localhost:8085/actuator/health` with `HEALTH_SHOW_DETAILS=always` shows the URLs).

Resources with no patient (`Location`, `Practitioner`, …) are skipped in Beckn mode: `grep 'beckn skip'`, metric `openmrs_beckn_resources_skipped_total`.

### Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| No `openmrs_beckn_*` metrics; `forward.success` rising | Emitter is on the OpenHIM path | Set `OPENMRS_EMITTER_TARGET=beckn` and restart |
| `result=NACK` on every message | Schema, signature or context problem | Read `error.code` and `error.message` in the log line. Check `OPENMRS_EMITTER_BECKN_SCHEMA_BASE_URL`; on `leg=cce-events` `OPENMRS_EMITTER_BECKN_SYGNAL_ID` (must match the keys in `onix-openmrs`) and `OPENMRS_EMITTER_BECKN_SYGNAL_NETWORK_ID`; on the referral legs the contract ids in `emitter.beckn.spice.*`; and that the onix containers hold the Sygnal profile (onix-openmrs: `cce-sygnal-apis.yaml` and `cce-fhir-extension.yaml`, in one folder), the `CoordinationEvent/v3.0` schema and the Health* pack (onix-bap, onix-bpp) and OpenMRS's keys |
| `NACK` `SCH_SCHEMA_ADAPTATION_FAILED` on a referral leg | One side still sends the old `cce:CceClinicalEvent` box, which an adapter without the old schema refuses | Both OpenMRS and Spice must send the `sygnal:CoordinationEvent` box (since 2026-11-23). Check the emitter image, and that `onix-bap`, `onix-bpp` and Spice's adapters hold `CoordinationEvent/v3.0` |
| `NACK` with `CTX_INVALID_FIELD` about `receiverId` on `leg=cce-events` | `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_ID` is not the HIE's subscriber id | Set it to the HIE's id (`bap.openhim.nextsteps.mdtlabs.org`) |
| `NACK` with `BIZ_GENERIC_ERROR` on `leg=cce-events` | CCE refused the event | Read `error.message`. Fix the data in OpenMRS. The corrected resource gets a new event id, so CCE does not drop it as a duplicate |
| `NACK` from onix about `context` fields on `leg=cce-events` | An old `bapId` / `bppId` style message to the HIE, or a field the closed Sygnal context does not allow | Check the image is the `sygnal-actions` build |
| Retries then `messages_dropped_total{reason="retries-exhausted"}` | onix, the HIE, CCE or Spice is down or slow, or `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_URI` points to the wrong host | Check the far side. Raise `OPENHIM_RETRY_MAX_ATTEMPTS` / `OPENHIM_RETRY_BACKOFF_MS`. Dropped messages are not resent (rewind the checkpoint to resend) |
| Connection refused to `onix-openmrs` / `onix-bap` / `onix-bpp` | Containers down or not on `cce-net` | Start them; check `docker network inspect cce-net` |
| Poll cycles take much longer | Each message waits for the answer from the far side (up to 50 s) | Check `openmrs_beckn_onix_latency_seconds`; check `emitter.beckn.onix.timeout` and the timeout budget ([beckn-integration.md](beckn-integration.md) § 10.4) |
| Retries on `leg=cce-events` although CCE got the event | An inner hop waits longer than an outer one, or onix still has the 5 s default | Check every hop against the timeout budget. A resend is safe: CCE answers `Ack` and drops it as a duplicate |
| No `on_update` logged by the receiver for a referral sent to Spice | `on_update` is sent once and may be lost | Nothing to do if the emitter logged `result=ACK`. The `Ack` is the proof |
| Referral out not sent to Spice | Facility code not in `spice.facility-codes`, or no facility code on the referral | Check `openmrs_beckn_referrals_out_total{outcome="facility-not-served"}`; check the destination Location's facility-code attribute |
| Referral outcome not sent to Spice | Referral was not marked `referral-response`, or `legs.referrals-in=false` | Check `REFERRAL_RESPONSE_ENABLED` and the order type name |
| CCE shows a resent event as new | The resource JSON changed between polls, so its hash changed | Look for an enricher that adds a changing field |

### Alerts

```yaml
      - alert: OpenmrsBecknMessagesDropped
        expr: increase(openmrs_beckn_messages_dropped_total[10m]) > 0
        for: 1m
        labels: { severity: critical }
```

---

## Maintenance

- **Single instance** only — no active-active scaling (would cause duplicates)
- **Checkpoint file** is the only persistent state — back up before major changes
- **Upgrading:** build new image → stop → start with same volume mounts
