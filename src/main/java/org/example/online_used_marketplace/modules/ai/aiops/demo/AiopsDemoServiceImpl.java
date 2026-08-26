package org.example.online_used_marketplace.modules.ai.aiops.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.aiops.demo.vo.AiopsDemoServiceStateVO;
import org.example.online_used_marketplace.modules.ai.config.AiopsDemoProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class AiopsDemoServiceImpl implements AiopsDemoService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiopsDemoProperties properties;
    private final MeterRegistry meterRegistry;

    public AiopsDemoServiceImpl(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                AiopsDemoProperties properties,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerMetrics() {
        for (String service : allowedServices()) {
            String normalized = normalizeService(service);
            meterRegistry.gauge("aiops_demo_service_health", Tags.of("service", normalized), this,
                    ignored -> metricState(normalized).getHealth());
            meterRegistry.gauge("aiops_demo_service_error_ratio", Tags.of("service", normalized), this,
                    ignored -> metricState(normalized).getErrorRatio());
        }
    }

    @Override
    public void injectFault(String service, Double errorRatio, String traceId) {
        requireEnabled();
        String normalized = requireAllowedService(service);
        double ratio = errorRatio == null ? 1.0D : errorRatio;
        if (ratio < 0.0D || ratio > 1.0D) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "errorRatio必须在0到1之间");
        }
        AiopsDemoServiceStateVO state = newState(normalized, 0, ratio, true, traceId);
        writeState(state);
    }

    @Override
    public void resetFault(String service, String traceId) {
        requireEnabled();
        String normalized = requireAllowedService(service);
        writeState(newState(normalized, 1, 0.0D, false, traceId));
    }

    @Override
    public AiopsDemoServiceStateVO getState(String service) {
        requireEnabled();
        String normalized = requireAllowedService(service);
        return readState(normalized);
    }

    private AiopsDemoServiceStateVO metricState(String service) {
        if (!properties.isEnabled()) {
            // The demo profile is not a production service. Disabled must not
            // look like an outage to Prometheus and trigger a false alert.
            return newState(service, 1, 0.0D, false, "demo-disabled");
        }
        try {
            return readState(service);
        } catch (RuntimeException ex) {
            return newState(service, 0, 1.0D, true, "redis-unavailable");
        }
    }

    private AiopsDemoServiceStateVO readState(String service) {
        try {
            String raw = redisTemplate.opsForValue().get(redisKey(service));
            if (!StringUtils.hasText(raw)) {
                return newState(service, 1, 0.0D, false, "");
            }
            AiopsDemoServiceStateVO state = objectMapper.readValue(raw, AiopsDemoServiceStateVO.class);
            state.setService(service);
            if (state.getHealth() == null) {
                state.setHealth(Boolean.TRUE.equals(state.getFaulted()) ? 0 : 1);
            }
            if (state.getErrorRatio() == null) {
                state.setErrorRatio(Boolean.TRUE.equals(state.getFaulted()) ? 1.0D : 0.0D);
            }
            if (state.getFaulted() == null) {
                state.setFaulted(state.getHealth() == 0 || state.getErrorRatio() > 0.0D);
            }
            return state;
        } catch (Exception ex) {
            log.warn("read AIOps demo state from redis failed, service={}, reason={}", service, ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "demo服务状态读取失败");
        }
    }

    private void writeState(AiopsDemoServiceStateVO state) {
        try {
            redisTemplate.opsForValue().set(redisKey(state.getService()), objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "demo服务状态序列化失败");
        } catch (RuntimeException ex) {
            log.warn("write AIOps demo state to redis failed, service={}, reason={}",
                    state.getService(), ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "demo服务状态写入失败");
        }
    }

    private AiopsDemoServiceStateVO newState(String service, int health, double errorRatio,
                                             boolean faulted, String traceId) {
        AiopsDemoServiceStateVO state = new AiopsDemoServiceStateVO();
        state.setService(service);
        state.setHealth(health);
        state.setErrorRatio(errorRatio);
        state.setFaulted(faulted);
        state.setTraceId(StringUtils.hasText(traceId) ? traceId.trim() : "");
        state.setUpdatedAt(LocalDateTime.now());
        return state;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "AIOps demo profile已禁用，请显式开启 ai.aiops.demo.enabled");
        }
    }

    private String requireAllowedService(String service) {
        String normalized = normalizeService(service);
        if (!allowedServices().contains(normalized)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "demo服务不在白名单中：" + normalized);
        }
        return normalized;
    }

    private List<String> allowedServices() {
        List<String> configured = properties.getAllowedServices();
        if (configured == null || configured.isEmpty()) {
            return List.of("marketplace-demo-worker");
        }
        List<String> normalized = new ArrayList<>();
        for (String service : configured) {
            if (StringUtils.hasText(service)) {
                normalized.add(normalizeService(service));
            }
        }
        return normalized.isEmpty() ? List.of("marketplace-demo-worker") : normalized;
    }

    private String normalizeService(String service) {
        if (!StringUtils.hasText(service)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "service不能为空");
        }
        String normalized = service.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "service格式不合法");
        }
        return normalized;
    }

    private String redisKey(String service) {
        String prefix = StringUtils.hasText(properties.getRedisKeyPrefix())
                ? properties.getRedisKeyPrefix().trim() : "ai:aiops:demo:service:";
        return prefix + service;
    }
}
