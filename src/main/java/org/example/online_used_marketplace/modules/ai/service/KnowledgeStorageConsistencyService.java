package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencyDocVO;
import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencyReportVO;

import java.util.List;

public interface KnowledgeStorageConsistencyService {

    StorageConsistencyReportVO inspect(Long docId, Integer sampleDocs, Integer perDocLimit);

    StorageConsistencyDocVO inspectDoc(Long docId, Integer perDocLimit);

    List<Long> resolveDocIds(Long docId, Integer sampleDocs);
}
