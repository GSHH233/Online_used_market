package org.example.online_used_marketplace.modules.ai.service;

import java.util.Map;

public interface PreferenceAutofillService {

    AutofillResult autofill(Map<String, String> explicitParams, Map<String, String> preferenceParams);

    class AutofillResult {
        private final Map<String, String> mergedParams;
        private final java.util.List<String> autofilledFields;

        public AutofillResult(Map<String, String> mergedParams, java.util.List<String> autofilledFields) {
            this.mergedParams = mergedParams;
            this.autofilledFields = autofilledFields;
        }

        public Map<String, String> getMergedParams() {
            return mergedParams;
        }

        public java.util.List<String> getAutofilledFields() {
            return autofilledFields;
        }
    }
}
