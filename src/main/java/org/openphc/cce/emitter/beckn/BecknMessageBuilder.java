package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;
import org.openphc.cce.emitter.config.EmitterProperties.BecknContractConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Builds the messages of the emitter (docs/beckn-integration.md § 8–9), from the settings:
 *
 * <ul>
 *   <li>the CCE events leg: the Sygnal action {@code sygnal/event/report} to the HIE, with one
 *       {@code sygnal:CoordinationEvent} in {@code message.events}, as extended in the CCE profile of
 *       Sygnal v3.0: the Sygnal fields, and the clinical-event fields ({@code patientId},
 *       {@code fhirVersion}, {@code sourceEventId}, {@code resource}) directly on it. No
 *       {@code cce:} type, no attribute bag, no {@code @context}, no contract, no commitment;</li>
 *   <li>the Spice referral legs (standard Beckn): {@code on_status} and {@code update}, with
 *       {@code context}, {@code message.contract} and one performance entry whose
 *       {@code performanceAttributes} is the same {@code sygnal:CoordinationEvent}, with an
 *       {@code @context} added (schema {@code CoordinationEvent/v3.0} of cce-beckn-service). The
 *       referral commitment files are read once, at start.</li>
 * </ul>
 * On every leg the event id is the id of the one event / performance entry.
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknMessageBuilder {

    public static final String VERSION = "2.0.0";
    public static final String FHIR_VERSION = "4.0.1";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_ON_STATUS = "on_status";
    public static final String ACTION_EVENT_REPORT = "sygnal/event/report";
    public static final String COORDINATION_EVENT_TYPE = "sygnal:CoordinationEvent";
    public static final String SYGNAL_EVENT_TYPE = "DELIVERY_RECORDED";
    public static final String GRANULARITY = "INDIVIDUAL";
    public static final String ROLE = "PROVIDER";
    /** JSON-LD context of the {@code performanceAttributes} box of the Beckn referral legs. */
    static final String REFERRAL_BOX_CONTEXT_PATH = "/CoordinationEvent/v3.0/context.jsonld";

    private final BecknConfig beckn;
    private final JsonNode referralsInCommitment;
    private final JsonNode referralsOutCommitment;

    public BecknMessageBuilder(EmitterProperties properties, ResourceLoader resourceLoader) {
        this.beckn = properties.getBeckn();
        this.referralsInCommitment = beckn.getLegs().isReferralsIn()
                ? loadCommitment(resourceLoader, beckn.getSpice().getReferralsIn().getCommitmentFile(),
                        "emitter.beckn.spice.referrals-in.commitment-file")
                : null;
        this.referralsOutCommitment = beckn.getLegs().isReferralsOut()
                ? loadCommitment(resourceLoader, beckn.getSpice().getReferralsOut().getCommitmentFile(),
                        "emitter.beckn.spice.referrals-out.commitment-file")
                : null;
    }

    /**
     * Sygnal {@code sygnal/event/report} to the HIE, posted to this emitter's onix Sygnal caller.
     * {@code message.events} holds exactly one {@code sygnal:CoordinationEvent} of the CCE profile
     * v3.0: only the fields set here, in this order, and never a null.
     */
    public OutgoingMessage eventReport(JsonNode resource, String eventId, String patientId) {
        var sygnal = beckn.getSygnal();
        var hie = sygnal.getHie();
        String transactionId = isBlank(hie.getTransactionId())
                ? UUID.randomUUID().toString()
                : hie.getTransactionId().trim();

        ObjectNode root = BecknJson.MAPPER.createObjectNode();
        ObjectNode context = root.putObject("context");
        context.put("version", VERSION);
        context.put("action", ACTION_EVENT_REPORT);
        context.put("networkId", sygnal.getNetworkId());
        context.put("senderId", sygnal.getId());
        context.put("receiverId", hie.getId());
        context.put("receiverUri", hie.getUri());
        context.put("transactionId", transactionId);
        // replaced on every attempt by OutgoingMessage.forAttempt()
        context.put("messageId", UUID.randomUUID().toString());
        context.put("timestamp", now());

        ObjectNode event = root.putObject("message").putArray("events").addObject();
        coordinationEvent(event, sygnal.getId(), resource, eventId, patientId);

        return message(Leg.CCE_EVENTS, ACTION_EVENT_REPORT, sygnal.getCallerUrl(), root, eventId, resource,
                transactionId, null);
    }

    /** {@code on_status} on the "referrals in" contract (OpenMRS is the provider), to {@code onix-bpp}. */
    public OutgoingMessage peerOnStatus(JsonNode resource, String eventId, String patientId, String performanceStatus) {
        var peer = beckn.getSpice();
        var self = beckn.getSelf();
        BecknContractConfig contract = peer.getReferralsIn();
        ObjectNode context = context(ACTION_ON_STATUS, peer.getBapId(), peer.getBapUri(),
                self.getBppId(), self.getBppUri(), contract.getTransactionId());
        ObjectNode performance = performance(eventId, performanceStatus, resource, patientId, self.getBppId());
        ObjectNode body = envelope(context, contract.getContractId(), true, referralsInCommitment, performance);
        return message(Leg.REFERRALS_IN, ACTION_ON_STATUS, beckn.getOnix().getBppCallerUrl(), body, eventId,
                resource, contract.getTransactionId(), contract.getContractId());
    }

    /** {@code update} on the "referrals out" contract (the peer is the provider), to {@code onix-bap}. */
    public OutgoingMessage peerReferralUpdate(JsonNode resource, String eventId, String patientId) {
        var peer = beckn.getSpice();
        var self = beckn.getSelf();
        BecknContractConfig contract = peer.getReferralsOut();
        ObjectNode context = context(ACTION_UPDATE, self.getBapId(), self.getBapUri(),
                peer.getBppId(), peer.getBppUri(), contract.getTransactionId());
        ObjectNode performance = performance(eventId, null, resource, patientId, self.getBapId());
        ObjectNode body = envelope(context, contract.getContractId(), false, referralsOutCommitment, performance);
        return message(Leg.REFERRALS_OUT, ACTION_UPDATE, beckn.getOnix().getBapCallerUrl(), body, eventId,
                resource, contract.getTransactionId(), contract.getContractId());
    }

    /** Beckn {@code context} of the referral legs. */
    private ObjectNode context(String action, String bapId, String bapUri, String bppId, String bppUri,
                               String transactionId) {
        ObjectNode context = BecknJson.MAPPER.createObjectNode();
        context.put("version", VERSION);
        context.put("action", action);
        String networkId = beckn.getSelf().getNetworkId();
        if (networkId != null && !networkId.isBlank()) {
            context.put("networkId", networkId);
        }
        context.put("bapId", bapId);
        context.put("bapUri", bapUri);
        context.put("bppId", bppId);
        context.put("bppUri", bppUri);
        context.put("transactionId", transactionId);
        // replaced on every attempt by OutgoingMessage.forAttempt()
        context.put("messageId", UUID.randomUUID().toString());
        context.put("timestamp", now());
        return context;
    }

    private ObjectNode performance(String eventId, String status, JsonNode resource, String patientId,
                                   String senderId) {
        ObjectNode performance = BecknJson.MAPPER.createObjectNode();
        performance.put("id", eventId);
        if (status != null) {
            performance.putObject("status").put("code", status);
        }
        performance.set("performanceAttributes", referralBox(resource, eventId, patientId, senderId));
        return performance;
    }

    /**
     * The box of the Beckn referral legs ({@code performanceAttributes}): the same
     * {@code sygnal:CoordinationEvent} as in {@link #eventReport}, with an {@code @context} first, because
     * onix checks a typed object of a Beckn message only when it has one. {@code from.id} is the
     * subscriber id that sends the message.
     */
    private ObjectNode referralBox(JsonNode resource, String eventId, String patientId, String senderId) {
        ObjectNode attrs = BecknJson.MAPPER.createObjectNode();
        attrs.put("@context", stripTrailingSlash(beckn.getSchemaBaseUrl()) + REFERRAL_BOX_CONTEXT_PATH);
        coordinationEvent(attrs, senderId, resource, eventId, patientId);
        return attrs;
    }

    /**
     * The fields of a {@code sygnal:CoordinationEvent} of the CCE profile v3.0, as extended by
     * {@code cce-fhir-extension.yaml}: only the fields set here, in this order, and never a null.
     */
    private static void coordinationEvent(ObjectNode event, String senderId, JsonNode resource, String eventId,
                                          String patientId) {
        event.put("@type", COORDINATION_EVENT_TYPE);
        event.put("id", eventId);
        event.put("revision", 1);
        event.put("eventType", SYGNAL_EVENT_TYPE);
        event.put("granularity", GRANULARITY);
        ObjectNode from = event.putObject("from");
        from.put("id", senderId);
        from.put("role", ROLE);
        event.put("occurredAt", occurredAt(resource));
        event.put("patientId", patientId);
        event.put("fhirVersion", FHIR_VERSION);
        String sourceEventId = sourceEventId(resource);
        if (sourceEventId != null) {
            event.put("sourceEventId", sourceEventId);
        }
        event.set("resource", resource.deepCopy());
    }

    private static ObjectNode envelope(ObjectNode context, String contractId, boolean withContractStatus,
                                       JsonNode commitment, ObjectNode performance) {
        ObjectNode root = BecknJson.MAPPER.createObjectNode();
        root.set("context", context);
        ObjectNode contract = root.putObject("message").putObject("contract");
        contract.put("id", contractId);
        if (withContractStatus) {
            contract.putObject("status").put("code", "ACTIVE");
        }
        contract.putArray("commitments").add(commitment.deepCopy());
        contract.putArray("performance").add(performance);
        return root;
    }

    /** {@code {ResourceType}/{id}}: the HMIS's own name for the event; null when either part is missing. */
    private static String sourceEventId(JsonNode resource) {
        String resourceType = BecknJson.text(resource, "resourceType");
        String id = BecknJson.text(resource, "id");
        return resourceType != null && id != null ? resourceType + "/" + id : null;
    }

    /** The URL is plain text, so the slashes of a Sygnal action are never encoded. */
    private static OutgoingMessage message(Leg leg, String action, String callerUrl, ObjectNode body, String eventId,
                                           JsonNode resource, String transactionId, String contractId) {
        return new OutgoingMessage(leg, action, stripTrailingSlash(callerUrl) + "/" + action, body, eventId,
                BecknJson.text(resource, "resourceType"), transactionId, contractId);
    }

    /**
     * When the event happened ({@code occurredAt} of the event): {@code meta.lastUpdated} of the resource,
     * i.e. when OpenMRS recorded this version of it (the version the event id is the hash of), as a
     * UTC instant. Falls back to the time the message is built when it is missing or not a full
     * timestamp. The time of the clinical act itself (for example {@code Encounter.period.start})
     * stays in the resource; CCE reads it from there for its schedules.
     */
    static String occurredAt(JsonNode resource) {
        String lastUpdated = BecknJson.text(resource == null ? null : resource.path("meta"), "lastUpdated");
        if (lastUpdated != null) {
            try {
                return OffsetDateTime.parse(lastUpdated).toInstant().toString();
            } catch (DateTimeParseException e) {
                // not a full FHIR instant: fall back to now
            }
        }
        return now();
    }

    static JsonNode loadCommitment(ResourceLoader loader, String location, String setting) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Beckn setting " + setting + " is not set");
        }
        Resource resource = loader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            JsonNode node = BecknJson.MAPPER.readTree(in);
            if (node == null || !node.isObject() || BecknJson.text(node, "id") == null) {
                throw new IllegalStateException("Commitment file " + location + " (" + setting
                        + ") must hold a JSON object with an \"id\"");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read commitment file " + location + " (" + setting + "): "
                    + e.getMessage(), e);
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
