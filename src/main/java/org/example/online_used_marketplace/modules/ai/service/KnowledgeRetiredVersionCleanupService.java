package org.example.online_used_marketplace.modules.ai.service;

/**
 * Removes expired index artifacts for superseded knowledge revisions.
 */
public interface KnowledgeRetiredVersionCleanupService {

    int cleanupExpiredRetiredVersions();
}
