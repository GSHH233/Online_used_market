package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.ProductOptimizeRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiProductService;
import org.example.online_used_marketplace.modules.ai.service.AiPublishAssistService;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Admin
 * @ClassName: AiProductController
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-21  0:20
 * @Version:
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiProductController {
    private final AiProductService aiProductService;
    private final AiPublishAssistService aiPublishAssistService;
    @PostMapping("/products/optimize")
    public Result<ProductOptimizeResponseVO> optimizeProductInfo(@Valid @RequestBody ProductOptimizeRequestDTO dto) {
        return Result.success(aiProductService.optimizeProductInfo(dto));
    }
    @PostMapping("/products/publish-assist")
    public Result<ProductPublishAssistVO> publishAssist(@Valid @RequestBody ProductPublishAssistRequestDTO dto){
        return Result.success(aiPublishAssistService.assist(dto));
    }

}
