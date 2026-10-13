package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.example.online_used_marketplace.modules.product.service.ProductPublicationValidator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PublishAssistProductPublicationValidator implements ProductPublicationValidator {

    private static final Set<String> ADOPTION_TYPES = Set.of("APPLIED", "PARTIAL", "IGNORED");
    private static final Set<String> ADOPTABLE_FIELDS = Set.of("title", "description", "price", "riskAdvice");

    private final PublishAssistFeedbackService feedback;

    @Override
    public void validate(ProductCreateDTO product, Long currentUserId) {
        if (StringUtils.hasText(product.getAssistId())) {
            feedback.getOwned(product.getAssistId(), currentUserId);
        }
        String type = product.getAssistAdoptionType();
        String normalizedType = StringUtils.hasText(type) ? type.trim().toUpperCase(Locale.ROOT) : "";
        if (!normalizedType.isEmpty() && !ADOPTION_TYPES.contains(normalizedType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "assistAdoptionType不合法");
        }
        List<String> fields = product.getAssistAdoptedFields();
        if (fields != null) {
            for (String field : fields) {
                if (field == null || !ADOPTABLE_FIELDS.contains(field.trim())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的采纳字段");
                }
            }
        }
        boolean hasFields = fields != null && !fields.isEmpty();
        if ("IGNORED".equals(normalizedType) && hasFields) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "IGNORED不能包含已采纳字段");
        }
        if ("PARTIAL".equals(normalizedType) && !hasFields) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "PARTIAL至少包含一个已采纳字段");
        }
    }
}
