package org.example.online_used_marketplace.modules.ai.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.modules.ai.config.AiopsAlertProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiopsAlertServiceImplTest {

    @Test
    void rejectsMissingOrWrongTokenBeforeDatabaseAccess() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiopsAlertProperties properties = new AiopsAlertProperties();
        properties.setEnabled(true);
        properties.setWebhookToken("server-secret");
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ObjectProvider<AiopsAlertInvestigationPublisher> publisherProvider = mock(ObjectProvider.class);
        AiopsAlertServiceImpl service = new AiopsAlertServiceImpl(
                jdbcTemplate, new ObjectMapper(), properties, publisherProvider,
                mock(AiopsAlertDeliveryStateStore.class), transactionManager);

        assertThrows(BusinessException.class, () -> service.receive(validWebhook(), null));
        assertThrows(BusinessException.class, () -> service.receive(validWebhook(), "wrong-secret"));
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void publishesOnlyAfterTransactionCommitAndPersistsPublishedStatus() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiopsAlertProperties properties = new AiopsAlertProperties();
        properties.setEnabled(true);
        properties.setWebhookToken("server-secret");
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        ObjectProvider<AiopsAlertInvestigationPublisher> publisherProvider = mock(ObjectProvider.class);
        AiopsAlertInvestigationPublisher publisher = mock(AiopsAlertInvestigationPublisher.class);
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        when(publisher.publish(any())).thenReturn(true);
        AiopsAlertDeliveryStateStore stateStore = mock(AiopsAlertDeliveryStateStore.class);

        AiopsAlertDeliveryVO delivery = new AiopsAlertDeliveryVO();
        delivery.setId(1L);
        delivery.setAlertCount(1);
        delivery.setEventIds(List.of(2L));
        delivery.setPayloadHash("payload-hash");
        delivery.setTraceId("trace-id");
        delivery.setProcessingStatus("PUBLISHED");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(), List.of(delivery));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L, 2L);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn("fingerprint");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        AiopsAlertServiceImpl service = new AiopsAlertServiceImpl(
                jdbcTemplate, new ObjectMapper(), properties, publisherProvider, stateStore, transactionManager);

        service.receive(validWebhook(), "server-secret");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(transactionManager, publisher, stateStore);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(publisher).publish(any());
        order.verify(stateStore).markPublished(1L);
    }

    private AlertmanagerWebhookDTO validWebhook() {
        AlertmanagerWebhookDTO webhook = new AlertmanagerWebhookDTO();
        webhook.setReceiver("used-marketplace-aiops");
        webhook.setStatus("firing");
        webhook.setGroupKey("{}:{alertname=\"AiopsDemoServiceUnhealthy\",service=\"marketplace-demo-worker\",run=\"20260819184906149452\"}");
        webhook.setVersion("4");
        webhook.setExternalURL("http://alertmanager.local");
        webhook.setGroupLabels(Map.of("alertname", "AiopsDemoServiceUnhealthy", "service", "marketplace-demo-worker"));
        webhook.setCommonLabels(Map.of("environment", "demo", "severity", "warning", "service", "marketplace-demo-worker"));
        webhook.setCommonAnnotations(Map.of("source", "aiops-continuous-detection-eval"));
        AlertmanagerAlertDTO alert = new AlertmanagerAlertDTO();
        alert.setStatus("firing");
        alert.setLabels(Map.of(
                "alertname", "AiopsDemoServiceUnhealthy",
                "service", "marketplace-demo-worker",
                "environment", "demo",
                "severity", "warning",
                "instance", "primary-20260819184906149452"));
        alert.setAnnotations(Map.of(
                "summary", "Demo service is unhealthy",
                "description", "External observation only",
                "evalSequence", "1"));
        alert.setStartsAt("2026-08-19T18:49:06.149452Z");
        alert.setEndsAt("");
        alert.setGeneratorURL("http://prometheus.local/graph");
        alert.setFingerprint("external-fingerprint-is-not-trusted");
        webhook.setAlerts(List.of(alert));
        return webhook;
    }
}
