package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.ProductOptimizeRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiProductService;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * @author Admin
 * @ClassName: AiProductServiceImpl
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-20  20:27
 * @Version:
 */
@Service
@RequiredArgsConstructor
public class AiProductServiceImpl implements AiProductService {
    private final ChatGenerationService chatGenerationService;
    private final ObjectMapper objectMapper;

    @Override
    public ProductOptimizeResponseVO optimizeProductInfo(ProductOptimizeRequestDTO request) {
        validateRequest(request);
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(request);

        try {
            String modelContent = chatGenerationService.generate(TokenGenerationRequest.builder()
                    .scene(TokenScene.PUBLISH_OPTIMIZE)
                    .systemPrompt(systemPrompt)
                    .prompt(userPrompt)
                    .build()).getContent();

            return parseAndNormalizeResponse(modelContent);
//            return objectMapper.readValue(modelContent, ProductOptimizeResponseVO.class);
        } catch (BusinessException e) {
            // 参数校验、解析校验等业务异常，原样抛出
            throw e;
        } catch (Exception e) {
            // 模型调用失败、网络异常等系统问题
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "AI服务暂时不可用，请稍后重试");
        }
    }

    private void validateRequest(ProductOptimizeRequestDTO request) {
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

    private String buildSystemPrompt() {
        // TODO: 你在这里定义 system prompt

        return """
                你是“二手商品发布优化助手”。
                任务：基于用户提供的原始商品信息，输出更清晰、可信、易成交的发布文案建议。
                                
                必须遵守：
                1) 不能编造未提供的信息（如发票、保修、配件完整、无拆修等）。
                1.1) 用户明确提供的型号、容量、电池健康度、配件、维修史、发票和来源信息必须保留，不能省略或反转。
                2) 不使用夸张营销词（如“全网最低”“闭眼入”）。
                3) 语气客观自然，避免口语废话。
                4) 仅输出 JSON，不要 markdown，不要解释，不要前后缀文字。
                                
                输出 JSON 字段固定为：
                {
                  "optimizedTitle": "string",
                  "optimizedDescription": "string",
                  "tags": ["string"],
                  "riskWarnings": ["string"]
                }
                                
                约束：
                - optimizedTitle 长度 3~80
                - optimizedDescription 长度 10~2000
                - tags 最多 8 个，每个最多 20 字
                - riskWarnings 最多 5 个，每个最多 60 字
                                
                若无法生成有效内容，返回：
                {"optimizedTitle":"","optimizedDescription":"","tags":[],"riskWarnings":[]}
                """;
    }

    private String buildUserPrompt(ProductOptimizeRequestDTO request) {
        // TODO: 你在这里定义 user prompt
        return  "Title: "+request.getTitle()+"\n"+
                "Description: "+request.getDescription()+"\n"+
                "Brand: "+request.getBrand()+"\n"+
                "ConditionText: "+request.getConditionText()+"\n"+
                "CategoryName: "+request.getCategoryName()+"\n"+
                "title：长度在 3～80 个字符之间\n" +
                "description：长度在 10～2000 个字符之间\n" +
                "brand：长度不超过 50 个字符\n" +
                "conditionText：长度不超过 30 个字符\n" +
                "categoryName：长度不超过 50 个字符";
    }

    private ProductOptimizeResponseVO parseAndNormalizeResponse(String modelContent) {
        // TODO: 你在这里实现 JSON 解析、空值兜底和字段清洗
        if (!StringUtils.hasText(modelContent)){
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"AI返回异常");
        }
        ProductOptimizeResponseVO productOptimizeResponseVO=new ProductOptimizeResponseVO();
        try {
            productOptimizeResponseVO = objectMapper.readValue(modelContent, ProductOptimizeResponseVO.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"AI返回格式异常，请重试");
        }
        List<String> tags = productOptimizeResponseVO.getTags();
        List<String> riskWarnings = productOptimizeResponseVO.getRiskWarnings();
        String optimizeTitle = normalizeText(productOptimizeResponseVO.getOptimizedTitle(),80);
        String optimizeDescription = normalizeText(productOptimizeResponseVO.getOptimizedDescription(), 2000);
        if (tags==null||tags.isEmpty()){
            tags=Collections.emptyList();
        }
        if (riskWarnings==null||riskWarnings.isEmpty()){
            riskWarnings=Collections.emptyList();
        }
        tags = tags.stream().filter(StringUtils::hasText).map(string -> {
            string=string.trim();
            if (string.length()>20){
                return string.substring(0,20);
            }
            return string;
        }).distinct().limit(8).toList();

        productOptimizeResponseVO.setTags(tags);
        riskWarnings= riskWarnings.stream().filter(StringUtils::hasText).map(string -> {
            string=string.trim();
            if (string.length()>60){
                return string.substring(0,60);
            }
            return string;
                })
                .distinct().limit(5).toList();
        productOptimizeResponseVO.setRiskWarnings(riskWarnings);
        productOptimizeResponseVO.setOptimizedTitle(optimizeTitle);
        productOptimizeResponseVO.setOptimizedDescription(optimizeDescription);
        if (!StringUtils.hasText(optimizeTitle)){
            productOptimizeResponseVO.setOptimizedTitle("二手商品转让");
        }
        if (!StringUtils.hasText(optimizeDescription)){
            productOptimizeResponseVO.setOptimizedDescription("商品信息详见原始描述");
        }
        return productOptimizeResponseVO;
    }
    private String normalizeText(String text, int maxLen){
        if(StringUtils.hasText(text)){
            text=text.trim();
            if (text.length()>maxLen){
                text=text.substring(0,maxLen);
            }
        }
        return text;
    }
}
