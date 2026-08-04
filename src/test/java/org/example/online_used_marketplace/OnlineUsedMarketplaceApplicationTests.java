package org.example.online_used_marketplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires configured OpenAI, MySQL, Redis, Elasticsearch and Milvus integration services")
class OnlineUsedMarketplaceApplicationTests {

    @Test
    void contextLoads() {
    }

}
