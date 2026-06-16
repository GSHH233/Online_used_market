package org.example.online_used_marketplace.modules.ai.config;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.service.impl.LocalVectorStoreImpl;
import org.example.online_used_marketplace.modules.ai.service.impl.MilvusVectorStoreImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * VectorStore 提供者切换配置
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AiVectorStoreProperties.class)
public class VectorStoreConfig {

    private final AiVectorStoreProperties properties;

    @Bean
    public VectorStore vectorStore(LocalVectorStoreImpl localVectorStore,
                                   MilvusVectorStoreImpl milvusVectorStore) {
        String provider = properties.getProvider();
        if (provider != null && provider.trim().equalsIgnoreCase("milvus")) {
            return milvusVectorStore;
        }
        return localVectorStore;
    }
}
