package org.example.online_used_marketplace.modules.ai.aiops.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.modules.ai.aiops.demo.vo.AiopsDemoServiceStateVO;
import org.example.online_used_marketplace.modules.ai.config.AiopsDemoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiopsDemoServiceImplTest {

    @Test
    void disabledProfileDoesNotExposeFalseOutageAndRejectsMutation() {
        AiopsDemoProperties properties = new AiopsDemoProperties();
        properties.setAllowedServices(List.of("marketplace-demo-worker"));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiopsDemoServiceImpl service = new AiopsDemoServiceImpl(
                redisTemplate, new ObjectMapper(), properties, registry);

        service.registerMetrics();

        assertEquals(1.0D, registry.get("aiops_demo_service_health")
                .tag("service", "marketplace-demo-worker").gauge().value());
        assertEquals(0.0D, registry.get("aiops_demo_service_error_ratio")
                .tag("service", "marketplace-demo-worker").gauge().value());
        assertThrows(BusinessException.class,
                () -> service.injectFault("marketplace-demo-worker", 1.0D, "trace-disabled"));
    }

    @Test
    void resetReadsRedisStateAfterWriteAndOnlyAllowsConfiguredService() throws Exception {
        AiopsDemoProperties properties = new AiopsDemoProperties();
        properties.setEnabled(true);
        properties.setAllowedServices(List.of("marketplace-demo-worker"));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        String fault = "{\"service\":\"marketplace-demo-worker\",\"health\":0,\"errorRatio\":1.0,\"faulted\":true}";
        String healthy = "{\"service\":\"marketplace-demo-worker\",\"health\":1,\"errorRatio\":0.0,\"faulted\":false}";
        when(values.get("ai:aiops:demo:service:marketplace-demo-worker"))
                .thenReturn(fault, healthy);
        AiopsDemoServiceImpl service = new AiopsDemoServiceImpl(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), properties, new SimpleMeterRegistry());

        service.injectFault("marketplace-demo-worker", 1.0D, "trace-fault");
        AiopsDemoServiceStateVO faultState = service.getState("marketplace-demo-worker");
        service.resetFault("marketplace-demo-worker", "trace-reset");
        AiopsDemoServiceStateVO verifiedState = service.getState("marketplace-demo-worker");

        assertEquals(0, faultState.getHealth());
        assertEquals(1.0D, faultState.getErrorRatio());
        assertEquals(1, verifiedState.getHealth());
        assertEquals(0.0D, verifiedState.getErrorRatio());
        verify(values, times(2)).set(eq("ai:aiops:demo:service:marketplace-demo-worker"), anyString());
        assertThrows(BusinessException.class, () -> service.getState("other-service"));
    }
}
