package com.zhiyou.opengpu.bootstrap;

import com.zhiyou.opengpu.common.Hashing;
import com.zhiyou.opengpu.config.OpenGpuProperties;
import com.zhiyou.opengpu.domain.AppUser;
import com.zhiyou.opengpu.domain.GpuTier;
import com.zhiyou.opengpu.domain.UserRole;
import com.zhiyou.opengpu.domain.Worker;
import com.zhiyou.opengpu.domain.WorkerRuntimeStatus;
import com.zhiyou.opengpu.domain.WorkerStatus;
import com.zhiyou.opengpu.repository.AppUserRepository;
import com.zhiyou.opengpu.repository.WorkerRepository;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开发种子数据：内置账号 + 一个固定的开发用 Worker 凭证，
 * 让 M1 闭环可以在不开管理页面的情况下先跑通。
 *
 * <p>生产环境应通过环境变量覆盖 {@code opengpu.security.dev-worker-token}，
 * 或直接删除该节点并在管理端重新创建。
 */
@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    private final AppUserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final PasswordEncoder passwordEncoder;
    private final OpenGpuProperties properties;

    public DataInitializer(AppUserRepository userRepository,
                           WorkerRepository workerRepository,
                           PasswordEncoder passwordEncoder,
                           OpenGpuProperties properties) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser("admin", "admin123", UserRole.ADMIN);
        seedUser("user", "user123", UserRole.USER);
        seedDevWorker();
    }

    private void seedUser(String username, String rawPassword, UserRole role) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return;
        }
        userRepository.save(AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build());
        log.info("已创建内置账号: {} / {}  ({})", username, rawPassword, role);
    }

    private void seedDevWorker() {
        String token = properties.getSecurity().getDevWorkerToken();
        if (token == null || !token.startsWith("wkr_")) {
            log.warn("未配置合法的 opengpu.security.dev-worker-token，跳过开发节点种子数据");
            return;
        }
        String[] parts = token.split("_", 3);
        if (parts.length != 3) {
            log.warn("开发 Worker token 格式非法（应为 wkr_<uuid>_<secret>），跳过种子数据");
            return;
        }
        UUID workerId;
        try {
            workerId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            log.warn("开发 Worker token 中的节点 ID 非法，跳过种子数据");
            return;
        }
        if (workerRepository.existsById(workerId)) {
            return;
        }
        Worker worker = Worker.builder()
                .name("dev-worker-01")
                .tokenHash(Hashing.sha256Hex(token))
                .status(WorkerStatus.OFFLINE)
                .runtimeStatus(WorkerRuntimeStatus.IDLE)
                .gpuModel("开发节点（Mock 引擎）")
                // 需与 worker/.env.example 的默认能力保持一致，否则带视频要求的任务无法被领取
                .vramMb(24576)
                .gpuTier(GpuTier.PRO.getRank())
                .maxDurationSeconds(15)
                .supportedResolutions("480P,720P,1080P,4K")
                .workerVersion("0.1.0")
                .modelVersion("MiniMax-H3")
                .enabled(true)
                .build();
        worker.setId(workerId);
        workerRepository.saveAndFlush(worker);

        log.warn("""

                ============================================================
                 已创建开发用 GPU 节点（仅用于本地联调）
                 节点名称 : dev-worker-01
                 节点 ID  : {}
                 Token    : {}
                 声明能力 : 24576MB 显存 / PRO 档 / 最长 15 秒
                 请勿在生产环境使用该固定凭证。
                ============================================================
                """, workerId, token);
    }
}
