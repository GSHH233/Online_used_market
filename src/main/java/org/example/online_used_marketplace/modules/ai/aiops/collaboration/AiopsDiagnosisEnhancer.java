package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

/**
 * Optional model enhancement SPI. Implementations receive a control-plane-only
 * context and cannot introduce evidence or executable actions by themselves.
 */
public interface AiopsDiagnosisEnhancer {

    Diagnosis enhance(AlertInvestigationContext controlPlaneContext, Diagnosis deterministicDiagnosis);
}
