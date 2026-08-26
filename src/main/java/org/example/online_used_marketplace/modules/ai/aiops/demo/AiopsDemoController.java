package org.example.online_used_marketplace.modules.ai.aiops.demo;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.aiops.demo.dto.AiopsDemoFaultRequestDTO;
import org.example.online_used_marketplace.modules.ai.aiops.demo.vo.AiopsDemoServiceStateVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/aiops/demo")
public class AiopsDemoController {

    private final AiopsDemoService aiopsDemoService;

    @PostMapping("/faults")
    public Result<AiopsDemoServiceStateVO> injectFault(@Valid @RequestBody AiopsDemoFaultRequestDTO request) {
        AiopsDemoAccessGuard.requireAdmin("aiops_demo_fault_injection");
        aiopsDemoService.injectFault(request.getService(), request.getErrorRatio(), request.getTraceId());
        return Result.success(aiopsDemoService.getState(request.getService()));
    }

    @GetMapping("/services/{service}")
    public Result<AiopsDemoServiceStateVO> getState(@PathVariable String service) {
        AiopsDemoAccessGuard.requireInternal("aiops_demo_state_query");
        return Result.success(aiopsDemoService.getState(service));
    }
}
