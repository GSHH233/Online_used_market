package org.example.online_used_marketplace.modules.ai.service.impl;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.springframework.stereotype.Service;

@Service
public class Cl100kTokenCounterService implements TokenCounterService {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    @Override
    public int count(String text) {
        return text == null || text.isBlank() ? 0 : encoding.countTokens(text);
    }
}
