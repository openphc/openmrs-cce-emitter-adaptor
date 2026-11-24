package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads {@code patientId} of the event, on every leg (docs/beckn-integration.md § 6).
 * {@code PatientReferenceRewriter} has already put the national ID into the references and the
 * Patient id, so this is a plain read with no call to OpenMRS.
 *
 * <ul>
 *   <li>{@code Patient}: its national-id identifier ({@code emitter.patient.national-id.system}),
 *       else its id.</li>
 *   <li>Any other resource: {@code subject.reference} or {@code patient.reference} without
 *       {@code Patient/}.</li>
 * </ul>
 * Returns null when there is none (for example {@code Location}, {@code Practitioner}).
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class PatientIdReader {

    private static final String PATIENT_PREFIX = "Patient/";

    private final EmitterProperties properties;

    public PatientIdReader(EmitterProperties properties) {
        this.properties = properties;
    }

    public String read(JsonNode resource) {
        if (resource == null) return null;
        if ("Patient".equals(BecknJson.text(resource, "resourceType"))) {
            String nationalId = nationalIdentifier(resource);
            return nationalId != null ? nationalId : BecknJson.text(resource, "id");
        }
        for (String field : new String[]{"subject", "patient"}) {
            String reference = BecknJson.text(resource.path(field), "reference");
            if (reference != null && reference.startsWith(PATIENT_PREFIX)
                    && reference.length() > PATIENT_PREFIX.length()) {
                return reference.substring(PATIENT_PREFIX.length());
            }
        }
        return null;
    }

    private String nationalIdentifier(JsonNode patient) {
        String system = properties.getPatient().getNationalId().getSystem();
        if (system == null || system.isBlank()) return null;
        for (JsonNode identifier : patient.path("identifier")) {
            if (system.equals(BecknJson.text(identifier, "system"))) {
                String value = BecknJson.text(identifier, "value");
                if (value != null) return value;
            }
        }
        return null;
    }
}
