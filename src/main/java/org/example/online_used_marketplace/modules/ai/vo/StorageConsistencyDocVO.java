package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class StorageConsistencyDocVO {

    private Long docId;

    private Integer dbCount;

    private Integer esCount;

    private Integer milvusCount;

    private List<Long> dbChunkIds;

    private List<Long> esChunkIds;

    private List<Long> milvusChunkIds;

    private List<Long> missingInEs;

    private List<Long> missingInMilvus;

    private List<Long> extraInEs;

    private List<Long> extraInMilvus;

    private Boolean consistent;
}
