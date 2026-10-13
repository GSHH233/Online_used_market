package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishAssistProductPublicationValidatorTest {

    private PublishAssistFeedbackService feedback;
    private PublishAssistProductPublicationValidator validator;

    @BeforeEach
    void setUp() {
        feedback = mock(PublishAssistFeedbackService.class);
        validator = new PublishAssistProductPublicationValidator(feedback);
    }

    @Test
    void publishingWithoutAssistDoesNotConsultFeedback() {
        assertDoesNotThrow(() -> validator.validate(new ProductCreateDTO(), 7L));
        verifyNoInteractions(feedback);
    }

    @Test
    void ownershipFailurePropagatesUnchanged() {
        ProductCreateDTO product = product();
        BusinessException denial = new BusinessException(ResultCode.NOT_FOUND, "Not owned");
        when(feedback.getOwned("assist-1", 7L)).thenThrow(denial);

        assertSame(denial, assertThrows(BusinessException.class, () -> validator.validate(product, 7L)));
        verify(feedback).getOwned("assist-1", 7L);
    }

    @Test
    void feedbackReadFailureIsNotTreatedAsPermissionToPublish() {
        IllegalStateException failure = new IllegalStateException("Storage unavailable");
        when(feedback.getOwned("assist-1", 7L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> validator.validate(product(), 7L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLIED", "PARTIAL", " applied ", "partial"})
    void acceptsSupportedTypesAndAllWhitelistedFields(String type) {
        ProductCreateDTO product = product();
        product.setAssistAdoptionType(type);
        product.setAssistAdoptedFields(List.of("title", " description ", "price", "riskAdvice"));

        assertDoesNotThrow(() -> validator.validate(product, 7L));
        verify(feedback).getOwned("assist-1", 7L);
    }

    @Test
    void acceptsIgnoredWithNoFieldsAndUnspecifiedTypeForInference() {
        ProductCreateDTO product = product();
        product.setAssistAdoptionType(" ignored ");
        assertDoesNotThrow(() -> validator.validate(product, 7L));
        product.setAssistAdoptionType(null);
        product.setAssistAdoptedFields(List.of("title"));
        assertDoesNotThrow(() -> validator.validate(product, 7L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "APPLY", "APPLIED;DROP"})
    void rejectsUnknownAdoptionTypes(String type) {
        ProductCreateDTO product = product();
        product.setAssistAdoptionType(type);

        assertThrows(BusinessException.class, () -> validator.validate(product, 7L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown", "", "TITLE", "sellerId"})
    void rejectsUnknownOrNullFieldsEvenWhenTypeIsUnspecified(String field) {
        ProductCreateDTO product = product();
        product.setAssistAdoptedFields(Arrays.asList(field));

        assertThrows(BusinessException.class, () -> validator.validate(product, 7L));
    }

    @Test
    void rejectsPartialWithNoFieldsAndIgnoredWithFields() {
        ProductCreateDTO product = product();
        product.setAssistAdoptionType("PARTIAL");
        assertThrows(BusinessException.class, () -> validator.validate(product, 7L));
        product.setAssistAdoptedFields(List.of());
        assertThrows(BusinessException.class, () -> validator.validate(product, 7L));
        product.setAssistAdoptionType("IGNORED");
        product.setAssistAdoptedFields(List.of("title"));
        assertThrows(BusinessException.class, () -> validator.validate(product, 7L));
    }

    private ProductCreateDTO product() {
        ProductCreateDTO product = new ProductCreateDTO();
        product.setAssistId("assist-1");
        return product;
    }
}
