package org.example.online_used_marketplace.modules.ai.event;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.ai.service.impl.PublishAssistProductLinkWriter;
import org.example.online_used_marketplace.modules.ai.service.impl.PublishAssistProductPublicationValidator;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.example.online_used_marketplace.modules.product.entity.Product;
import org.example.online_used_marketplace.modules.product.entity.ProductImage;
import org.example.online_used_marketplace.modules.product.event.ProductPublishedEvent;
import org.example.online_used_marketplace.modules.product.mapper.ProductImageMapper;
import org.example.online_used_marketplace.modules.product.mapper.ProductMapper;
import org.example.online_used_marketplace.modules.product.service.ProductService;
import org.example.online_used_marketplace.modules.product.service.impl.ProductServiceImpl;
import org.example.online_used_marketplace.modules.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductPublicationTransactionTest {

    private AnnotationConfigApplicationContext context;
    private DataSource dataSource;
    private final List<Connection> connections = new ArrayList<>();
    private PublishAssistFeedbackService feedback;
    private ProductMapper products;
    private ProductImageMapper images;
    private ProductService service;
    private boolean failLinkCommit;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            connections.add(connection);
            if (failLinkCommit && connections.size() == 2) {
                doThrow(new SQLException("token=secret-commit-detail")).when(connection).commit();
            }
            return connection;
        });
        feedback = mock(PublishAssistFeedbackService.class);
        products = mock(ProductMapper.class);
        images = mock(ProductImageMapper.class);
        when(products.insert(any(Product.class))).thenAnswer(invocation -> {
            invocation.<Product>getArgument(0).setId(42L);
            return 1;
        });
        when(images.insert(any(ProductImage.class))).thenReturn(1);

        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PublishAssistFeedbackService.class, () -> feedback);
        context.registerBean(ProductMapper.class, () -> products);
        context.registerBean(ProductImageMapper.class, () -> images);
        context.registerBean(UserMapper.class, () -> mock(UserMapper.class));
        context.register(ProductServiceImpl.class, PublishAssistProductPublicationValidator.class,
                PublishAssistProductLinkWriter.class, ProductPublishedListener.class);
        context.refresh();
        service = context.getBean(ProductService.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void feedbackRunsAfterProductCommitAndCommitsInADistinctTransaction() throws SQLException {
        AtomicBoolean transactionActiveAtLink = new AtomicBoolean();
        AtomicReference<Connection> connectionAtLink = new AtomicReference<>();
        doAnswer(invocation -> {
            transactionActiveAtLink.set(TransactionSynchronizationManager.isActualTransactionActive());
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            connectionAtLink.set(holder == null ? null : holder.getConnection());
            return null;
        }).when(feedback).linkProduct(eq("assist-1"), eq(7L), eq(42L), any(ProductCreateDTO.class));

        assertEquals(42L, service.addProduct(product(), 7L).getProductId());

        // Assert outside the callback: Spring logs callback failures instead of propagating them.
        assertTrue(AopUtils.isAopProxy(context.getBean(PublishAssistProductLinkWriter.class)));
        assertEquals(2, connections.size());
        assertTrue(transactionActiveAtLink.get());
        assertNotNull(connectionAtLink.get());
        assertSame(connections.get(1), connectionAtLink.get());
        assertNotSame(connections.get(0), connectionAtLink.get());
        verify(connections.get(1)).setAutoCommit(false);
        ArgumentCaptor<ProductCreateDTO> snapshot = ArgumentCaptor.forClass(ProductCreateDTO.class);
        var ordered = inOrder(connections.get(0), feedback, connections.get(1));
        ordered.verify(connections.get(0)).commit();
        ordered.verify(feedback).linkProduct(eq("assist-1"), eq(7L), eq(42L), snapshot.capture());
        ordered.verify(connections.get(1)).commit();
        assertEquals("Used textbook", snapshot.getValue().getTitle());
        assertEquals(List.of("title"), snapshot.getValue().getAssistAdoptedFields());
        verify(feedback).getOwned("assist-1", 7L);
    }

    @Test
    void feedbackWriteFailureRollsBackOnlyTheNewTransactionAndKeepsApiSuccessful() throws SQLException {
        doThrow(new IllegalStateException("token=secret-write-detail"))
                .when(feedback).linkProduct(eq("assist-1"), eq(7L), eq(42L), any(ProductCreateDTO.class));

        var result = assertDoesNotThrow(() -> service.addProduct(product(), 7L));

        assertEquals(42L, result.getProductId());
        assertEquals(2, connections.size());
        verify(connections.get(0)).commit();
        verify(connections.get(0), never()).rollback();
        verify(connections.get(1)).rollback();
        verify(connections.get(1), never()).commit();
    }

    @Test
    void feedbackCommitFailureAlsoKeepsTheAlreadyCommittedProductApiSuccessful() throws SQLException {
        failLinkCommit = true;

        var result = assertDoesNotThrow(() -> service.addProduct(product(), 7L));

        assertEquals(42L, result.getProductId());
        assertEquals(2, connections.size());
        verify(connections.get(0)).commit();
        verify(connections.get(0), never()).rollback();
        verify(connections.get(1)).commit();
    }

    @Test
    void rollbackAfterEventPublicationDoesNotRunTheListener() throws SQLException {
        TransactionTemplate transaction = new TransactionTemplate(
                context.getBean("mysqlTransactionManager", PlatformTransactionManager.class));

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.addProduct(product(), 7L);
            verify(feedback, never()).linkProduct(any(), any(), any(), any());
            throw new IllegalStateException("Rollback requested");
        }));

        assertEquals(1, connections.size());
        verify(connections.get(0)).rollback();
        verify(connections.get(0), never()).commit();
        verify(feedback, never()).linkProduct(any(), any(), any(), any());
    }

    @Test
    void postCommitLinkUsesSnapshotEvenIfCallerMutatesDtoBeforeCommit() {
        ProductCreateDTO dto = product();
        TransactionTemplate transaction = new TransactionTemplate(
                context.getBean("mysqlTransactionManager", PlatformTransactionManager.class));

        transaction.executeWithoutResult(status -> {
            service.addProduct(dto, 7L);
            dto.setTitle("Changed after publication");
            dto.setAssistId("changed-assist");
            dto.getImageUrls().clear();
            dto.getAssistAdoptedFields().clear();
            verify(feedback, never()).linkProduct(any(), any(), any(), any());
        });

        ArgumentCaptor<ProductCreateDTO> linked = ArgumentCaptor.forClass(ProductCreateDTO.class);
        verify(feedback).linkProduct(eq("assist-1"), eq(7L), eq(42L), linked.capture());
        assertEquals("Used textbook", linked.getValue().getTitle());
        assertEquals("assist-1", linked.getValue().getAssistId());
        assertEquals(List.of("image-1"), linked.getValue().getImageUrls());
        assertEquals(List.of("title"), linked.getValue().getAssistAdoptedFields());
    }

    @Test
    void imageFailureRollsBackPublicationWithoutOpeningALinkTransaction() throws SQLException {
        when(images.insert(any(ProductImage.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.addProduct(product(), 7L));

        assertEquals(1, connections.size());
        verify(connections.get(0)).rollback();
        verify(connections.get(0), never()).commit();
        verify(feedback, never()).linkProduct(any(), any(), any(), any());
    }

    @Test
    void ownershipDenialRejectsPublicationBeforeEitherInsert() throws SQLException {
        BusinessException denial = new BusinessException(ResultCode.NOT_FOUND, "Not owned");
        when(feedback.getOwned("assist-1", 7L)).thenThrow(denial);

        assertSame(denial, assertThrows(BusinessException.class, () -> service.addProduct(product(), 7L)));

        verifyNoInteractions(products, images);
        verify(feedback, never()).linkProduct(any(), any(), any(), any());
        assertEquals(1, connections.size());
        verify(connections.get(0)).rollback();
        verify(connections.get(0), never()).commit();
    }

    @Test
    void eventPublishedOutsideATransactionIsNotWritten() {
        context.publishEvent(ProductPublishedEvent.snapshot(42L, 7L, product()));

        assertTrue(connections.isEmpty());
        verifyNoInteractions(feedback);
    }

    private ProductCreateDTO product() {
        ProductCreateDTO product = new ProductCreateDTO();
        product.setTitle("Used textbook");
        product.setDescription("Good condition");
        product.setPrice(BigDecimal.TEN);
        product.setConditionLevel(8);
        product.setImageUrls(new ArrayList<>(List.of("image-1")));
        product.setAssistId("assist-1");
        product.setAssistAdoptionType("PARTIAL");
        product.setAssistAdoptedFields(new ArrayList<>(List.of("title")));
        return product;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {

        @Bean("mysqlTransactionManager")
        PlatformTransactionManager mysqlTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
