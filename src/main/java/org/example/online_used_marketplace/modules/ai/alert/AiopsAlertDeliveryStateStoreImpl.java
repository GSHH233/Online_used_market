package org.example.online_used_marketplace.modules.ai.alert;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiopsAlertDeliveryStateStoreImpl implements AiopsAlertDeliveryStateStore {

    private final JdbcTemplate jdbcTemplate;

    public AiopsAlertDeliveryStateStoreImpl(@Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markPublished(Long deliveryId) {
        update("PUBLISHED", "", deliveryId);
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markResolved(Long deliveryId) {
        update("RESOLVED", "", deliveryId);
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long deliveryId, String error) {
        update("FAILED", error, deliveryId);
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markInvestigationPending(Long deliveryId, String error) {
        update("INVESTIGATION_PENDING", error, deliveryId);
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRetryScheduled(Long deliveryId, String error) {
        update("RETRY_SCHEDULED", error, deliveryId);
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markInvestigationAccepted(Long deliveryId) {
        update("INVESTIGATION_ACCEPTED", "", deliveryId);
    }

    private void update(String status, String error, Long deliveryId) {
        jdbcTemplate.update("""
                UPDATE aiops_alert_delivery
                SET processing_status=?, error_msg=?, updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, status, bounded(error), deliveryId);
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
