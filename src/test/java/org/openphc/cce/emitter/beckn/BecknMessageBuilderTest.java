package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Message shapes against the samples in docs/beckn-integration.md § 9: the Sygnal event report (CCE
 * Sygnal contract, profile v3.0, with the extended {@code sygnal:CoordinationEvent} as the event) and
 * the Beckn referral messages to Spice (unchanged from demo-beckn).
 */
class BecknMessageBuilderTest {

    private static final String ENCOUNTER = "{\"resourceType\":\"Encounter\",\"id\":\"e\","
            + "\"subject\":{\"reference\":\"Patient/p\"}}";

    private static JsonNode sample(String name) throws Exception {
        try (InputStream in = BecknMessageBuilderTest.class.getResourceAsStream("/beckn/" + name)) {
            return BecknJson.MAPPER.readTree(in);
        }
    }

    private static JsonNode sample() throws Exception {
        return sample("sample-event-report.json");
    }

    private static BecknMessageBuilder builder(EmitterProperties properties) {
        return new BecknMessageBuilder(properties, new DefaultResourceLoader());
    }

    /** Copies the per-attempt fields (messageId, timestamp) from the sample, after checking their form. */
    private static ObjectNode normalise(ObjectNode built, JsonNode sample) {
        ObjectNode context = (ObjectNode) built.get("context");
        UUID.fromString(context.get("messageId").asText());
        Instant.parse(context.get("timestamp").asText());
        context.set("messageId", sample.path("context").get("messageId"));
        context.set("timestamp", sample.path("context").get("timestamp"));
        return built;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The keys in the order they are written. */
    private static List<String> orderedFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void eventReportMatchesSample() throws Exception {
        JsonNode sample = sample();
        JsonNode resource = sample.at("/message/events/0/resource");
        String eventId = sample.at("/message/events/0/id").asText();

        OutgoingMessage msg = builder(BecknTestSettings.properties()).eventReport(resource, eventId, "1198503150001234");

        assertEquals(Leg.CCE_EVENTS, msg.leg());
        assertEquals("sygnal/event/report", msg.action());
        assertEquals("http://onix-openmrs:8082/sygnal/caller/sygnal/event/report", msg.url());
        assertEquals(BecknTestSettings.TRANSACTION_ID, msg.transactionId());
        ObjectNode built = normalise(msg.forAttempt(), sample);
        assertEquals(sample, built);
        // JsonNode equality ignores the order of the keys: the order is part of the contract too
        assertEquals(orderedFieldNames(sample.at("/message/events/0")), orderedFieldNames(built.at("/message/events/0")));
    }

    @Test
    void contextIsTheClosedSygnalContextAndTheEventIsTheExtendedCoordinationEvent() {
        ObjectNode body = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse(ENCOUNTER), "ev", "p").forAttempt();

        assertEquals(Set.of("context", "message"), fieldNames(body));
        assertEquals(new TreeSet<>(List.of("version", "action", "networkId", "senderId", "receiverId", "receiverUri",
                "transactionId", "messageId", "timestamp")), fieldNames(body.get("context")));
        assertEquals(Set.of("events"), fieldNames(body.get("message")));
        assertEquals(1, body.at("/message/events").size());
        JsonNode event = body.at("/message/events/0");
        // exactly these keys, in this order, none of them null
        assertEquals(List.of("@type", "id", "revision", "eventType", "granularity", "from", "occurredAt",
                "patientId", "fhirVersion", "sourceEventId", "resource"), orderedFieldNames(event));
        event.fields().forEachRemaining(field -> assertFalse(field.getValue().isNull(), field.getKey()));
        assertEquals("sygnal:CoordinationEvent", event.get("@type").asText());
        assertEquals("ev", event.get("id").asText());
        assertTrue(event.get("revision").isInt());
        assertEquals(1, event.get("revision").asInt());
        assertEquals("DELIVERY_RECORDED", event.get("eventType").asText());
        assertEquals("INDIVIDUAL", event.get("granularity").asText());
        assertEquals(List.of("id", "role"), orderedFieldNames(event.get("from")));
        assertEquals(body.at("/context/senderId").asText(), event.at("/from/id").asText());
        assertEquals("bap.openmrs.nextsteps.mdtlabs.org", event.at("/from/id").asText());
        assertEquals("PROVIDER", event.at("/from/role").asText());
        Instant.parse(event.get("occurredAt").asText());
        assertEquals("p", event.get("patientId").asText());
        assertEquals("4.0.1", event.get("fhirVersion").asText());
        assertEquals("Encounter/e", event.get("sourceEventId").asText());
        assertEquals(BecknJson.parse(ENCOUNTER), event.get("resource"));
        String text = BecknJson.write(body);
        for (String gone : new String[]{"bapId", "bppId", "bapUri", "bppUri", "contract", "commitments", "performance",
                "@context", "eventAttributes", "performanceAttributes"}) {
            assertFalse(text.contains("\"" + gone + "\""), gone);
        }
        assertFalse(text.contains("cce:"), "no cce: type in the message");
        assertFalse(text.contains("CceClinicalEvent"), "no cce: type in the message");
    }

    @Test
    void sourceEventIdIsLeftOutNotNullWhenTheResourceHasNoId() {
        JsonNode event = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse("{\"resourceType\":\"Encounter\",\"subject\":{\"reference\":\"Patient/p\"}}"),
                        "ev", "p")
                .forAttempt().at("/message/events/0");

        assertEquals(List.of("@type", "id", "revision", "eventType", "granularity", "from", "occurredAt",
                "patientId", "fhirVersion", "resource"), orderedFieldNames(event));
    }

    @Test
    void networkIdAndSenderIdAndCallerUrlComeFromSettingsAndTheSchemaBaseUrlIsNotInTheMessage() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSygnal().setNetworkId("openphc-demo");
        properties.getBeckn().getSygnal().setId("openmrs.example.org");
        properties.getBeckn().setSchemaBaseUrl("https://schemas.example.org/base/");
        properties.getBeckn().getSygnal().setCallerUrl("http://onix:9000/sygnal/caller/");
        OutgoingMessage msg = builder(properties).eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        ObjectNode body = msg.forAttempt();

        assertEquals("http://onix:9000/sygnal/caller/sygnal/event/report", msg.url());
        assertEquals("openphc-demo", body.at("/context/networkId").asText());
        assertEquals("openmrs.example.org", body.at("/context/senderId").asText());
        assertEquals("openmrs.example.org", body.at("/message/events/0/from/id").asText());
        assertFalse(BecknJson.write(body).contains("schemas.example.org"), "the event has no @context");
    }

    @Test
    void withoutFixedTransactionIdEachEventGetsItsOwnUuidKeptAcrossAttempts() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSygnal().getHie().setTransactionId("");
        BecknMessageBuilder builder = builder(properties);

        OutgoingMessage first = builder.eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        OutgoingMessage second = builder.eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");

        String tx = first.forAttempt().at("/context/transactionId").asText();
        UUID.fromString(tx);
        assertEquals(tx, first.forAttempt().at("/context/transactionId").asText());
        assertEquals(tx, first.transactionId());
        assertNotEquals(tx, second.forAttempt().at("/context/transactionId").asText());
    }

    @Test
    void eachAttemptHasNewMessageIdAndSameEventId() {
        OutgoingMessage msg = builder(BecknTestSettings.properties())
                .eventReport(BecknJson.parse(ENCOUNTER), "ev", "p");
        ObjectNode first = msg.forAttempt();
        ObjectNode second = msg.forAttempt();
        assertNotEquals(first.at("/context/messageId"), second.at("/context/messageId"));
        assertEquals("ev", first.at("/message/events/0/id").asText());
        assertEquals("ev", second.at("/message/events/0/id").asText());
        assertEquals(first.at("/message/events/0/occurredAt"), second.at("/message/events/0/occurredAt"));
    }

    @Test
    void occurredAtIsLastUpdatedAsUtcInstant() {
        JsonNode resource = BecknJson.parse("{\"resourceType\":\"Observation\",\"id\":\"o\","
                + "\"meta\":{\"lastUpdated\":\"2026-11-13T10:00:12.345+02:00\"}}");
        assertEquals("2026-11-13T08:00:12.345Z", BecknMessageBuilder.occurredAt(resource));
    }

    @Test
    void occurredAtFallsBackToNowWithoutAFullLastUpdated() {
        List<JsonNode> resources = new ArrayList<>();
        resources.add(BecknJson.parse(ENCOUNTER));
        resources.add(BecknJson.parse("{\"resourceType\":\"Encounter\",\"meta\":{\"lastUpdated\":\"2026-11-13\"}}"));
        for (JsonNode resource : resources) {
            Instant occurredAt = Instant.parse(BecknMessageBuilder.occurredAt(resource));
            assertTrue(Duration.between(occurredAt, Instant.now()).abs().toSeconds() < 60, occurredAt.toString());
        }
    }

    // ---- Spice referral legs (standard Beckn, as on demo-beckn) ----

    @Test
    void onStatusMatchesSample92() throws Exception {
        JsonNode sample = sample("sample-on-status.json");
        JsonNode resource = sample.at("/message/contract/performance/0/performanceAttributes/resource");
        String eventId = sample.at("/message/contract/performance/0/id").asText();

        OutgoingMessage msg = builder(BecknTestSettings.properties())
                .peerOnStatus(resource, eventId, "1198503150001234", "COMPLETED");

        assertEquals(Leg.REFERRALS_IN, msg.leg());
        assertEquals("http://onix-bpp:8082/bpp/caller/on_status", msg.url());
        ObjectNode built = normalise(msg.forAttempt(), sample);
        assertEquals(sample, built);
        // the box is the CoordinationEvent of the Sygnal report, with @context first: same keys, same order
        assertEquals(orderedFieldNames(sample.at("/message/contract/performance/0/performanceAttributes")),
                orderedFieldNames(built.at("/message/contract/performance/0/performanceAttributes")));
    }

    @Test
    void referralOutUpdateUsesReferralsOutContract() throws Exception {
        JsonNode resource = BecknJson.parse("{\"resourceType\":\"ServiceRequest\",\"id\":\"sr9\",\"status\":\"active\","
                + "\"subject\":{\"reference\":\"Patient/119\"}}");
        OutgoingMessage msg = builder(BecknTestSettings.properties()).peerReferralUpdate(resource, "ev-1", "119");
        ObjectNode body = msg.forAttempt();

        assertEquals(Leg.REFERRALS_OUT, msg.leg());
        assertEquals("http://onix-bap:8081/bap/caller/update", msg.url());
        assertEquals("update", body.at("/context/action").asText());
        assertEquals("bap.openmrs.example.org", body.at("/context/bapId").asText());
        assertEquals("bpp.spice.example.org", body.at("/context/bppId").asText());
        assertEquals("https://spice.example.org/bpp/receiver", body.at("/context/bppUri").asText());
        assertEquals("b52a7c3d-0e4f-4b8c-9d2e-3f4a5b6c7d8e", body.at("/context/transactionId").asText());
        assertEquals("7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f", body.at("/message/contract/id").asText());
        assertEquals("referral", body.at("/message/contract/commitments/0/id").asText());
        assertEquals(1, body.at("/message/contract/performance").size());
        assertEquals("ev-1", body.at("/message/contract/performance/0/id").asText());
        JsonNode box = body.at("/message/contract/performance/0/performanceAttributes");
        assertEquals("https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CoordinationEvent/v3.0/context.jsonld",
                box.get("@context").asText());
        assertEquals("sygnal:CoordinationEvent", box.get("@type").asText());
        assertEquals("ev-1", box.get("id").asText());
        assertEquals(1, box.get("revision").asInt());
        assertEquals("DELIVERY_RECORDED", box.get("eventType").asText());
        assertEquals("bap.openmrs.example.org", box.at("/from/id").asText(), "from.id is the sender, context.bapId");
        assertEquals("PROVIDER", box.at("/from/role").asText());
        Instant.parse(box.get("occurredAt").asText());
        assertEquals("119", box.get("patientId").asText());
        assertEquals("4.0.1", box.get("fhirVersion").asText());
        assertEquals("ServiceRequest/sr9", box.get("sourceEventId").asText());
        assertEquals(resource, box.get("resource"));
        assertFalse(BecknJson.write(body).contains("CceClinicalEvent"), "no cce: type in the box");
    }

    @Test
    void referralBoxIsTheEventOfTheSygnalReportWithAContext() {
        JsonNode resource = BecknJson.parse(ENCOUNTER);
        BecknMessageBuilder builder = builder(BecknTestSettings.properties());
        ObjectNode event = (ObjectNode) builder.eventReport(resource, "ev", "p").forAttempt().at("/message/events/0");
        ObjectNode box = (ObjectNode) builder.peerReferralUpdate(resource, "ev", "p").forAttempt()
                .at("/message/contract/performance/0/performanceAttributes");

        assertEquals("@context", orderedFieldNames(box).get(0));
        box.remove("@context");
        assertEquals(orderedFieldNames(event), orderedFieldNames(box));
        for (String field : new String[]{"@type", "id", "revision", "eventType", "granularity", "patientId",
                "fhirVersion", "sourceEventId", "resource"}) {
            assertEquals(event.get(field), box.get(field), field);
        }
    }

    @Test
    void referralNetworkIdIsSelfNetworkIdAndLeftOutWhenNotSet() {
        JsonNode resource = BecknJson.parse(ENCOUNTER);
        EmitterProperties properties = BecknTestSettings.properties();
        assertFalse(builder(properties).peerReferralUpdate(resource, "ev", "p").forAttempt().get("context").has("networkId"));

        properties.getBeckn().getSelf().setNetworkId("openphc-demo");
        assertEquals("openphc-demo",
                builder(properties).peerReferralUpdate(resource, "ev", "p").forAttempt().at("/context/networkId").asText());
    }

    @Test
    void missingCommitmentFileFailsAtStart() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSpice().getReferralsIn().setCommitmentFile("classpath:beckn/does-not-exist.json");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder(properties));
        assertTrue(e.getMessage().contains("emitter.beckn.spice.referrals-in.commitment-file"));
    }
}
