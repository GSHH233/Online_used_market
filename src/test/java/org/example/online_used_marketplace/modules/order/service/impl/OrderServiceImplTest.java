package org.example.online_used_marketplace.modules.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.order.dto.OrderCreateDTO;
import org.example.online_used_marketplace.modules.order.dto.OrderQueryDTO;
import org.example.online_used_marketplace.modules.order.entity.Order;
import org.example.online_used_marketplace.modules.order.entity.OrderItem;
import org.example.online_used_marketplace.modules.order.enums.OrderRoleType;
import org.example.online_used_marketplace.modules.order.enums.OrderStatus;
import org.example.online_used_marketplace.modules.order.mapper.OrderItemMapper;
import org.example.online_used_marketplace.modules.order.mapper.OrderMapper;
import org.example.online_used_marketplace.modules.order.service.OrderService;
import org.example.online_used_marketplace.modules.order.vo.OrderCreateVO;
import org.example.online_used_marketplace.modules.order.vo.OrderListItemVO;
import org.example.online_used_marketplace.modules.product.entity.Product;
import org.example.online_used_marketplace.modules.product.enums.ProductStatus;
import org.example.online_used_marketplace.modules.product.mapper.ProductImageMapper;
import org.example.online_used_marketplace.modules.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceImplTest {
    private ProductMapper products;
    private OrderMapper orders;
    private OrderItemMapper items;
    private ProductImageMapper images;
    private OrderServiceImpl service;

    @BeforeAll
    static void initializeMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "order-test");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        products = mock(ProductMapper.class);
        orders = mock(OrderMapper.class);
        items = mock(OrderItemMapper.class);
        images = mock(ProductImageMapper.class);
        service = new OrderServiceImpl(products, orders, items, images);
    }

    @Test
    void createLocksProductBeforeInsertingOrderAndSnapshot() {
        stubCreate();
        OrderCreateVO result = service.createOrder(createRequest(), 10L);
        InOrder sequence = inOrder(products, orders, items);
        sequence.verify(products).selectById(30L);
        sequence.verify(products).update(isNull(), any(Wrapper.class));
        sequence.verify(orders).insert(any(Order.class));
        sequence.verify(items).insert(any(OrderItem.class));

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orders).insert(order.capture());
        assertEquals(40L, result.getOrderId());
        assertTrue(Long.parseLong(result.getOrderNo()) > 0);
        assertEquals(result.getOrderNo(), order.getValue().getOrderNo());
        assertEquals(OrderStatus.PENDING_PAY.getCode(), order.getValue().getStatus());
        assertEquals(new BigDecimal("125.00"), order.getValue().getOrderAmount());
        ArgumentCaptor<OrderItem> item = ArgumentCaptor.forClass(OrderItem.class);
        verify(items).insert(item.capture());
        assertEquals(40L, item.getValue().getOrderId());
        assertEquals("Used camera", item.getValue().getProductTitle());
        assertProductUpdate(ProductStatus.ON_SALE, ProductStatus.LOCKED);
        verify(products, never()).updateById(any(Product.class));
    }

    @Test
    void concurrentBuyersReadingSameOnSaleProductOnlyInsertOneOrder() throws Exception {
        CyclicBarrier readers = new CyclicBarrier(2);
        when(products.selectById(30L)).thenAnswer(invocation -> {
            Product snapshot = product();
            readers.await(5, TimeUnit.SECONDS);
            return snapshot;
        });
        AtomicBoolean locked = new AtomicBoolean();
        when(products.update(isNull(), any(Wrapper.class)))
                .thenAnswer(invocation -> locked.compareAndSet(false, true) ? 1 : 0);
        stubInserts();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> tryCreate(10L));
            Future<Boolean> second = executor.submit(() -> tryCreate(11L));
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        verify(orders, times(1)).insert(any(Order.class));
        verify(items, times(1)).insert(any(OrderItem.class));
    }

    @Test
    void lostProductLockDoesNotInsertAnything() {
        when(products.selectById(30L)).thenReturn(product());
        BusinessException error = assertBusinessConflict(() -> service.createOrder(createRequest(), 10L));
        assertEquals("商品已被他人下单，请刷新", error.getMessage());
        verifyNoInteractions(orders, items, images);
        assertProductUpdate(ProductStatus.ON_SALE, ProductStatus.LOCKED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3, 4})
    void unavailableProductNeverAttemptsToLock(int status) {
        Product unavailable = product();
        unavailable.setStatus(status);
        when(products.selectById(30L)).thenReturn(unavailable);
        assertBusinessConflict(() -> service.createOrder(createRequest(), 10L));
        verify(products, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(orders, items, images);
    }

    @Test
    void sellerCannotLockOwnProduct() {
        when(products.selectById(30L)).thenReturn(product());
        assertThrows(BusinessException.class, () -> service.createOrder(createRequest(), 20L));
        verify(products, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(orders, items, images);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createFailureAfterLockRollsBackThroughTransactionProxy(boolean snapshotFailure) {
        when(products.selectById(30L)).thenReturn(product());
        when(products.update(isNull(), any(Wrapper.class))).thenReturn(1);
        if (snapshotFailure) {
            when(orders.insert(any(Order.class))).thenAnswer(invocation -> {
                invocation.<Order>getArgument(0).setId(40L);
                return 1;
            });
        }
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        OrderService transactional = transactionProxy(manager);
        assertThrows(BusinessException.class, () -> transactional.createOrder(createRequest(), 10L));
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "DELIVERED", "FINISHED", "CANCELLED"})
    void validActionUsesIdAndFromStatusCasAndOnlyPatchesTransitionFields(OrderStatus target) {
        Order original = order(source(target));
        when(orders.selectById(40L)).thenReturn(original);
        when(orders.update(any(Order.class), any(Wrapper.class))).thenReturn(1);
        when(products.update(isNull(), any(Wrapper.class))).thenReturn(1);
        act(service, target, actor(target));
        ArgumentCaptor<Order> changes = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<Wrapper<Order>> condition = wrapperCaptor();
        verify(orders).update(changes.capture(), condition.capture());
        assertBinding(condition.getValue(), "id", 40L);
        assertBinding(condition.getValue(), "status", source(target).getCode());
        assertEquals(target.getCode(), changes.getValue().getStatus());
        assertNull(changes.getValue().getOrderAmount());
        assertNull(changes.getValue().getBuyerId());
        assertNotNull(changes.getValue().getUpdatedTime());
        assertEquals(source(target).getCode(), original.getStatus());
        switch (target) {
            case PAID -> assertNotNull(changes.getValue().getPayTime());
            case DELIVERED -> assertNotNull(changes.getValue().getDeliverTime());
            case FINISHED -> assertNotNull(changes.getValue().getFinishTime());
            case CANCELLED -> assertNotNull(changes.getValue().getCancelTime());
            default -> fail();
        }
        if (target == OrderStatus.FINISHED || target == OrderStatus.CANCELLED) {
            InOrder sequence = inOrder(orders, products);
            sequence.verify(orders).update(any(Order.class), any(Wrapper.class));
            sequence.verify(products).update(isNull(), any(Wrapper.class));
            assertProductUpdate(ProductStatus.LOCKED,
                    target == OrderStatus.FINISHED ? ProductStatus.SOLD : ProductStatus.ON_SALE);
        } else {
            verifyNoInteractions(products);
        }
        verify(orders, never()).updateById(any(Order.class));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "DELIVERED", "FINISHED", "CANCELLED"})
    void staleActionFailsBeforeTouchingProductEvenIfANewOrderHasLockedIt(OrderStatus target) {
        when(orders.selectById(40L)).thenReturn(order(source(target)));
        BusinessException error = assertBusinessConflict(() -> act(service, target, actor(target)));
        assertEquals("订单状态已变更，请刷新后重试", error.getMessage());
        verifyNoInteractions(products);
    }

    @Test
    void oldCancelAfterAnotherBuyerCreatesOrderCannotReleaseNewLock() {
        when(orders.selectById(40L)).thenReturn(order(OrderStatus.PENDING_PAY));
        when(orders.update(any(Order.class), any(Wrapper.class))).thenReturn(1, 0);
        when(products.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(products.selectById(30L)).thenReturn(product());
        when(orders.insert(any(Order.class))).thenAnswer(invocation -> {
            invocation.<Order>getArgument(0).setId(41L);
            return 1;
        });
        when(items.insert(any(OrderItem.class))).thenReturn(1);
        service.cancelOrder(40L, 10L);
        assertEquals(41L, service.createOrder(createRequest(), 11L).getOrderId());
        assertBusinessConflict(() -> service.cancelOrder(40L, 10L));
        ArgumentCaptor<Wrapper<Product>> conditions = wrapperCaptor();
        verify(products, times(2)).update(isNull(), conditions.capture());
        assertBinding(conditions.getAllValues().get(0), "status", ProductStatus.LOCKED.getCode());
        assertBinding(conditions.getAllValues().get(1), "status", ProductStatus.ON_SALE.getCode());
        verify(orders, times(1)).insert(any(Order.class));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"FINISHED", "CANCELLED"})
    void productConflictRollsBackWinningOrderTransition(OrderStatus target) {
        when(orders.selectById(40L)).thenReturn(order(source(target)));
        when(orders.update(any(Order.class), any(Wrapper.class))).thenReturn(1);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        assertBusinessConflict(() -> act(transactionProxy(manager), target, actor(target)));
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "DELIVERED", "FINISHED", "CANCELLED"})
    void unauthorizedActionNeverUpdatesAnything(OrderStatus target) {
        when(orders.selectById(40L)).thenReturn(order(source(target)));
        BusinessException error = assertThrows(BusinessException.class, () -> act(service, target, 99L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), error.getCode());
        verify(orders, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(products);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "DELIVERED", "FINISHED", "CANCELLED"})
    void terminalOrderRejectsEveryAction(OrderStatus target) {
        when(orders.selectById(40L)).thenReturn(order(OrderStatus.CANCELLED));
        assertBusinessConflict(() -> act(service, target, actor(target)));
        verify(orders, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(products);
    }

    @Test
    void pageUsesSelectPageTotalAndBatchesSnapshots() {
        OrderQueryDTO query = new OrderQueryDTO();
        query.setRole(OrderRoleType.SELLER);
        query.setStatus(OrderStatus.PAID.getCode());
        Page<Order> page = new Page<>(2, 10, 27);
        page.setRecords(List.of(order(OrderStatus.PAID)));
        when(orders.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        OrderItem snapshot = new OrderItem();
        snapshot.setOrderId(40L);
        snapshot.setProductTitle("Snapshot title");
        when(items.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
        IPage<OrderListItemVO> result = service.listOrders(query, 20L);
        assertEquals(27, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals("Snapshot title", result.getRecords().get(0).getProductTitle());
        ArgumentCaptor<Wrapper<Order>> condition = wrapperCaptor();
        verify(orders).selectPage(any(Page.class), condition.capture());
        assertBinding(condition.getValue(), "seller_id", 20L);
        assertBinding(condition.getValue(), "status", OrderStatus.PAID.getCode());
        verify(orders, never()).selectCount(any());
        verify(items, times(1)).selectList(any(Wrapper.class));
    }

    @Test
    void emptyPageKeepsTotalWithoutQueryingSnapshots() {
        when(orders.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<Order>(4, 10, 27));
        assertEquals(27, service.listOrders(new OrderQueryDTO(), 10L).getTotal());
        verifyNoInteractions(items);
        verify(orders, never()).selectCount(any());
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void invalidPaginationFailsBeforeQueryingMappers(OrderQueryDTO query, String message) {
        BusinessException error = assertBusinessConflict(() -> service.listOrders(query, 10L));
        assertEquals(message, error.getMessage());
        verifyNoInteractions(orders, items, products, images);
    }

    @Test
    void pageSizeOneHundredIsAcceptedForDirectServiceCalls() {
        OrderQueryDTO query = new OrderQueryDTO();
        query.setPageSize(100L);
        when(orders.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Order> page = invocation.getArgument(0);
            assertEquals(100, page.getSize());
            return page;
        });
        assertEquals(100, service.listOrders(query, 10L).getSize());
        verifyNoInteractions(items);
    }

    private static Stream<Arguments> invalidPages() {
        return Stream.concat(
                Stream.of(Arguments.of(null, "查询参数不能为空")),
                Stream.concat(
                        Stream.of((Long) null, 0L, -1L).map(pageNum -> {
                            OrderQueryDTO query = new OrderQueryDTO();
                            query.setPageNum(pageNum);
                            return Arguments.of(query, "页码必须大于等于1");
                        }),
                        Stream.of((Long) null, 0L, -1L, 101L).map(pageSize -> {
                            OrderQueryDTO query = new OrderQueryDTO();
                            query.setPageSize(pageSize);
                            return Arguments.of(query, "每页数量必须在1-100之间");
                        })));
    }

    private void stubCreate() {
        when(products.selectById(30L)).thenReturn(product());
        when(products.update(isNull(), any(Wrapper.class))).thenReturn(1);
        stubInserts();
    }

    private void stubInserts() {
        when(orders.insert(any(Order.class))).thenAnswer(invocation -> {
            invocation.<Order>getArgument(0).setId(40L);
            return 1;
        });
        when(items.insert(any(OrderItem.class))).thenReturn(1);
    }

    private boolean tryCreate(Long buyer) {
        try {
            service.createOrder(createRequest(), buyer);
            return true;
        } catch (BusinessException error) {
            assertEquals(ResultCode.BAD_REQUEST.getCode(), error.getCode());
            assertEquals("商品已被他人下单，请刷新", error.getMessage());
            return false;
        }
    }

    private OrderService transactionProxy(PlatformTransactionManager manager) {
        ProxyFactory proxy = new ProxyFactory(service);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mysqlTransactionManager", manager);
        TransactionInterceptor interceptor = new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
        interceptor.setBeanFactory(beans);
        proxy.addAdvice(interceptor);
        return (OrderService) proxy.getProxy();
    }

    private void assertProductUpdate(ProductStatus from, ProductStatus target) {
        ArgumentCaptor<Wrapper<Product>> condition = wrapperCaptor();
        verify(products).update(isNull(), condition.capture());
        assertBinding(condition.getValue(), "id", 30L);
        assertBinding(condition.getValue(), "status", from.getCode());
        LambdaUpdateWrapper<?> update = (LambdaUpdateWrapper<?>) condition.getValue();
        assertBinding(update.getSqlSet(), update, "status", target.getCode());
        assertTrue(update.getSqlSet().contains("updated_time="));
    }

    private static void assertBinding(Wrapper<?> wrapper, String column, Object expected) {
        assertBinding(wrapper.getSqlSegment(), (AbstractWrapper<?, ?, ?>) wrapper, column, expected);
    }

    private static void assertBinding(String sql, AbstractWrapper<?, ?, ?> wrapper, String column, Object expected) {
        Matcher binding = Pattern.compile("\\b" + column + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(sql);
        assertTrue(binding.find(), sql);
        assertEquals(expected, wrapper.getParamNameValuePairs().get(binding.group(1)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static BusinessException assertBusinessConflict(Runnable action) {
        BusinessException error = assertThrows(BusinessException.class, action::run);
        assertEquals(ResultCode.BAD_REQUEST.getCode(), error.getCode());
        return error;
    }

    private static OrderStatus source(OrderStatus target) {
        return switch (target) {
            case PAID, CANCELLED -> OrderStatus.PENDING_PAY;
            case DELIVERED -> OrderStatus.PAID;
            case FINISHED -> OrderStatus.DELIVERED;
            default -> throw new IllegalArgumentException();
        };
    }

    private static long actor(OrderStatus target) {
        return target == OrderStatus.DELIVERED ? 20L : 10L;
    }

    private static void act(OrderService service, OrderStatus target, Long user) {
        switch (target) {
            case PAID -> service.payOrder(40L, user);
            case DELIVERED -> service.deliverOrder(40L, user);
            case FINISHED -> service.finishOrder(40L, user);
            case CANCELLED -> service.cancelOrder(40L, user);
            default -> throw new IllegalArgumentException();
        }
    }

    private static Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(40L);
        order.setProductId(30L);
        order.setBuyerId(10L);
        order.setSellerId(20L);
        order.setStatus(status.getCode());
        return order;
    }

    private static Product product() {
        Product product = new Product();
        product.setId(30L);
        product.setSellerId(20L);
        product.setStatus(ProductStatus.ON_SALE.getCode());
        product.setTitle("Used camera");
        product.setPrice(new BigDecimal("125.00"));
        return product;
    }

    private static OrderCreateDTO createRequest() {
        OrderCreateDTO request = new OrderCreateDTO();
        request.setProductId(30L);
        return request;
    }
}
