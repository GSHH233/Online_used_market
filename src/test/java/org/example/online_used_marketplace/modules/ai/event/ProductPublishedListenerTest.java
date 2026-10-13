package org.example.online_used_marketplace.modules.ai.event;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.online_used_marketplace.modules.ai.service.impl.PublishAssistProductLinkWriter;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.example.online_used_marketplace.modules.product.event.ProductPublishedEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProductPublishedListenerTest {

    @Test
    void skipsProductsWithoutAssist() {
        PublishAssistProductLinkWriter writer = mock(PublishAssistProductLinkWriter.class);
        ProductPublishedListener listener = new ProductPublishedListener(writer);
        ProductCreateDTO product = new ProductCreateDTO();

        listener.onProductPublished(ProductPublishedEvent.snapshot(42L, 7L, product));
        product.setAssistId("  ");
        listener.onProductPublished(ProductPublishedEvent.snapshot(43L, 7L, product));

        verifyNoInteractions(writer);
    }

    @Test
    void associationFailureDoesNotEscapeOrLogSensitiveContent() {
        PublishAssistProductLinkWriter writer = mock(PublishAssistProductLinkWriter.class);
        ProductPublishedListener listener = new ProductPublishedListener(writer);
        ProductCreateDTO product = new ProductCreateDTO();
        product.setAssistId("assist-1\r\n");
        product.setTitle("private-title");
        product.setDescription("private-description");
        ProductPublishedEvent event = ProductPublishedEvent.snapshot(42L, 7L, product);
        doThrow(new IllegalStateException("token=secret-token private-description"))
                .when(writer).linkProduct(event);
        Logger logger = (Logger) LoggerFactory.getLogger(ProductPublishedListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> listener.onProductPublished(event));

            verify(writer).linkProduct(event);
            assertEquals(1, appender.list.size());
            ILoggingEvent logged = appender.list.get(0);
            String message = logged.getFormattedMessage();
            assertTrue(message.contains("productId=42"));
            assertTrue(message.contains("assistId=assist-1__"));
            assertTrue(message.contains("IllegalStateException"));
            assertFalse(message.contains("secret-token"));
            assertFalse(message.contains("private-title"));
            assertFalse(message.contains("private-description"));
            assertFalse(message.contains("\r"));
            assertFalse(message.contains("\n"));
            assertNull(logged.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
