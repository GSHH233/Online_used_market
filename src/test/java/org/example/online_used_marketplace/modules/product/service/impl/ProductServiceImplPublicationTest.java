package org.example.online_used_marketplace.modules.product.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.example.online_used_marketplace.modules.product.service.ProductPublicationValidator;
import org.example.online_used_marketplace.modules.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductServiceImplPublicationTest {

    private ProductMapper products;
    private ProductImageMapper images;
    private ProductPublicationValidator firstValidator;
    private ProductPublicationValidator secondValidator;
    private ApplicationEventPublisher publisher;
    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        products = mock(ProductMapper.class);
        images = mock(ProductImageMapper.class);
        firstValidator = mock(ProductPublicationValidator.class);
        secondValidator = mock(ProductPublicationValidator.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new ProductServiceImpl(products, images, mock(UserMapper.class),
                List.of(firstValidator, secondValidator), publisher);
        when(products.insert(any(Product.class))).thenAnswer(invocation -> {
            invocation.<Product>getArgument(0).setId(42L);
            return 1;
        });
        when(images.insert(any(ProductImage.class))).thenReturn(1);
    }

    @Test
    void validatesEveryExtensionBeforeInsertingAndPublishesOnlyAfterImages() {
        ProductCreateDTO dto = product();

        var result = service.addProduct(dto, 7L);

        var ordered = inOrder(firstValidator, secondValidator, products, images, publisher);
        ordered.verify(firstValidator).validate(dto, 7L);
        ordered.verify(secondValidator).validate(dto, 7L);
        ArgumentCaptor<Product> inserted = ArgumentCaptor.forClass(Product.class);
        ordered.verify(products).insert(inserted.capture());
        ordered.verify(images).insert(any(ProductImage.class));
        ArgumentCaptor<ProductPublishedEvent> event = ArgumentCaptor.forClass(ProductPublishedEvent.class);
        ordered.verify(publisher).publishEvent(event.capture());
        assertEquals(ProductStatus.ON_SALE.getCode(), inserted.getValue().getStatus());
        assertEquals(7L, inserted.getValue().getSellerId());
        assertEquals(42L, result.getProductId());
        assertEquals(ProductStatus.ON_SALE.getCode(), result.getStatus());
        assertEquals(dto.getTitle(), event.getValue().title());
        assertEquals(42L, event.getValue().productId());
        assertEquals(7L, event.getValue().sellerId());
    }

    @Test
    void businessValidationFailureIsNotSwallowedAndWritesNothing() {
        ProductCreateDTO dto = product();
        BusinessException denial = new BusinessException(ResultCode.NOT_FOUND, "Not owned");
        doThrow(denial).when(secondValidator).validate(dto, 7L);

        assertSame(denial, assertThrows(BusinessException.class, () -> service.addProduct(dto, 7L)));

        verifyNoInteractions(products, images, publisher);
    }

    @Test
    void failedProductInsertDoesNotPublishAnEventOrInsertImages() {
        when(products.insert(any(Product.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.addProduct(product(), 7L));

        verifyNoInteractions(images, publisher);
    }

    @Test
    void insertWithoutGeneratedIdDoesNotPublishAnEventOrInsertImages() {
        when(products.insert(any(Product.class))).thenReturn(1);

        assertThrows(BusinessException.class, () -> service.addProduct(product(), 7L));

        verifyNoInteractions(images, publisher);
    }

    @Test
    void failedImageInsertDoesNotPublishAnEvent() {
        ProductCreateDTO dto = product();
        dto.setImageUrls(List.of("image-1", "image-2"));
        when(images.insert(any(ProductImage.class))).thenReturn(1, 0);

        assertThrows(BusinessException.class, () -> service.addProduct(dto, 7L));

        verifyNoInteractions(publisher);
    }

    @Test
    void productPublishingWorksWithoutOptionalValidators() {
        service = new ProductServiceImpl(products, images, mock(UserMapper.class), List.of(), publisher);

        assertEquals(42L, service.addProduct(product(), 7L).getProductId());
        verify(publisher).publishEvent(any(ProductPublishedEvent.class));
    }

    @Test
    void listUsesThePaginationTotalEvenOnAnEmptyPageWithoutAnotherCountQuery() {
        Page<Product> page = new Page<>(4, 10, 27);
        page.setRecords(List.of());
        when(products.selectPage(ArgumentMatchers.<Page<Product>>any(), any())).thenReturn(page);

        var result = service.listProduct(new ProductQueryDTO());

        assertEquals(27, result.getTotal());
        assertEquals(4, result.getCurrent());
        assertEquals(10, result.getSize());
        verify(products, never()).selectCount(any());
        verifyNoInteractions(images);
    }

    @Test
    void nullQueryAndNullProductAreBusinessErrors() {
        assertThrows(BusinessException.class, () -> service.listProduct(null));
        assertThrows(BusinessException.class, () -> service.addProduct(null, 7L));
        verifyNoInteractions(products, images, publisher, firstValidator, secondValidator);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsNullOrNonpositivePageNumber(Long pageNum) {
        ProductQueryDTO query = new ProductQueryDTO();
        query.setPageNum(pageNum);

        assertThrows(BusinessException.class, () -> service.listProduct(query));
        verifyNoInteractions(products, images);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, 51})
    void rejectsPageSizeOutsideOneToFifty(Long pageSize) {
        ProductQueryDTO query = new ProductQueryDTO();
        query.setPageSize(pageSize);

        assertThrows(BusinessException.class, () -> service.listProduct(query));
        verifyNoInteractions(products, images);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 50})
    void acceptsInclusivePageSizeBoundaries(Long pageSize) {
        ProductQueryDTO query = new ProductQueryDTO();
        query.setPageSize(pageSize);
        when(products.selectPage(ArgumentMatchers.<Page<Product>>any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(pageSize.longValue(), service.listProduct(query).getSize());
    }

    private ProductCreateDTO product() {
        ProductCreateDTO product = new ProductCreateDTO();
        product.setTitle("Used textbook");
        product.setPrice(BigDecimal.TEN);
        product.setConditionLevel(8);
        product.setImageUrls(List.of("image-1"));
        return product;
    }
}
