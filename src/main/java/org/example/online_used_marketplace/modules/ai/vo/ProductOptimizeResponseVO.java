package org.example.online_used_marketplace.modules.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author Admin
 * @ClassName: ProductOptimizeResponseVO
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-20  20:23
 * @Version:
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductOptimizeResponseVO {
    private String optimizedTitle;
    private String optimizedDescription;
    private List<String> tags;
    private List<String> riskWarnings;

}
