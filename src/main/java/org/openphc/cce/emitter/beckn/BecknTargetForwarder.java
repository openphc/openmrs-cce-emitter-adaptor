package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknLegsConfig;
import org.openphc.cce.emitter.service.ForwardResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of Beckn mode (docs/beckn-integration.md § 4). Called by {@code FhirPollerService}
 * in place of {@code ForwardingEngine} with the enriched resource JSON. Sends the Sygnal action
 * {@code sygnal/event/report} to the HIE (which passes it on to CCE) and, for a referral, one Beckn
 * message to the HMIS peer; each message is sent and retried on its own. The result is a success
 * only if every message got {@code Ack}.
 *
 * <p>A resource with no patient (for example {@code Location}, {@code Practitioner}) is skipped:
 * {@code patientId} is required on every leg, by the {@code sygnal:CoordinationEvent} of the CCE
 * profile, in the report to the HIE and in the box of the referral legs.
 */
@Service
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknTargetForwarder {

    private static final Logger log = LoggerFactory.getLogger(BecknTargetForwarder.class);
    public static final String METRIC_SKIPPED = "openmrs.beckn.resources.skipped";
    public static final String METRIC_REFERRALS_OUT = "openmrs.beckn.referrals.out";

    private final EmitterProperties properties;
    private final EventIdFactory eventIdFactory;
    private final PatientIdReader patientIdReader;
    private final ReferralClassifier referralClassifier;
    private final BecknMessageBuilder messageBuilder;
    private final OnixClient onixClient;
    private final MeterRegistry meterRegistry;

    public BecknTargetForwarder(EmitterProperties properties,
                                EventIdFactory eventIdFactory,
                                PatientIdReader patientIdReader,
                                ReferralClassifier referralClassifier,
                                BecknMessageBuilder messageBuilder,
                                OnixClient onixClient,
                                MeterRegistry meterRegistry) {
        this.properties = properties;
        this.eventIdFactory = eventIdFactory;
        this.patientIdReader = patientIdReader;
        this.referralClassifier = referralClassifier;
        this.messageBuilder = messageBuilder;
        this.onixClient = onixClient;
        this.meterRegistry = meterRegistry;
    }

    public ForwardResult forward(String resourceJson, String resourceType) {
        JsonNode resource;
        try {
            resource = BecknJson.parse(resourceJson);
        } catch (IllegalArgumentException e) {
            skipped(resourceType, "invalid-json");
            log.error("beckn skip resourceType={} reason=invalid-json", resourceType);
            return ForwardResult.skipped("invalid-json");
        }
        String patientId = patientIdReader.read(resource);
        if (patientId == null) {
            skipped(resourceType, "no-patient");
            log.info("beckn skip resourceType={} reason=no-patient", resourceType);
            return ForwardResult.skipped("no-patient");
        }
        String eventId = eventIdFactory.eventId(resource);
        BecknLegsConfig legs = properties.getBeckn().getLegs();
        List<OnixClient.SendResult> results = new ArrayList<>();

        if (legs.isCceEvents()) {
            results.add(onixClient.send(messageBuilder.eventReport(resource, eventId, patientId)));
        }

        ReferralClassifier.Decision decision = referralClassifier.classify(resource);
        switch (decision.kind()) {
            case OUTCOME_TO_PEER -> {
                if (legs.isReferralsIn()) {
                    results.add(onixClient.send(
                            messageBuilder.peerOnStatus(resource, eventId, patientId, decision.performanceStatus())));
                }
            }
            case REFERRAL_OUT_TO_PEER -> {
                if (legs.isReferralsOut()) {
                    referralsOut("sent");
                    results.add(onixClient.send(messageBuilder.peerReferralUpdate(resource, eventId, patientId)));
                } else {
                    referralsOut("leg-off");
                }
            }
            case REFERRAL_OUT_NOT_SERVED -> referralsOut("facility-not-served");
            case NONE -> { /* nothing for the peer */ }
        }

        if (results.isEmpty()) {
            skipped(resourceType, "all-legs-off");
            return ForwardResult.skipped("no Beckn leg on for this resource");
        }
        for (OnixClient.SendResult r : results) {
            if (!r.isAck()) {
                return r.httpStatus() == 0
                        ? ForwardResult.unreachable(r.attempts())
                        : ForwardResult.failure(r.httpStatus(), null);
            }
        }
        return ForwardResult.success(200);
    }

    private void skipped(String resourceType, String reason) {
        meterRegistry.counter(METRIC_SKIPPED, Tags.of("resourceType", String.valueOf(resourceType), "reason", reason))
                .increment();
    }

    private void referralsOut(String outcome) {
        meterRegistry.counter(METRIC_REFERRALS_OUT, Tags.of("outcome", outcome)).increment();
    }
}
