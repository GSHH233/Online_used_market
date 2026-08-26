package org.example.online_used_marketplace.modules.ai.alert;

public interface AiopsAlertInvestigationPublisher {

    boolean publish(AiopsAlertInvestigationMessage message);
}
