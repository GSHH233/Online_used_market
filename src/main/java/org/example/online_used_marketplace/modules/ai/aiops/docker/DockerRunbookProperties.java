package org.example.online_used_marketplace.modules.ai.aiops.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "ai.aiops.docker-runbook")
public class DockerRunbookProperties {

    private boolean enabled;
    private String transport = "ssh-helper";
    private String targetId = "marketplace-demo-web";
    private String containerName = "used-market-runbook-demo";
    private String rollbackImage = "nginx:1.27-alpine";
    private URI healthUrl = URI.create("http://127.0.0.1:18080/");
    private int hostPort = 18080;
    private int containerPort = 80;
    private int restartGraceSeconds = 10;
    private long commandTimeoutMs = 30_000L;
    private long healthTimeoutMs = 3_000L;
    private long healthPollIntervalMs = 500L;
    private int maxHealthAttempts = 5;
    private String dockerExecutable = "docker";
    private final Ssh ssh = new Ssh();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }
    public String getRollbackImage() { return rollbackImage; }
    public void setRollbackImage(String rollbackImage) { this.rollbackImage = rollbackImage; }
    public URI getHealthUrl() { return healthUrl; }
    public void setHealthUrl(URI healthUrl) { this.healthUrl = healthUrl; }
    public int getHostPort() { return hostPort; }
    public void setHostPort(int hostPort) { this.hostPort = hostPort; }
    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
    public int getRestartGraceSeconds() { return restartGraceSeconds; }
    public void setRestartGraceSeconds(int restartGraceSeconds) { this.restartGraceSeconds = restartGraceSeconds; }
    public long getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(long commandTimeoutMs) { this.commandTimeoutMs = commandTimeoutMs; }
    public long getHealthTimeoutMs() { return healthTimeoutMs; }
    public void setHealthTimeoutMs(long healthTimeoutMs) { this.healthTimeoutMs = healthTimeoutMs; }
    public long getHealthPollIntervalMs() { return healthPollIntervalMs; }
    public void setHealthPollIntervalMs(long healthPollIntervalMs) { this.healthPollIntervalMs = healthPollIntervalMs; }
    public int getMaxHealthAttempts() { return maxHealthAttempts; }
    public void setMaxHealthAttempts(int maxHealthAttempts) { this.maxHealthAttempts = maxHealthAttempts; }
    public String getDockerExecutable() { return dockerExecutable; }
    public void setDockerExecutable(String dockerExecutable) { this.dockerExecutable = dockerExecutable; }
    public Ssh getSsh() { return ssh; }

    public static class Ssh {
        private String executable = "ssh";
        private String host = "";
        private int port = 22;
        private String user = "";
        private String keyPath = "";
        private String helperPath = "/usr/local/sbin/used-market-docker-runbook";

        public String getExecutable() { return executable; }
        public void setExecutable(String executable) { this.executable = executable; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getKeyPath() { return keyPath; }
        public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
        public String getHelperPath() { return helperPath; }
        public void setHelperPath(String helperPath) { this.helperPath = helperPath; }
    }
}
