package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;

import java.util.Map;

public interface UserProfileService {

    UserProfileVO buildProfile(String userId, String sessionId);

    UserProfileVO mergeCurrentTurn(UserProfileVO profile, Map<String, String> currentTurnPreferences);

    Map<String, String> toPreferenceMap(UserProfileVO profile);

    String toPromptBlock(UserProfileVO profile);
}
