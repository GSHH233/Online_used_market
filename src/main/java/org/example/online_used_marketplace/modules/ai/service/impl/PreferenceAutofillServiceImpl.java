package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.service.PreferenceAutofillService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PreferenceAutofillServiceImpl implements PreferenceAutofillService {

    @Override
    public AutofillResult autofill(Map<String, String> explicitParams, Map<String, String> preferenceParams) {
        Map<String, String> merged = new HashMap<>();
        List<String> autofilled = new ArrayList<>();

        if (preferenceParams != null) {
            merged.putAll(preferenceParams);
        }

        if (explicitParams != null) {
            for (Map.Entry<String, String> entry : explicitParams.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (StringUtils.hasText(key) && StringUtils.hasText(val)) {
                    merged.put(key, val.trim());
                }
            }
        }

        if (preferenceParams != null) {
            for (Map.Entry<String, String> entry : preferenceParams.entrySet()) {
                String key = entry.getKey();
                String prefVal = entry.getValue();
                String explicitVal = explicitParams == null ? null : explicitParams.get(key);
                if (StringUtils.hasText(key) && StringUtils.hasText(prefVal) && !StringUtils.hasText(explicitVal)) {
                    autofilled.add(key);
                }
            }
        }

        return new AutofillResult(merged, autofilled);
    }
}
