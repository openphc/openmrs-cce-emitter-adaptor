# Configuration Guide — OpenMRS CCE Emitter Adaptor

## Spring Profiles

| Profile | YAML file | Purpose |
|---------|-----------|---------|
| `default` | `application.yml` | Base config with sensible defaults |
| `local` | `application-local.yml` | Local dev — DEBUG logging, SSL trust-all |
| `staging` | `application-staging.yml` | Pre-production — INFO logging |
| `production` | `application-production.yml` | Production — WARN root, health details hidden |

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

---

## Configuration Reference

### Server

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |
| `server.shutdown` | — | `graceful` | Graceful shutdown |
| `spring.lifecycle.timeout-per-shutdown-phase` | `SHUTDOWN_TIMEOUT` | `30s` | Shutdown wait |

### OpenMRS Connection

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.openmrs.name` | `OPENMRS_NAME` | `openmrs` | Display name |
| `emitter.openmrs.base-url` | `OPENMRS_BASE_URL` | `http://localhost:8080/openmrs` | OpenMRS base URL |
| `emitter.openmrs.fhir-path` | `OPENMRS_FHIR_PATH` | `/ws/fhir2/R4` | FHIR API path |

### OpenMRS Authentication

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.openmrs.auth.type` | `OPENMRS_AUTH_TYPE` | `basic` | `basic`, `bearer`, or `oauth2` |
| `emitter.openmrs.auth.username` | `OPENMRS_AUTH_USERNAME` | `admin` | Username (for basic) |
| `emitter.openmrs.auth.password` | `OPENMRS_AUTH_PASSWORD` | `Admin123` | Password (for basic) |
| `emitter.openmrs.auth.token` | `OPENMRS_AUTH_TOKEN` | — | Bearer token |
| `emitter.openmrs.auth.oauth2.token-url` | `OPENMRS_AUTH_OAUTH2_TOKEN_URL` | — | OAuth2 token endpoint URL |
| `emitter.openmrs.auth.oauth2.client-id` | `OPENMRS_AUTH_OAUTH2_CLIENT_ID` | — | OAuth2 client ID |
| `emitter.openmrs.auth.oauth2.client-secret` | `OPENMRS_AUTH_OAUTH2_CLIENT_SECRET` | — | OAuth2 client secret |
| `emitter.openmrs.auth.oauth2.scope` | `OPENMRS_AUTH_OAUTH2_SCOPE` | — | OAuth2 scope (optional) |

### OpenHIM Connection & Auth

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.openhim.base-url` | `OPENHIM_BASE_URL` | `http://localhost:5001/fhir` | OpenHIM base URL |
| `emitter.openhim.auth.type` | `OPENHIM_AUTH_TYPE` | `basic` | `none`, `basic`, `jwt`, `custom-token` |
| `emitter.openhim.auth.username` | `OPENHIM_AUTH_USERNAME` | — | Basic auth username |
| `emitter.openhim.auth.password` | `OPENHIM_AUTH_PASSWORD` | — | Basic auth password |
| `emitter.openhim.auth.token` | `OPENHIM_AUTH_TOKEN` | — | JWT or custom token |
| `emitter.openhim.ssl-trust-all` | `OPENHIM_SSL_TRUST_ALL` | `false` | Trust all SSL certs |
| `emitter.openhim.append-resource-type` | `OPENHIM_APPEND_RESOURCE_TYPE` | `true` | Append `/{ResourceType}` to URL |
| `emitter.openhim.retry.max-attempts` | `OPENHIM_RETRY_MAX_ATTEMPTS` | `3` | Max retries |
| `emitter.openhim.retry.backoff-ms` | `OPENHIM_RETRY_BACKOFF_MS` | `2000` | Backoff per attempt (ms) |

### Polling

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.polling.fhir.enabled` | `POLLING_FHIR_ENABLED` | `true` | Enable FHIR polling |
| `emitter.polling.fhir.interval-seconds` | `POLLING_FHIR_INTERVAL_SECONDS` | `30` | FHIR poll interval |
| `emitter.polling.fhir.page-size` | `POLLING_FHIR_PAGE_SIZE` | `50` | FHIR `_count` |
| `emitter.polling.fhir.overlap-seconds` | `POLLING_FHIR_OVERLAP_SECONDS` | `5` | Overlap window subtracted from the saved checkpoint when querying. Set to `0` when relying solely on the checkpoint `+1s` bump (recommended for OpenMRS fhir2). |
| `emitter.polling.fhir.follow-prior-order` | `POLLING_FHIR_FOLLOW_PRIOR_ORDER` | `true` | When a `ServiceRequest`/`MedicationRequest` revision is a discontinue tombstone (`status=stopped\|revoked\|cancelled\|entered-in-error`), skip the tombstone and re-fetch + forward the prior order. |
| `emitter.polling.fhir.resource-types` | `POLLING_FHIR_RESOURCE_TYPES` | `Patient,RelatedPerson,Encounter,Observation,Condition,ServiceRequest` | Comma-separated FHIR resource types to poll |

### Patient Reference Rewriting (national-id)

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.patient.national-id.enabled` | `PATIENT_NATIONAL_ID_ENABLED` | `true` | Enable rewriting `Patient/{uuid}` → `Patient/{national-id}` on outgoing resources |
| `emitter.patient.national-id.system` | `PATIENT_NATIONAL_ID_SYSTEM` | `http://moh.gov.rw/fhir/identifier/national-id` | FHIR identifier `system` for the national-id |
| `emitter.patient.national-id.code` | `PATIENT_NATIONAL_ID_CODE` | `national-id` | FHIR identifier `type.coding.code` for the national-id |
| `emitter.patient.national-id.cache-ttl-seconds` | `PATIENT_NATIONAL_ID_CACHE_TTL_SECONDS` | `3600` | UUID → national-id cache TTL |
| `emitter.patient.national-id.cache-max-size` | `PATIENT_NATIONAL_ID_CACHE_MAX_SIZE` | `10000` | Max cache entries |
| `emitter.patient.national-id.on-missing` | `PATIENT_NATIONAL_ID_ON_MISSING` | `skip` | Behavior when patient has no national-id: `skip`, `forward-as-is`, or `fail` |

### Referral-Response Classification

When enabled (default), `OrderIdentifierEnricher` classifies completed/revoked referral `ServiceRequest` resources so the Compliance Service can match them via standard FHIR PlanDefinition expressions. See [`architecture.md` § 10.4](architecture.md) for the full design rationale.

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.referral.response.enabled` | `REFERRAL_RESPONSE_ENABLED` | `true` | Enable referral-response classification on outgoing `ServiceRequest` |
| `emitter.referral.response.order-type-name` | `REFERRAL_RESPONSE_ORDER_TYPE_NAME` | `Referral` | OpenMRS `orderType.display` value to treat as a referral (case-insensitive) |
| `emitter.referral.response.category-system` | `REFERRAL_RESPONSE_CATEGORY_SYSTEM` | `http://cce.openphc.org/CodeSystem/event-type` | CodeSystem URL for the `category` coding added on response |
| `emitter.referral.response.category-code` | `REFERRAL_RESPONSE_CATEGORY_CODE` | `referral-response` | `category.coding.code` value — PlanDefinition `action.trigger.data.codeFilter` matches on this |
| `emitter.referral.response.category-display` | `REFERRAL_RESPONSE_CATEGORY_DISPLAY` | `Referral Response` | `category.coding.display` |
| `emitter.referral.response.based-on-identifier-system` | `REFERRAL_RESPONSE_BASED_ON_SYSTEM` | `http://mdtlabs.com/service-request-id` | Identifier `system` used in `basedOn[].identifier` to reference the placer's ServiceRequest id (sourced from the OpenMRS `accessionNumber`) |
| `emitter.referral.response.flip-intent` | `REFERRAL_RESPONSE_FLIP_INTENT` | `false` | Flip `intent` from `order` to `filler-order`. Enable only if the downstream Compliance Service / PlanDefinition expects fulfiller-stage intent |

Trigger condition: classification fires when **all** of the following are true — (1) resource is a `ServiceRequest`; (2) `enabled=true`; (3) OpenMRS `orderType.display` equals `order-type-name` (case-insensitive); (4) `status` is `completed` or `revoked`. `MedicationRequest` only receives ACSN enrichment, never referral-response classification.

### Output Target and Beckn

> **Status:** Implemented on branch `sygnal-actions` (package `org.openphc.cce.emitter.beckn`). Not yet tested against real onix containers. See [beckn-integration.md](beckn-integration.md) § 11 for the full YAML.
>
> Beckn mode uses two protocols. The **CCE events leg** sends each resource to the HIE as the Sygnal action `sygnal/event/report`, through OpenMRS's Sygnal onix (`onix-openmrs`): settings `emitter.beckn.sygnal.*`. The **Spice referral legs** are standard Beckn (`on_status`, `update`), as on `demo-beckn` except the box (a `sygnal:CoordinationEvent` since 2026-11-23): settings `emitter.beckn.onix.bap/bpp-caller-url`, `emitter.beckn.self.*`, `emitter.beckn.spice.*`.
>
> With `OPENMRS_EMITTER_TARGET=beckn` the emitter fails to start if a required setting is missing or wrong; the error names each one. Only the settings of the legs that are on are required. `sygnal.hie.uri` and the referral-leg ids have no defaults; `application-beckn-demo.yml` (profile `beckn-demo`) holds example values.

With `OPENMRS_EMITTER_TARGET=openhim` the emitter posts FHIR to OpenHIM as described above, and none of the `emitter.beckn.*` settings are read.

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.target` | `OPENMRS_EMITTER_TARGET` | `beckn` (on this branch) | `openhim` (today's path) or `beckn` |
| `emitter.beckn.onix.timeout` | `OPENMRS_EMITTER_BECKN_ONIX_TIMEOUT` | `50s` | Wait for one onix answer, every leg. The outermost hop: longer than every hop behind it ([beckn-integration.md](beckn-integration.md) § 10.4) |
| `emitter.beckn.schema-base-url` | `OPENMRS_EMITTER_BECKN_SCHEMA_BASE_URL` | `https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema` | Base URL of the schemas: `{base}/CoordinationEvent/v3.0/context.jsonld` is the `@context` of the `performanceAttributes` box of the Beckn referral legs (until 2026-11-23 `{base}/CceClinicalEvent/v1.0/context.jsonld`). The event of `sygnal/event/report` has no `@context` and does not use this setting. The files are in the cce-beckn-service `schema/` folder |
| **CCE events leg (Sygnal)** | | | |
| `emitter.beckn.sygnal.caller-url` | `OPENMRS_EMITTER_BECKN_SYGNAL_CALLER_URL` | `http://onix-openmrs:8082/sygnal/caller` | onix Sygnal caller. The emitter appends `/sygnal/event/report` |
| `emitter.beckn.sygnal.id` | `OPENMRS_EMITTER_BECKN_SYGNAL_ID` | `bap.openmrs.nextsteps.mdtlabs.org` | OpenMRS's Sygnal subscriber id: `context.senderId` and `from.id` of the event. Must match the keys in `onix-openmrs` |
| `emitter.beckn.sygnal.network-id` | `OPENMRS_EMITTER_BECKN_SYGNAL_NETWORK_ID` | `sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry` | Required. `context.networkId` |
| `emitter.beckn.sygnal.hie.id` | `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_ID` | `bap.openhim.nextsteps.mdtlabs.org` | The HIE's subscriber id: `context.receiverId` |
| `emitter.beckn.sygnal.hie.uri` | `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_URI` | — | Required. The HIE's public onix receiver base, e.g. `https://hie.example.org/sygnal/receiver`: `context.receiverUri` |
| `emitter.beckn.sygnal.hie.transaction-id` | `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_TRANSACTION_ID` | — | Optional fixed `context.transactionId` (a UUID). Empty: a new UUID per event |
| `emitter.beckn.legs.cce-events` | `OPENMRS_EMITTER_BECKN_LEG_CCE_EVENTS` | `true` | Send every resource to the HIE |
| **Spice referral legs (Beckn)** | | | |
| `emitter.beckn.onix.bap-caller-url` | `OPENMRS_EMITTER_BECKN_ONIX_BAP_CALLER_URL` | `http://onix-bap:8081/bap/caller` | `onix-bap` caller. Used for `update` to Spice |
| `emitter.beckn.onix.bpp-caller-url` | `OPENMRS_EMITTER_BECKN_ONIX_BPP_CALLER_URL` | `http://onix-bpp:8082/bpp/caller` | `onix-bpp` caller. Used for `on_status` to Spice |
| `emitter.beckn.self.network-id` | `OPENMRS_EMITTER_BECKN_SELF_NETWORK_ID` | — | Optional. Sent as `context.networkId` of the Beckn messages when set |
| `emitter.beckn.self.bap-id` / `bap-uri` | `OPENMRS_EMITTER_BECKN_SELF_BAP_ID` / `OPENMRS_EMITTER_BECKN_SELF_BAP_URI` | — | OpenMRS's consumer (BAP) id and receiver URI |
| `emitter.beckn.self.bpp-id` / `bpp-uri` | `OPENMRS_EMITTER_BECKN_SELF_BPP_ID` / `OPENMRS_EMITTER_BECKN_SELF_BPP_URI` | — | OpenMRS's provider (BPP) id and receiver URI |
| `emitter.beckn.spice.name` | `OPENMRS_EMITTER_BECKN_SPICE_NAME` | `Spice` | The one HMIS peer |
| `emitter.beckn.spice.bap-id` / `bap-uri` / `bpp-id` / `bpp-uri` | `OPENMRS_EMITTER_BECKN_SPICE_BAP_ID`, `…_BAP_URI`, `…_BPP_ID`, `…_BPP_URI` | — | The peer's Beckn ids and receiver URIs |
| `emitter.beckn.spice.facility-codes` | `OPENMRS_EMITTER_BECKN_SPICE_FACILITY_CODES` (comma-separated) | — | Destination facility codes the peer serves. A referral out goes to the peer only if its `performer` facility code is in this list |
| `emitter.beckn.spice.referrals-in.*` | `OPENMRS_EMITTER_BECKN_REFERRALS_IN_CONTRACT_ID`, `…_TRANSACTION_ID`, `…_COMMITMENT_FILE` | — | `contract-id`, `transaction-id`, `commitment-file` for `on_status` to the peer. Agreed with the peer; the receiver does not check incoming contract ids |
| `emitter.beckn.spice.referrals-out.*` | `OPENMRS_EMITTER_BECKN_REFERRALS_OUT_CONTRACT_ID`, `…_TRANSACTION_ID`, `…_COMMITMENT_FILE` | — | `contract-id`, `transaction-id`, `commitment-file` for `update` to the peer. Issued by the peer's admin |
| `emitter.beckn.legs.referrals-in` | `OPENMRS_EMITTER_BECKN_LEG_REFERRALS_IN` | `true` | Send referral outcomes to the peer |
| `emitter.beckn.legs.referrals-out` | `OPENMRS_EMITTER_BECKN_LEG_REFERRALS_OUT` | `true` | Send referrals out to the peer |

Beckn mode reuses `emitter.openhim.retry.max-attempts` and `emitter.openhim.retry.backoff-ms` on every leg, for 5xx answers and timeouts; a 4xx `Nack` is never retried. Raise them above `3` and `2000` in Beckn mode: a message is dropped after the last try. CCE advises a growing wait (1 min, 5 min, 30 min); these retries are still seconds apart (open question Q4). The other `emitter.openhim.*` settings are not used in Beckn mode.

The referral classifier reads the existing `emitter.referral.response.category-system`, `emitter.referral.direction.category-system` and `emitter.referral.direction.destination-identifier-system` settings. Do not change them without telling the HIE and Spice.

The Beckn "HIE feed" settings (`emitter.beckn.openhim.*`, `commitment-hie.json`) are removed: the CCE leg is Sygnal now.

### Checkpoint

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.checkpoint.store-type` | `CHECKPOINT_STORE_TYPE` | `file` | `file` (database planned as future enhancement) |
| `emitter.checkpoint.file-path` | `CHECKPOINT_FILE_PATH` | `./data/checkpoints.json` | File path (when `store-type=file`) |

---

## Example: Local Development

```bash
OPENMRS_BASE_URL=http://localhost:8080/openmrs
OPENMRS_AUTH_USERNAME=admin
OPENMRS_AUTH_PASSWORD=Admin123
OPENHIM_BASE_URL=http://localhost:5001/fhir
OPENHIM_AUTH_TYPE=none
LOG_LEVEL_APP=DEBUG
```

## Example: Production

```bash
OPENMRS_BASE_URL=http://openmrs:8080/openmrs
OPENMRS_AUTH_TYPE=basic
OPENMRS_AUTH_USERNAME=cce-emitter
OPENMRS_AUTH_PASSWORD=${SECRET_OPENMRS_PASSWORD}
OPENHIM_BASE_URL=https://openhim-api.cce.mdtlabs.org/fhir
OPENHIM_AUTH_TYPE=basic
OPENHIM_AUTH_USERNAME=openmrs-emitter-client
OPENHIM_AUTH_PASSWORD=${SECRET_OPENHIM_PASSWORD}
OPENHIM_SSL_TRUST_ALL=false
OPENHIM_RETRY_MAX_ATTEMPTS=5
CHECKPOINT_FILE_PATH=/app/data/checkpoints.json
LOG_LEVEL_ROOT=WARN
```

## Example: Production with OAuth2

```bash
OPENMRS_BASE_URL=http://openmrs:8080/openmrs
OPENMRS_AUTH_TYPE=oauth2
OPENMRS_AUTH_OAUTH2_TOKEN_URL=https://keycloak.example.org/realms/openmrs/protocol/openid-connect/token
OPENMRS_AUTH_OAUTH2_CLIENT_ID=cce-emitter-service
OPENMRS_AUTH_OAUTH2_CLIENT_SECRET=${SECRET_OAUTH2_CLIENT_SECRET}
OPENMRS_AUTH_OAUTH2_SCOPE=openid
OPENHIM_BASE_URL=https://openhim-api.cce.mdtlabs.org/fhir
OPENHIM_AUTH_TYPE=basic
OPENHIM_AUTH_USERNAME=openmrs-emitter-client
OPENHIM_AUTH_PASSWORD=${SECRET_OPENHIM_PASSWORD}
OPENHIM_SSL_TRUST_ALL=false
OPENHIM_RETRY_MAX_ATTEMPTS=5
CHECKPOINT_FILE_PATH=/app/data/checkpoints.json
LOG_LEVEL_ROOT=WARN
```

## Example: Beckn Mode

```bash
OPENMRS_EMITTER_TARGET=beckn
OPENMRS_EMITTER_BECKN_SCHEMA_BASE_URL=https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema
# CCE events leg (Sygnal)
OPENMRS_EMITTER_BECKN_SYGNAL_CALLER_URL=http://onix-openmrs:8082/sygnal/caller
OPENMRS_EMITTER_BECKN_SYGNAL_ID=bap.openmrs.nextsteps.mdtlabs.org
OPENMRS_EMITTER_BECKN_SYGNAL_NETWORK_ID=sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry
OPENMRS_EMITTER_BECKN_SYGNAL_HIE_ID=bap.openhim.nextsteps.mdtlabs.org
OPENMRS_EMITTER_BECKN_SYGNAL_HIE_URI=https://hie.example.org/sygnal/receiver
# Spice referral legs (Beckn)
OPENMRS_EMITTER_BECKN_ONIX_BAP_CALLER_URL=http://onix-bap:8081/bap/caller
OPENMRS_EMITTER_BECKN_ONIX_BPP_CALLER_URL=http://onix-bpp:8082/bpp/caller
OPENHIM_RETRY_MAX_ATTEMPTS=6
OPENHIM_RETRY_BACKOFF_MS=5000
```

The HIE host above is made up. The Beckn ids, contract ids and facility codes of the referral legs are set with the `OPENMRS_EMITTER_BECKN_*` variables in the table above, or in YAML (for example a profile file), as shown in [beckn-integration.md](beckn-integration.md) § 11. The `OPENMRS_EMITTER_` prefix keeps them apart from the receiver's (`OPENMRS_RECEIVER_`) and the OpenHIM emitter's (`OPENHIM_EMITTER_`) when all three share one env file.

---

## Checkpoint File Format

```json
{
  "checkpoints": {
    "Patient": {
      "lastUpdated": "2026-04-13T10:30:00.000+00:00",
      "lastPollTime": "2026-04-13T10:30:15.123+00:00"
    },
    "Encounter": {
      "lastUpdated": "2026-04-13T10:29:45.000+00:00",
      "lastPollTime": "2026-04-13T10:30:15.123+00:00"
    }
  }
}
```

All checkpoint keys use FHIR PascalCase resource names. In Docker, mount the checkpoint path as a volume.

---

## Checkpoint Lifecycle (Hybrid Approach)

The service uses a **hybrid checkpoint + sliding window seed** strategy to resolve the query time for each poll cycle.

| Scenario | Query Time | Behavior |
|----------|-----------|----------|
| **Checkpoint exists** (normal operation) | `checkpoint - overlapSeconds` | Polls resources modified since last checkpoint, minus overlap window for safety |
| **No checkpoint** (first boot or lost) | `now() - (intervalSeconds + overlapSeconds)` | Sliding window seed — polls only the recent window (default ~35s), avoids full-history re-poll |

- After each successful poll, the **maximum `_lastUpdated`** from the results, advanced by **+1 second**, is saved as the new checkpoint. The `+1s` bump works around the OpenMRS fhir2 quirk where `_lastUpdated=gt{T}` is inclusive at the second boundary (sub-seconds are truncated server-side), which would otherwise cause the same row to be re-emitted on every poll.
- The `overlap-seconds` (default `5`; set to `0` in the demo compose) is subtracted from the saved checkpoint at query time. With the `+1s` bump in place, an overlap of `0` is sufficient and avoids unnecessary duplicates.
- If the checkpoint file is deleted or corrupted, the service gracefully falls back to the sliding window seed — no manual intervention required.
- Deduplication by resource ID within each poll cycle handles any overlap-induced duplicates.
