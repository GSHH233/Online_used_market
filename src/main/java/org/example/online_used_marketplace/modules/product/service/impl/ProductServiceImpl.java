package org.example.online_used_marketplace.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.example.online_used_marketplace.modules.product.dto.ProductQueryDTO;
import org.example.online_used_marketplace.modules.product.entity.Product;
import org.example.online_used_marketplace.modules.product.entity.ProductImage;
import org.example.online_used_marketplace.modules.product.enums.ProductStatus;
import org.example.online_used_marketplace.modules.product.event.ProductPublishedEvent;
import org.example.online_used_marketplace.modules.product.mapper.ProductImageMapper;
import org.example.online_used_marketplace.modules.product.mapper.ProductMapper;
import org.example.online_used_marketplace.modules.product.service.ProductService;
import org.example.online_used_marketplace.modules.product.service.ProductPublicationValidator;
import org.example.online_used_marketplace.modules.product.vo.ProductCreateVO;
import org.example.online_used_marketplace.modules.product.vo.ProductDetailVO;
import org.example.online_used_marketplace.modules.product.vo.ProductListItemVO;
import org.example.online_used_marketplace.modules.user.entity.User;
import org.example.online_used_marketplace.modules.user.mapper.UserMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "mysqlTransactionManager")
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final ProductImageMapper productImageMapper;
    private final UserMapper userMapper;
    private final List<ProductPublicationValidator> publicationValidators;
    private final ApplicationEventPublisher eventPublisher;
    private static final String DEFAULT_SORT_BY = "publishTime";
    private static final String DEFAULT_SORT_DIRECTION = "desc";

    @Override
    public ProductCreateVO addProduct(ProductCreateDTO dto, Long currentUserId) {
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品请求不能为空");
        }
        if (dto.getImageUrls() == null || dto.getImageUrls().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品图片不能为空");
        }
        if (dto.getPrice() == null || dto.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品价格必须大于0");
        }
        if (dto.getConditionLevel() == null || dto.getConditionLevel() < 1 || dto.getConditionLevel() > 10) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品成色必须在1-10之间");
        }
        for (ProductPublicationValidator validator : publicationValidators) {
            validator.validate(dto, currentUserId);
        }

        Product product = new Product();
        BeanUtils.copyProperties(dto, product);
        product.setSellerId(currentUserId);
        product.setStatus(ProductStatus.ON_SALE.getCode());
        product.setViewCount(0);
        product.setFavoriteCount(0);
        product.setPublishTime(LocalDateTime.now());

        int insert = productMapper.insert(product);
        if (insert != 1 || product.getId() == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布商品失败");
        }

        Long productId = product.getId();
        List<String> imageUrls = dto.getImageUrls();
        for (int i = 0; i < imageUrls.size(); i++) {
            ProductImage productImage = new ProductImage();
            productImage.setProductId(productId);
            productImage.setImageUrl(imageUrls.get(i));
            productImage.setSort(i);
            productImage.setIsMain(i == 0 ? 1 : 0);
            productImage.setCreatedTime(LocalDateTime.now());

            int imageInsert = productImageMapper.insert(productImage);
            if (imageInsert != 1) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "保存商品图片失败");
            }
        }

        eventPublisher.publishEvent(ProductPublishedEvent.snapshot(productId, currentUserId, dto));

        return new ProductCreateVO(productId, product.getTitle(), product.getStatus());
    }

    @Override
    public IPage<ProductListItemVO> listProduct(ProductQueryDTO dto) {
        // Step 1: 参数校验（你可继续补：sortBy 白名单、sortDirection 白名单）
        validateQuery(dto);

        // Step 2: 构建分页对象
        Page<Product> page = new Page<>(dto.getPageNum(), dto.getPageSize());

        // Step 3: 构建查询条件（关键词/分类/状态/价格区间）
        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(dto.getKeyword()), "title", dto.getKeyword())
                .eq(dto.getCategoryId() != null, "category_id", dto.getCategoryId())
                .eq(dto.getStatus() != null, "status", dto.getStatus())
                .ge(dto.getMinPrice() != null, "price", dto.getMinPrice())
                .le(dto.getMaxPrice() != null, "price", dto.getMaxPrice());

        // Step 4: 这里留给你实现排序逻辑（建议做字段白名单，防 SQL 注入）
        applySort(wrapper, dto.getSortBy(), dto.getSortDirection());

        // Step 5: 分页查询
        IPage<Product> productPage = productMapper.selectPage(page, wrapper);

        // Step 6: 组装 VO（你可继续补：主图查询/批量查询优化，避免 N+1）
        List<Long> productIds = productPage.getRecords().stream()
                .map(Product::getId)
                .toList();
        Map<Long, ProductImage> mainImageByProductId = productIds.isEmpty()
                ? Map.of()
                : productImageMapper.selectList(new QueryWrapper<ProductImage>()
                        .in("product_id", productIds)
                        .eq("is_main", 1)
                        .orderByAsc("sort"))
                .stream()
                .collect(Collectors.toMap(
                        ProductImage::getProductId,
                        Function.identity(),
                        (first, ignored) -> first
                ));

        List<ProductListItemVO> voList = new ArrayList<>();
        for (Product product : productPage.getRecords()) {
            ProductListItemVO vo = new ProductListItemVO();
            BeanUtils.copyProperties(product, vo);
            ProductImage mainImage = mainImageByProductId.get(product.getId());
            if (mainImage != null) {
                vo.setMainImageUrl(mainImage.getImageUrl());
            }
            voList.add(vo);
        }

        // Step 7: 封装分页返回
        Page<ProductListItemVO> voPage = new Page<>(
                productPage.getCurrent(),
                productPage.getSize(),
                productPage.getTotal()
        );
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public ProductDetailVO getProductDetail(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品ID不合法");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }

        productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Product>()
                .eq("id", productId)
                .setSql("view_count = COALESCE(view_count, 0) + 1"));
        product.setViewCount((product.getViewCount() == null ? 0 : product.getViewCount()) + 1);

        List<String> imageUrls = productImageMapper.selectList(new QueryWrapper<ProductImage>()
                        .eq("product_id", productId)
                        .orderByAsc("sort"))
                .stream()
                .map(ProductImage::getImageUrl)
                .filter(org.springframework.util.StringUtils::hasText)
                .toList();

        ProductDetailVO detail = new ProductDetailVO();
        BeanUtils.copyProperties(product, detail);
        detail.setImageUrls(imageUrls);
        User seller = product.getSellerId() == null ? null : userMapper.selectById(product.getSellerId());
        if (seller != null) {
            detail.setSellerNickname(org.springframework.util.StringUtils.hasText(seller.getNickname())
                    ? seller.getNickname()
                    : seller.getUsername());
            detail.setSellerAvatar(seller.getAvatar());
            detail.setSellerCreatedTime(seller.getCreatedTime());
        }
        return detail;
    }

    private void validateQuery(ProductQueryDTO dto) {
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询参数不能为空");
        }
        if (dto.getPageNum() == null || dto.getPageNum() < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "页码必须大于等于1");
        }
        if (dto.getPageSize() == null || dto.getPageSize() < 1 || dto.getPageSize() > 50) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "每页数量必须在1-50之间");
        }
        if (dto.getMinPrice() != null && dto.getMaxPrice() != null
                && dto.getMaxPrice().compareTo(dto.getMinPrice()) < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "最大价格不能小于最小价格");
        }
    }

    private void applySort(QueryWrapper<Product> wrapper, String sortBy, String sortDirection) {
        if (!org.springframework.util.StringUtils.hasText(sortBy)){
            sortBy=DEFAULT_SORT_BY;
        }
        if (!org.springframework.util.StringUtils.hasText(sortDirection)){
            sortDirection=DEFAULT_SORT_DIRECTION;
        }
        String sortColumn=SORT_FIELD_MAP.get(sortBy);
        String sortDirection1 = sortDirection.toLowerCase();
        if (sortColumn==null){
            throw new BusinessException(ResultCode.BAD_REQUEST, "sortBy 不合法");
        }
        if (!"asc".equals(sortDirection1)&&!"desc".equals(sortDirection1)){
            throw new BusinessException(ResultCode.BAD_REQUEST, "sortDirection 仅支持 asc 或 desc");
        }
        if ("asc".equals(sortDirection1)){
            wrapper.orderByAsc(sortColumn);
        }else {
            wrapper.orderByDesc(sortColumn);
        }
    }
    private static final Map<String, String> SORT_FIELD_MAP = Map.of(
            "price", "price",
            "createdTime", "created_time",
            "publishTime", "publish_time"
    );
}
