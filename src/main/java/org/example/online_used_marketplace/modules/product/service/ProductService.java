package org.example.online_used_marketplace.modules.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.example.online_used_marketplace.modules.product.dto.ProductQueryDTO;
import org.example.online_used_marketplace.modules.product.vo.ProductCreateVO;
import org.example.online_used_marketplace.modules.product.vo.ProductDetailVO;
import org.example.online_used_marketplace.modules.product.vo.ProductListItemVO;

/**
 * @author Admin
 * @ClassName: ProductService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-02  15:47
 * @Version:
 */
public interface ProductService {
    ProductCreateVO addProduct(ProductCreateDTO dto,Long currentUserId);
    IPage<ProductListItemVO> listProduct(ProductQueryDTO dto);
    ProductDetailVO getProductDetail(Long productId);
}
