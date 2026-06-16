package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量存储配置
 */
@Data
@ConfigurationProperties(prefix = "ai.vector")
public class AiVectorStoreProperties {

    /**
     * local | milvus
     */
    private String provider = "local";

    private Milvus milvus = new Milvus();

    @Data
    public static class Milvus {
        /**
         * Docker 内 Milvus 对外暴露地址，例如 localhost
         */
        private String host = "localhost";

        /**
         * Docker 内 Milvus gRPC 端口，默认 19530
         */
        private Integer port = 19530;

        /**
         * 集合名称
         */
        private String collectionName = "ai_knowledge_chunk";

        /**
         * 向量维度（需与 embedding 模型一致）
         */
        private Integer dimension = 1536;

        /**
         * 是否自动初始化集合骨架
         */
        private Boolean autoInitCollection = true;
    }
}
