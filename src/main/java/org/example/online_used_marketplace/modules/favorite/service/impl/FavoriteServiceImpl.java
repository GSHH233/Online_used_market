package org.example.online_used_marketplace.modules.favorite.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.favorite.dto.FavoriteQueryDTO;
import org.example.online_used_marketplace.modules.favorite.entity.Favorite;
import org.example.online_used_marketplace.modules.favorite.mapper.FavoriteMapper;
import org.example.online_used_marketplace.modules.favorite.service.FavoriteService;
import org.example.online_used_marketplace.modules.favorite.vo.FavoriteListItemVO;
import org.example.online_used_marketplace.modules.product.entity.Product;
import org.example.online_used_marketplace.modules.product.entity.ProductImage;
import org.example.online_used_marketplace.modules.product.mapper.ProductImageMapper;
import org.example.online_used_marketplace.modules.product.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final ProductMapper productMapper;
    private final ProductImageMapper productImageMapper;

    @Override
    public void addFavorite(Long productId, Long currentUserId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        Favorite exists = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, currentUserId)
                .eq(Favorite::getProductId, productId)
                .last("limit 1"));
        if (exists != null) {
            return;
        }

        Favorite favorite = new Favorite();
        favorite.setUserId(currentUserId);
        favorite.setProductId(productId);
        try{
            favoriteMapper.insert(favorite);
            Integer favoriteCount = product.getFavoriteCount();
            if (favoriteCount==null){
                throw new BusinessException(ResultCode.INTERNAL_ERROR,"查询错误");
            }
            product.setFavoriteCount(favoriteCount+1);
            productMapper.updateById(product);
        }catch (DuplicateKeyException e){
            log.warn("插入成功");
            return;
        }

    }

    @Override
    public void removeFavorite(Long productId, Long currentUserId) {
        int rows = favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, currentUserId)
                .eq(Favorite::getProductId, productId));
        if (rows<0){
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"删除失败");
        }
    }

    @Override
    public IPage<FavoriteListItemVO> listFavorites(FavoriteQueryDTO dto, Long currentUserId) {
        if (dto.getPageNum() < 1 || dto.getPageSize() < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数错误");
        }

        Page<Favorite> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        LambdaQueryWrapper<Favorite> queryWrapper = new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, currentUserId)
                .orderByDesc(Favorite::getCreatedTime);
        long total = favoriteMapper.selectCount(queryWrapper);
        IPage<Favorite> favoritePage = favoriteMapper.selectPage(page, queryWrapper);

        List<Favorite> favorites = favoritePage.getRecords();
        List<Long> productIds = favorites.stream().map(Favorite::getProductId).collect(Collectors.toList());

        Map<Long, Product> productMap = productIds.isEmpty() ? Map.of() : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        Map<Long, ProductImage> mainImageMap = productIds.isEmpty() ? Map.of() : productImageMapper.selectList(new LambdaQueryWrapper<ProductImage>()
                        .in(ProductImage::getProductId, productIds)
                        .eq(ProductImage::getIsMain, 1))
                .stream()
                .collect(Collectors.toMap(ProductImage::getProductId, i -> i, (a, b) -> a));

        List<FavoriteListItemVO> voList = new ArrayList<>();
        for (Favorite favorite : favorites) {
            Product product = productMap.get(favorite.getProductId());
            if (product == null) {
                continue;
            }
            FavoriteListItemVO vo = new FavoriteListItemVO();
            vo.setId(favorite.getId());
            vo.setProductId(product.getId());
            vo.setProductTitle(product.getTitle());
            vo.setPrice(product.getPrice());
            vo.setStatus(product.getStatus());
            vo.setCreatedTime(favorite.getCreatedTime());
            ProductImage mainImage = mainImageMap.get(product.getId());
            vo.setProductMainImage(mainImage != null ? mainImage.getImageUrl() : null);
            voList.add(vo);
        }

        Page<FavoriteListItemVO> voPage = new Page<>(favoritePage.getCurrent(), favoritePage.getSize(), total);
        voPage.setRecords(voList);
        return voPage;
    }
}
