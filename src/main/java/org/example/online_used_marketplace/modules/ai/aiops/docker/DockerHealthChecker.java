package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.net.URI;
import java.time.Duration;

/** Performs an application-level health check independently of Docker CLI. */
public interface DockerHealthChecker {

    DockerHealthResult check(URI healthUrl, Duration timeout);
}
