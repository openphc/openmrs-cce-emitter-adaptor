# Deployment Guide — OpenMRS CCE Emitter Adaptor

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21 LTS |
| Gradle | 8.x (wrapper included) |
| Docker | 24.x+ (for containerized deployment) |
| OpenMRS | 2.5+ with `fhir2` and `webservices.rest` modules |
| OpenHIM | 8.x+ |

---

## Build

```bash
./gradlew clean build          # with tests
./gradlew clean build -x test  # skip tests
docker build -t openmrs-cce-emitter-adaptor:latest .
```

---

## Run Locally

```bash
./gradlew bootRun --args='--spring.profiles.active=local'

# Verify
curl -s http://localhost:8080/actuator/health | jq
```

---

## Docker Compose

```yaml
services:
  openmrs-cce-emitter-adaptor:
    build: .
    image: openmrs-cce-emitter-adaptor:latest
    container_name: openmrs-cce-emitter-adaptor
    ports:
      - "8080:8080"
    restart: unless-stopped
    volumes:
      - emitter-data:/app/data
    environment:
      SERVER_PORT: 8080
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx512m -XX:+UseG1GC"
      OPENMRS_BASE_URL: http://openmrs:8080/openmrs
      OPENMRS_AUTH_TYPE: basic
      OPENMRS_AUTH_USERNAME: admin
      OPENMRS_AUTH_PASSWORD: Admin123
      OPENHIM_BASE_URL: https://openhim-api.cce.mdtlabs.org/fhir
      OPENHIM_AUTH_TYPE: basic
      OPENHIM_AUTH_USERNAME: openmrs-emitter-client
      OPENHIM_AUTH_PASSWORD: password
      OPENHIM_SSL_TRUST_ALL: "true"
      CHECKPOINT_FILE_PATH: /app/data/checkpoints.json
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  emitter-data:
```

---

## Beckn Mode

> **Status:** Implemented on branch `sygnal-actions` (package `org.openphc.cce.emitter.beckn`). Not yet tested against real onix containers. See [beckn-integration.md](beckn-integration.md).

With `OPENMRS_EMITTER_TARGET=openhim` the deployment above is all that is needed. On this branch the default is `beckn`, so set `OPENMRS_EMITTER_TARGET=openhim` explicitly to deploy without Beckn. Beckn mode adds the onix adapter containers and their Redis, as an optional group: OpenMRS's Sygnal onix (`onix-openmrs`) for the CCE events leg, and the two Beckn `openmrs-onix` containers (`onix-bap`, `onix-bpp`, onix 1.9.4) for the Spice referral legs. Since 2026-11-23 their schema set is `CoordinationEvent/v3.0` plus the Health* pack, in place of `CceClinicalEvent/v1.0`.

| Container | Role | Port on `cce-net` | Host port on the dev host |
|-----------|------|-------------------|---------------------------|
| `openmrs-cce-emitter-adaptor` | Emitter | `8085` | `8099` |
| `onix-openmrs` | Sygnal: CCE events leg. Caller `/sygnal/caller/` (sends `sygnal/event/report`), receiver `/sygnal/receiver/` (public) | `8082` | a free port, e.g. `8282` |
| `onix-bap` | Beckn: OpenMRS as consumer (referrals out). Caller `/bap/caller` | `8081` | `8181` |
| `onix-bpp` | Beckn: OpenMRS as provider (referrals in). Caller `/bpp/caller` | `8082` | `8182` |
| Redis for onix | Adapter's short-lived data | — | not published |

- All of them join the `cce-net` Docker network. The emitter calls the onix containers by name, on their own ports.
- On the dev host, host ports `8081` (`keycloak`) and `8082` (`cce-openhim-core`) are taken. Publish `onix-bap` and `onix-bpp` on `8181` and `8182`, and `onix-openmrs` on another free one (e.g. `8282`). Inside `cce-net` they keep their own ports.
- The receiver routings of the onix containers point at the receiver adaptor, not at the emitter.
- The HIE's onix receiver (`https://<hie-host>/sygnal/receiver`) must be reachable from `onix-openmrs`; it is the emitter's `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_URI`.

Extra emitter environment:

```yaml
    environment:
      OPENMRS_EMITTER_TARGET: beckn
      OPENMRS_EMITTER_BECKN_SCHEMA_BASE_URL: https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema
      # CCE events leg (Sygnal)
      OPENMRS_EMITTER_BECKN_SYGNAL_CALLER_URL: http://onix-openmrs:8082/sygnal/caller
      OPENMRS_EMITTER_BECKN_SYGNAL_ID: bap.openmrs.nextsteps.mdtlabs.org
      OPENMRS_EMITTER_BECKN_SYGNAL_NETWORK_ID: sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry
      OPENMRS_EMITTER_BECKN_SYGNAL_HIE_ID: bap.openhim.nextsteps.mdtlabs.org
      OPENMRS_EMITTER_BECKN_SYGNAL_HIE_URI: https://<hie-host>/sygnal/receiver
      # Spice referral legs (Beckn)
      OPENMRS_EMITTER_BECKN_ONIX_BAP_CALLER_URL: http://onix-bap:8081/bap/caller
      OPENMRS_EMITTER_BECKN_ONIX_BPP_CALLER_URL: http://onix-bpp:8082/bpp/caller
      OPENHIM_RETRY_MAX_ATTEMPTS: "6"
    networks:
      - cce-net
```

The Beckn ids, contract ids and facility codes of the referral legs go in YAML or `OPENMRS_EMITTER_BECKN_*` variables ([configuration-guide.md](configuration-guide.md)).

### Beckn Mode Checklist

- [ ] `onix-openmrs`, `onix-bap` and `onix-bpp` are running on `cce-net` and reachable from the emitter
- [ ] `onix-openmrs` holds OpenMRS's keys for subscriber id `bap.openmrs.nextsteps.mdtlabs.org` (= `OPENMRS_EMITTER_BECKN_SYGNAL_ID`), and the CCE Sygnal profile v3.0: the two files `cce-sygnal-apis.yaml` (its spec) and `cce-fhir-extension.yaml`, in one folder (`schema/sygnal-profile/v3.0/` of the cce-beckn-service repository). It needs no payload schema: no object of the Sygnal messages carries `@context`
- [ ] `onix-bap` and `onix-bpp` have OpenMRS's own Beckn ids and keys, and the schema set `CoordinationEvent/v3.0` (from `schema/CoordinationEvent/v3.0/` of the cce-beckn-service repository) plus the Health* pack. onix finds the box's schema by its `@type`: `sygnal:CoordinationEvent` → `CoordinationEvent/attributes.yaml` in its local schema folder
- [ ] Spice's referral adapters and messages use the `sygnal:CoordinationEvent` box too (changed on 2026-11-23, at the same time). An adapter without the old schema refuses the old `cce:CceClinicalEvent` box (`SCH_SCHEMA_ADAPTATION_FAILED`)
- [ ] `OPENMRS_EMITTER_BECKN_SYGNAL_HIE_URI` is set (the emitter does not start in Beckn mode without it)
- [ ] `responseHeaderTimeout` on `onix-openmrs` and `onix-bap` is raised from the 5 s default to at least 45 s, and the emitter's `emitter.beckn.onix.timeout` is 50 s ([beckn-integration.md](beckn-integration.md) § 10.4)
- [ ] The "referrals in" and "referrals out" ids are agreed with Spice and set in `emitter.beckn.spice`
- [ ] Spice's facility codes are set in `emitter.beckn.spice.facility-codes`
- [ ] `OPENMRS_EMITTER_TARGET=beckn` is set, and the emitter is restarted
- [ ] The old Beckn HIE-feed variables (`OPENMRS_EMITTER_BECKN_OPENHIM_*`) are removed from the env file (they are ignored)

---

## Resource Allocation

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 0.5 vCPU | 1 vCPU |
| Memory | 256 MB | 512 MB |
| Disk | 10 MB | 50 MB |

---

## Deployment Checklist

- [ ] OpenMRS instance is reachable from the emitter
- [ ] OpenMRS user credentials have sufficient privileges for FHIR + REST APIs
- [ ] OpenHIM channel is configured for the emitter client
- [ ] Checkpoint file volume is mounted and writable
- [ ] Environment variables for credentials are set (not hardcoded)
- [ ] `OPENHIM_SSL_TRUST_ALL` is `false` in production
- [ ] Health check endpoint responds: `GET /actuator/health`
- [ ] Prometheus scraping configured for `/actuator/prometheus`
