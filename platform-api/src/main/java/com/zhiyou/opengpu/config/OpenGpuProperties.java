package com.zhiyou.opengpu.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opengpu.*} 配置绑定，见 application.yml。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "opengpu")
public class OpenGpuProperties {

    private Security security = new Security();
    private Queue queue = new Queue();
    private Task task = new Task();
    private Storage storage = new Storage();
    private Cors cors = new Cors();

    @Getter
    @Setter
    public static class Queue {
        /**
         * 是否启用 Redis Streams 唤醒通道。
         * 关闭时任务领取退化为数据库轮询，功能一致。
         */
        private boolean redisEnabled = false;
    }

    @Getter
    @Setter
    public static class Security {
        private String jwtSecret;
        private long jwtExpireMinutes = 720;
        /** 开发用固定 Worker token，格式 wkr_&lt;workerId&gt;_&lt;secret&gt; */
        private String devWorkerToken;

        /** 是否开放自助注册。关闭后 POST /api/auth/register 返回 40301。 */
        private boolean registrationEnabled = true;

        // 账号格式策略：通过 GET /api/auth/config 下发给前端，避免前端硬编码
        private int usernameMinLength = 3;
        private int usernameMaxLength = 32;
        private int passwordMinLength = 6;
        private int passwordMaxLength = 64;
    }

    @Getter
    @Setter
    public static class Task {
        private long leaseSeconds = 300;
        private int maxRetry = 2;
        private int maxPromptLength = 2000;
        private int maxClaimWaitSeconds = 30;
        private long heartbeatTimeoutSeconds = 90;
        private long heartbeatIntervalSeconds = 20;
        private long leaseReaperIntervalSeconds = 15;
        private long workerMonitorIntervalSeconds = 30;
        private long maxFileSizeBytes = 2147483648L;
        private List<String> allowedVideoExtensions = List.of("mp4", "webm", "mov");
    }

    @Getter
    @Setter
    public static class Storage {
        /** local | minio */
        private String type = "local";
        private String localRoot = "./data/videos";
        /** 对外可访问的基地址，为空则返回相对路径 /api/files/... */
        private String publicBaseUrl = "";
        private Minio minio = new Minio();

        @Getter
        @Setter
        public static class Minio {
            private String endpoint;
            private String accessKey;
            private String secretKey;
            private String bucket;
            private String publicEndpoint;
        }
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }
}
