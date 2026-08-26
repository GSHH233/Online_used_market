package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.AssistStepStatus;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistPipelineRunner;
import org.example.online_used_marketplace.modules.ai.service.AiPublishAssistService;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistGuardrailService;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.ai.service.ReplanService;
import org.example.online_used_marketplace.modules.ai.vo.AssistStepTraceVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRetrievalVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 发布助手聚合服务
 */
@Service
@RequiredArgsConstructor
public class AiPublishAssistServiceImpl implements AiPublishAssistService {

    private final ReplanService replanService;
    private final PublishAssistPipelineRunner pipelineRunner;
    private final PublishAssistGuardrailService guardrailService;
    private final PublishAssistFeedbackService feedbackService;

    @Override
    public ProductPublishAssistVO assist(ProductPublishAssistRequestDTO request, Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        ProductPublishAssistVO result = executeAssist(request);
        String assistId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        result.setAssistId(assistId);
        result.setTraceId(traceId);
        feedbackService.recordAssist(assistId, traceId, userId, request, result);
        return result;
    }

    private ProductPublishAssistVO executeAssist(ProductPublishAssistRequestDTO request) {
        validateRequest(request);

        List<AssistStepTraceVO> trace = new ArrayList<>();
        Optional<ProductPublishAssistVO> guardrailResult = guardrailService.apply(request, trace);
        if (guardrailResult.isPresent()) {
            return guardrailResult.get();
        }

        int maxRounds = 2;

        ProductPublishAssistRequestDTO originalRequest = new ProductPublishAssistRequestDTO();
        ProductPublishAssistRequestDTO currentRequest = new ProductPublishAssistRequestDTO();
        BeanUtils.copyProperties(request, originalRequest);
        BeanUtils.copyProperties(originalRequest, currentRequest);

        ProductPublishAssistVO firstResult = null;
        ProductPublishAssistVO finalResult = null;
        List<PublishAssistRetrievalVO> retrievals = new ArrayList<>();

        for (int round = 1; round <= maxRounds; round++) {
            appendRoundPlan(trace, round);

            try {
                ProductPublishAssistVO currentResult = executeOnce(currentRequest, trace, round);
                if (currentResult.getRetrievals() != null) {
                    retrievals.addAll(currentResult.getRetrievals());
                }
                currentResult.setRetrievals(new ArrayList<>(retrievals));

                if (round == 1) {
                    firstResult = currentResult;
                }

                if (!replanService.shouldReplan(currentResult)) {
                    finalResult = currentResult;
                    finalResult.setTrace(trace);
                    return finalResult;
                }

                if (round < maxRounds) {
                    markReplan(trace, "命中重规划条件，进入第" + (round + 1) + "轮", round);
                    currentRequest = replanService.buildReplanRequest(originalRequest, currentResult);
                } else {
                    finalResult = currentResult;
                    finalResult.setTrace(trace);
                    return finalResult;
                }
            } catch (BusinessException e) {
                if (round == 1) {
                    throw e;
                }
                markReplan(trace, "第二轮失败，已降级返回第一轮结果", round);
                finalResult = firstResult;
                if (finalResult == null) {
                    throw e;
                }
                finalResult.setTrace(trace);
                return finalResult;
            } catch (Exception e) {
                if (round == 1) {
                    throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布助手执行失败，请稍后重试");
                }
                markReplan(trace, "第二轮失败，已降级返回第一轮结果", round);
                finalResult = firstResult;
                if (finalResult == null) {
                    throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布助手执行失败，请稍后重试");
                }
                finalResult.setTrace(trace);
                return finalResult;
            }
        }

        finalResult = (finalResult != null ? finalResult : firstResult);
        if (finalResult != null) {
            finalResult.setTrace(trace);
            return finalResult;
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布助手执行失败，请稍后重试");
    }

    private ProductPublishAssistVO executeOnce(ProductPublishAssistRequestDTO request, List<AssistStepTraceVO> trace, Integer round) {
        PublishInputContext context = toContext(request);
        PublishAssistResult result = new PublishAssistResult();
        pipelineRunner.run(context, result, trace, round);

        ProductPublishAssistVO vo = new ProductPublishAssistVO();
        vo.setOptimizeResult(result.getOptimizeResult());
        vo.setRiskScore(result.getRiskScore());
        vo.setRiskWarnings(result.getRiskWarnings());
        vo.setPriceSuggestion(result.getPriceSuggestion());
        vo.setCanPublish(result.getCanPublish());
        vo.setRiskEvidence(result.getRiskEvidence());
        vo.setTrace(trace);
        vo.setSources(result.getSources());
        vo.setRetrievals(result.getRetrievals());
        vo.setGenerationMode(result.getGenerationMode());
        vo.setFallbackUsed(result.getFallbackUsed());
        vo.setFallbackReason(result.getFallbackReason());
        return vo;
    }

    private PublishInputContext toContext(ProductPublishAssistRequestDTO request) {
        PublishInputContext context = new PublishInputContext();
        context.setTitle(request.getTitle());
        context.setDescription(request.getDescription());
        context.setBrand(request.getBrand());
        context.setConditionText(request.getConditionText());
        context.setCategoryName(request.getCategoryName());
        return context;
    }

    private void markReplan(List<AssistStepTraceVO> trace, String reason, Integer round) {
        AssistStepTraceVO vo = new AssistStepTraceVO();
        vo.setStep("replan#" + round);
        vo.setLabel("重新规划");
        vo.setStatus(AssistStepStatus.SUCCESS);
        vo.setMessage(reason);
        trace.add(vo);
    }

    private void validateRequest(ProductPublishAssistRequestDTO request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }

        String title = request.getTitle();
        String description = request.getDescription();
        String conditionText = request.getConditionText();
        String categoryName = request.getCategoryName();
        String brand = request.getBrand();

        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标题不能为空");
        }
        title = title.trim();
        if (title.length() < 3) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标题不能过短");
        }
        if (title.length() > 80) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标题不能过长");
        }

        if (!StringUtils.hasText(description)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "描述不能为空");
        }
        description = description.trim();
        if (description.length() < 10) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "描述不能过短");
        }
        if (description.length() > 2000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "描述不能过长");
        }

        if (StringUtils.hasText(conditionText)) {
            conditionText = conditionText.trim();
            if (conditionText.length() > 30) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "内容不能过长");
            }
        }

        if (StringUtils.hasText(categoryName)) {
            categoryName = categoryName.trim();
            if (categoryName.length() > 30) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类名不能过长");
            }
        }

        if (StringUtils.hasText(brand)) {
            brand = brand.trim();
            if (brand.length() > 40) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "brand不能过长");
            }
        }

        request.setTitle(title);
        request.setDescription(description);
        request.setConditionText(conditionText);
        request.setCategoryName(categoryName);
        request.setBrand(brand);
    }

    private List<AssistStepTraceVO> appendRoundPlan(List<AssistStepTraceVO> trace, Integer round) {
        trace.add(newStep("optimize#" + round, "文案优化"));
        trace.add(newStep("risk#" + round, "风险评估"));
        trace.add(newStep("pricing#" + round, "价格建议"));
        trace.add(newStep("decision#" + round, "发布决策"));
        return trace;
    }

    private AssistStepTraceVO newStep(String step, String label) {
        AssistStepTraceVO vo = new AssistStepTraceVO();
        vo.setStep(step);
        vo.setLabel(label);
        vo.setStatus(AssistStepStatus.PENDING);
        vo.setMessage("待执行");
        return vo;
    }
}
