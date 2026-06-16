package org.example.online_used_marketplace.modules.ai.context;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;

import java.util.List;
import java.util.Map;

/**
 * @author Admin
 * @ClassName: PublishInputContext
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-24  0:49
 * @Version:
 */
@Data
public class PublishInputContext {
    private String title;

    private String description;

    private String brand;

    private String conditionText;

    private String categoryName;

    private List<String> imageUrls;


    // 机器“看图看到了什么”
    private Map<String,Object> visionAttrs;
    // 机器“准备怎么去查”
    private String queryHints;
    //机器“实际查到了什么证据”
    private List<RagSourceVO> retrievedSources;
}
