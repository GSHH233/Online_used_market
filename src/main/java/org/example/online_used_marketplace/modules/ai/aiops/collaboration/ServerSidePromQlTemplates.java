package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import java.util.Locale;

final class ServerSidePromQlTemplates {

    private static final String DEFAULT_SERVICE = "marketplace-api";

    private ServerSidePromQlTemplates() {
    }

    static String build(AlertInvestigationContext context) {
        String trustedService = context == null ? null : context.trustedLabel("service");
        String service = safeLabel(trustedService);
        String alertName = context == null || context.getAlertName() == null
                ? ""
                : context.getAlertName().toLowerCase(Locale.ROOT);
        if ("marketplace-demo-worker".equals(service)) {
            if (containsAny(alertName, "error", "5xx", "failure", "错误")
                    || "error_ratio".equalsIgnoreCase(context.trustedLabel("metric"))) {
                return "aiops_demo_service_error_ratio{service=\"" + escape(service) + "\"}";
            }
            return "aiops_demo_service_health{service=\"" + escape(service) + "\"}";
        }
        if (containsAny(alertName, "latency", "p95", "p99", "slow", "延迟")) {
            return "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{service=\""
                    + escape(service) + "\"}[5m])) by (le))";
        }
        if (containsAny(alertName, "error", "5xx", "failure", "错误")) {
            return "sum(rate(http_server_requests_seconds_count{service=\""
                    + escape(service) + "\",status=~\"5..\"}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{service=\""
                    + escape(service) + "\"}[5m])), 1)";
        }
        return "up{job=\"" + escape(service) + "\"}";
    }

    static String templateName(AlertInvestigationContext context) {
        String alertName = context == null || context.getAlertName() == null
                ? ""
                : context.getAlertName().toLowerCase(Locale.ROOT);
        if (containsAny(alertName, "latency", "p95", "p99", "slow", "延迟")) {
            return "HTTP_P95_LATENCY";
        }
        if (containsAny(alertName, "error", "5xx", "failure", "错误")) {
            return "HTTP_ERROR_RATIO";
        }
        return "SERVICE_UP";
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String safeLabel(String value) {
        return isSafeLabel(value) ? value : DEFAULT_SERVICE;
    }

    private static boolean isSafeLabel(String value) {
        return value != null && value.length() <= 100 && value.matches("[A-Za-z0-9_.:-]+");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
