package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.service.FhirPollerService;
import org.openphc.cce.emitter.service.ForwardResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Beckn mode end to end against stubbed onix callers (and a stubbed OpenMRS for the poller test):
 * the CCE events leg (Sygnal {@code sygnal/event/report}: 202 Ack, 400 Nack, 500-then-Ack retry with a
 * new messageId and the same event id) and the Spice referral legs (Beckn {@code on_status} /
 * {@code update}, as on demo-beckn).
 */
@SpringBootTest(properties = {
        "emitter.target=beckn",
        "emitter.polling.fhir.enabled=false",
        "emitter.patient.national-id.enabled=false",
        "emitter.openhim.retry.max-attempts=3",
        "emitter.openhim.retry.backoff-ms=1",
        "emitter.beckn.onix.timeout=2s",
        "emitter.beckn.sygnal.network-id=sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry",
        "emitter.beckn.sygnal.hie.uri=https://hie.example.org/sygnal/receiver",
        "emitter.beckn.self.bap-id=bap.openmrs.example.org",
        "emitter.beckn.self.bap-uri=https://openmrs.example.org/bap/receiver",
        "emitter.beckn.self.bpp-id=bpp.openmrs.example.org",
        "emitter.beckn.self.bpp-uri=https://openmrs.example.org/bpp/receiver",
        "emitter.beckn.spice.bap-id=bap.spice.example.org",
        "emitter.beckn.spice.bap-uri=https://spice.example.org/bap/receiver",
        "emitter.beckn.spice.bpp-id=bpp.spice.example.org",
        "emitter.beckn.spice.bpp-uri=https://spice.example.org/bpp/receiver",
        "emitter.beckn.spice.facility-codes=0417",
        "emitter.beckn.spice.referrals-in.contract-id=e2c9a7b4-6f1d-4b3a-9c8e-7d6f5a4b3c2d",
        "emitter.beckn.spice.referrals-in.transaction-id=a41f6b2c-9d3e-4a7b-8c1d-2e3f4a5b6c7d",
        "emitter.beckn.spice.referrals-out.contract-id=7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f",
        "emitter.beckn.spice.referrals-out.transaction-id=b52a7c3d-0e4f-4b8c-9d2e-3f4a5b6c7d8e"
})
class BecknModeIntegrationTest {

    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());
    private static final Path CHECKPOINTS;

    static {
        WM.start();
        try {
            CHECKPOINTS = Files.createTempDirectory("beckn-it").resolve("checkpoints.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The action is appended with its slashes as they are: no %2F. */
    private static final String REPORT = "/sygnal/caller/sygnal/event/report";
    /** The Beckn referral legs to Spice. */
    private static final String BAP = "/bap/caller/update";
    private static final String BPP = "/bpp/caller/on_status";
    private static final String ACK = "{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}";
    private static final String NACK = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\","
            + "\"error\":{\"code\":\"SCH_REQUIRED_FIELD_MISSING\",\"message\":\"events[0].id is missing\"}}}";
    private static final String UNAVAILABLE = "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\","
            + "\"error\":{\"code\":\"NET_DOWNSTREAM_UNAVAILABLE\",\"message\":\"try later\"}}}";

    private static final String ENCOUNTER = "{\"resourceType\":\"Encounter\",\"id\":\"7d2c9b1e\","
            + "\"meta\":{\"lastUpdated\":\"2026-11-13T08:00:12.000+00:00\"},"
            + "\"status\":\"finished\",\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";
    private static final String DIRECTION = "http://cce.openphc.org/CodeSystem/referral-direction";
    private static final String INBOUND_OUTCOME = "{\"resourceType\":\"ServiceRequest\",\"id\":\"0fe4c80e\",\"status\":\"completed\","
            + "\"intent\":\"order\",\"category\":[{\"coding\":[{\"system\":\"" + DIRECTION + "\",\"code\":\"inbound\"}]},"
            + "{\"coding\":[{\"system\":\"http://cce.openphc.org/CodeSystem/event-type\",\"code\":\"referral-response\"}]}],"
            + "\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";
    private static final String OUTBOUND_REFERRAL = "{\"resourceType\":\"ServiceRequest\",\"id\":\"a1b2\",\"status\":\"active\","
            + "\"intent\":\"order\",\"category\":[{\"coding\":[{\"system\":\"" + DIRECTION + "\",\"code\":\"outbound\"}]}],"
            + "\"performer\":[{\"reference\":\"Organization/0417\",\"identifier\":{\"system\":"
            + "\"http://cce.openphc.org/fhir/NamingSystem/facility-code\",\"value\":\"0417\"}}],"
            + "\"subject\":{\"reference\":\"Patient/1198503150001234\"}}";

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("emitter.beckn.sygnal.caller-url", () -> WM.baseUrl() + "/sygnal/caller");
        registry.add("emitter.beckn.onix.bap-caller-url", () -> WM.baseUrl() + "/bap/caller");
        registry.add("emitter.beckn.onix.bpp-caller-url", () -> WM.baseUrl() + "/bpp/caller/");
        registry.add("emitter.openmrs.base-url", () -> WM.baseUrl() + "/openmrs");
        registry.add("emitter.openhim.base-url", () -> WM.baseUrl() + "/fhir");
        registry.add("emitter.checkpoint.file-path", CHECKPOINTS::toString);
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @Autowired
    private BecknTargetForwarder forwarder;
    @Autowired
    private EventIdFactory eventIdFactory;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private FhirPollerService poller;

    @BeforeEach
    void reset() {
        WM.resetAll();
    }

    private static List<JsonNode> bodies() {
        return bodies(REPORT);
    }

    private static List<JsonNode> bodies(String path) {
        return WM.findAll(postRequestedFor(urlEqualTo(path))).stream()
                .map(LoggedRequest::getBodyAsString).map(BecknJson::parse).toList();
    }

    private static void noOtherCalls() {
        assertTrue(WM.getAllServeEvents().stream().allMatch(e -> REPORT.equals(e.getRequest().getUrl())),
                () -> WM.getAllServeEvents().stream().map(e -> e.getRequest().getUrl()).toList().toString());
        assertTrue(WM.getAllServeEvents().stream().noneMatch(e -> e.getRequest().getUrl().contains("%2F")));
    }

    private double count(String metric, String... tags) {
        var counter = meterRegistry.find(metric).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void accepted202AckIsSuccessAndTheBodyIsAnEventReport() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody(ACK)));
        double before = count(OnixClient.METRIC_SENT, "leg", "cce-events", "action", "sygnal/event/report",
                "outcome", "ack");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertTrue(result.isSuccess());
        noOtherCalls();
        List<JsonNode> sent = bodies();
        assertEquals(1, sent.size());
        JsonNode body = sent.get(0);

        JsonNode ctx = body.get("context");
        assertEquals("2.0.0", ctx.get("version").asText());
        assertEquals("sygnal/event/report", ctx.get("action").asText());
        assertEquals("sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry", ctx.get("networkId").asText());
        assertEquals("bap.openmrs.nextsteps.mdtlabs.org", ctx.get("senderId").asText());
        assertEquals("bap.openhim.nextsteps.mdtlabs.org", ctx.get("receiverId").asText());
        assertEquals("https://hie.example.org/sygnal/receiver", ctx.get("receiverUri").asText());
        UUID.fromString(ctx.get("transactionId").asText());
        UUID.fromString(ctx.get("messageId").asText());
        assertEquals(9, ctx.size());
        ctx.fieldNames().forEachRemaining(name -> assertFalse(name.startsWith("bap") || name.startsWith("bpp"), name));

        JsonNode message = body.get("message");
        assertEquals(1, message.size());
        assertEquals(1, message.get("events").size());
        JsonNode event = message.at("/events/0");
        // the extended sygnal:CoordinationEvent (profile v3.0): exactly these keys, in this order;
        // no cce: type, no attribute bag, no @context
        List<String> eventFields = new ArrayList<>();
        event.fieldNames().forEachRemaining(eventFields::add);
        assertEquals(List.of("@type", "id", "revision", "eventType", "granularity", "from", "occurredAt",
                "patientId", "fhirVersion", "sourceEventId", "resource"), eventFields);
        assertEquals("sygnal:CoordinationEvent", event.get("@type").asText());
        assertEquals(eventIdFactory.eventId(BecknJson.parse(ENCOUNTER)), event.get("id").asText());
        assertEquals(1, event.get("revision").asInt());
        assertEquals("DELIVERY_RECORDED", event.get("eventType").asText());
        assertEquals("INDIVIDUAL", event.get("granularity").asText());
        assertEquals(2, event.get("from").size());
        assertEquals(ctx.get("senderId").asText(), event.at("/from/id").asText());
        assertEquals("PROVIDER", event.at("/from/role").asText());
        assertEquals("2026-11-13T08:00:12Z", event.get("occurredAt").asText());
        assertEquals("1198503150001234", event.get("patientId").asText());
        assertEquals("4.0.1", event.get("fhirVersion").asText());
        assertEquals("Encounter/7d2c9b1e", event.get("sourceEventId").asText());
        assertEquals(BecknJson.parse(ENCOUNTER), event.get("resource"));
        assertFalse(body.toString().contains("cce:"), "no cce: type in the message");
        assertFalse(event.has("eventAttributes"), "no attribute bag");

        assertEquals(before + 1, count(OnixClient.METRIC_SENT, "leg", "cce-events", "action", "sygnal/event/report",
                "outcome", "ack"));
    }

    @Test
    void nack400FailsWithoutRetry() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(NACK)));
        double before = count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "nack");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertFalse(result.isSuccess());
        assertEquals(400, result.statusCode());
        assertEquals(1, bodies().size());
        assertEquals(before + 1, count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "nack"));
    }

    @Test
    void serverErrorThenAckIsRetriedWithNewMessageIdAndSameEventAndTransaction() {
        WM.stubFor(post(urlEqualTo(REPORT)).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody(UNAVAILABLE))
                .willSetStateTo("second"));
        WM.stubFor(post(urlEqualTo(REPORT)).inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(ACK)));
        double retriesBefore = count(OnixClient.METRIC_RETRIES, "leg", "cce-events", "action", "sygnal/event/report");

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertTrue(result.isSuccess());
        List<JsonNode> sent = bodies();
        assertEquals(2, sent.size());
        assertNotEquals(sent.get(0).at("/context/messageId").asText(), sent.get(1).at("/context/messageId").asText());
        assertEquals(sent.get(0).at("/context/transactionId"), sent.get(1).at("/context/transactionId"));
        assertEquals(sent.get(0).at("/message/events/0/id").asText(), sent.get(1).at("/message/events/0/id").asText());
        assertEquals(retriesBefore + 1, count(OnixClient.METRIC_RETRIES, "leg", "cce-events", "action", "sygnal/event/report"));
    }

    @Test
    void noAnswerIsRetriedThenDropped() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ForwardResult result = forwarder.forward(ENCOUNTER, "Encounter");

        assertFalse(result.isSuccess());
        assertEquals(3, bodies().size());
        assertTrue(count(OnixClient.METRIC_DROPPED, "leg", "cce-events", "reason", "retries-exhausted") >= 1);
    }

    @Test
    void eventsGetTheirOwnTransactionIdWhenNoneIsSet() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));

        forwarder.forward(ENCOUNTER, "Encounter");
        forwarder.forward(ENCOUNTER.replace("7d2c9b1e", "8e3d0c2f"), "Encounter");

        List<JsonNode> sent = bodies();
        assertEquals(2, sent.size());
        assertNotEquals(sent.get(0).at("/context/transactionId"), sent.get(1).at("/context/transactionId"));
    }

    @Test
    void inboundReferralOutcomeGoesToHieAsEventReportAndToPeerAsBecknOnStatus() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));
        WM.stubFor(post(urlEqualTo(BPP)).willReturn(okJson(ACK)));

        assertTrue(forwarder.forward(INBOUND_OUTCOME, "ServiceRequest").isSuccess());

        JsonNode hie = bodies().get(0);
        assertEquals("sygnal/event/report", hie.at("/context/action").asText());
        List<JsonNode> onStatus = bodies(BPP);
        assertEquals(1, onStatus.size());
        JsonNode peer = onStatus.get(0);
        assertEquals("on_status", peer.at("/context/action").asText());
        assertEquals("bap.spice.example.org", peer.at("/context/bapId").asText());
        assertEquals("bpp.openmrs.example.org", peer.at("/context/bppId").asText());
        assertEquals("e2c9a7b4-6f1d-4b3a-9c8e-7d6f5a4b3c2d", peer.at("/message/contract/id").asText());
        assertEquals("COMPLETED", peer.at("/message/contract/performance/0/status/code").asText());
        assertEquals(hie.at("/message/events/0/id"), peer.at("/message/contract/performance/0/id"));
    }

    @Test
    void outboundReferralToServedFacilityGoesToHieAndAsBecknUpdateToPeer() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));
        WM.stubFor(post(urlEqualTo(BAP)).willReturn(okJson(ACK)));
        double sentBefore = count(BecknTargetForwarder.METRIC_REFERRALS_OUT, "outcome", "sent");

        assertTrue(forwarder.forward(OUTBOUND_REFERRAL, "ServiceRequest").isSuccess());

        assertEquals(1, bodies().size());
        List<JsonNode> update = bodies(BAP);
        assertEquals(1, update.size());
        assertEquals("update", update.get(0).at("/context/action").asText());
        assertEquals("bap.openmrs.example.org", update.get(0).at("/context/bapId").asText());
        assertEquals("bpp.spice.example.org", update.get(0).at("/context/bppId").asText());
        assertEquals("7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f", update.get(0).at("/message/contract/id").asText());
        assertEquals(BecknJson.parse(OUTBOUND_REFERRAL),
                update.get(0).at("/message/contract/performance/0/performanceAttributes/resource"));
        assertEquals(0, bodies(BPP).size());
        assertEquals(sentBefore + 1, count(BecknTargetForwarder.METRIC_REFERRALS_OUT, "outcome", "sent"));
    }

    @Test
    void hieNackDoesNotStopThePeerMessage() {
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(NACK)));
        WM.stubFor(post(urlEqualTo(BPP)).willReturn(okJson(ACK)));

        ForwardResult result = forwarder.forward(INBOUND_OUTCOME, "ServiceRequest");

        assertFalse(result.isSuccess());
        assertEquals(1, bodies().size());
        assertEquals(1, bodies(BPP).size());
    }

    @Test
    void resourceWithoutPatientIsSkipped() {
        ForwardResult result = forwarder.forward("{\"resourceType\":\"Location\",\"id\":\"l1\"}", "Location");

        assertFalse(result.isSuccess());
        assertTrue(result.status().startsWith("skipped"));
        assertEquals(0, WM.getAllServeEvents().size());
        assertTrue(count(BecknTargetForwarder.METRIC_SKIPPED, "resourceType", "Location", "reason", "no-patient") >= 1);
    }

    @Test
    void pollerSendsToOnixAndNotToOpenhim() {
        String bundle = "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{\"resource\":"
                + "{\"resourceType\":\"Encounter\",\"id\":\"enc-1\",\"meta\":{\"lastUpdated\":\"2026-11-13T08:00:12.000+00:00\"},"
                + "\"status\":\"finished\",\"subject\":{\"reference\":\"Patient/1198503150001234\"}}}]}";
        WM.stubFor(get(urlPathEqualTo("/openmrs/ws/fhir2/R4/Encounter")).willReturn(okJson(bundle)));
        WM.stubFor(post(urlEqualTo(REPORT)).willReturn(okJson(ACK)));

        ReflectionTestUtils.invokeMethod(poller, "pollResourceType", "Encounter");

        List<JsonNode> sent = bodies();
        assertEquals(1, sent.size());
        assertEquals("Encounter/enc-1", sent.get(0).at("/message/events/0/sourceEventId").asText());
        assertEquals("2026-11-13T08:00:12Z", sent.get(0).at("/message/events/0/occurredAt").asText());
        WM.verify(0, postRequestedFor(urlMatching("/fhir.*")));
    }

    @Test
    void healthChecksTheOnixCallersNotOpenhim() {
        assertFalse(context.containsBean("openhim"));
        HealthIndicator onix = context.getBean("onix", HealthIndicator.class);
        assertEquals(Status.UP, onix.health().getStatus());
        WM.verify(1, getRequestedFor(urlEqualTo(REPORT)));
        WM.verify(1, getRequestedFor(urlEqualTo(BAP)));
        WM.verify(1, getRequestedFor(urlEqualTo(BPP)));
    }
}
