package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.config.EmbeddingBatchProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceImplTest {

    @Test
    void splitsProviderRequestsIntoBatchesOfAtMostTenAndKeepsOrder() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        EmbeddingBatchProperties properties = new EmbeddingBatchProperties();
        properties.setMaxBatchSize(10);
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(model, properties);
        List<String> inputs = IntStream.range(0, 25).mapToObj(String::valueOf).toList();

        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(batch.size());
            for (String value : batch) {
                vectors.add(new float[]{Float.parseFloat(value)});
            }
            return vectors;
        });

        List<float[]> result = service.embed(inputs);

        assertEquals(25, result.size());
        for (int index = 0; index < result.size(); index++) {
            assertEquals(index, result.get(index)[0]);
        }
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, times(3)).embed(captor.capture());
        assertEquals(List.of(10, 10, 5), captor.getAllValues().stream().map(List::size).toList());
    }
}
