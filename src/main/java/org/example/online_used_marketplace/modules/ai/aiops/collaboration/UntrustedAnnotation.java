package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UntrustedAnnotation {

    private String key;

    private String value;

    @Builder.Default
    private TrustLevel trustLevel = TrustLevel.UNTRUSTED;

    public static UntrustedAnnotation fromExternal(String key, String value) {
        return UntrustedAnnotation.builder()
                .key(key)
                .value(value)
                .trustLevel(TrustLevel.UNTRUSTED)
                .build();
    }
}
