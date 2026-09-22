package com.zhiyou.opengpu.queue;

import com.zhiyou.opengpu.config.OpenGpuProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 待执行任务的低延迟唤醒通道（Redis Streams，可选）。
 *
 * <p><b>设计要点：</b> PostgreSQL 才是任务事实来源，原子领取靠
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 完成；Redis Stream 只用于把
 * 「有新任务」这一事件尽快推给正在长轮询的 Worker。
 *
 * <p>因此 Redis 是<strong>可选组件</strong>：
 * <ul>
 *   <li>{@code opengpu.queue.redis-enabled=true}（默认）：启用唤醒通道，
 *       任务入队后可被立即取走。Redis 运行中不可用也只是记日志并退化为轮询。</li>
 *   <li>{@code =false}：完全不访问 Redis，领取退化为约 1 秒一次的数据库轮询，
 *       功能完全一致。</li>
 * </ul>
 */
@Slf4j
@Component
public class TaskQueue {

    public static final String STREAM_KEY = "opengpu:tasks:queued";
    private static final long MAX_STREAM_LENGTH = 2000L;

    private final StringRedisTemplate redis;
    private final OpenGpuProperties properties;

    public TaskQueue(StringRedisTemplate redis, OpenGpuProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean isRedisEnabled() {
        return properties.getQueue().isRedisEnabled();
    }

    /** 新任务入队后广播唤醒信号。失败只记日志，不影响任务落库。 */
    public void publish(UUID taskId) {
        if (!isRedisEnabled()) {
            return;
        }
        try {
            redis.opsForStream().add(STREAM_KEY, Map.of("taskId", taskId.toString()));
            redis.opsForStream().trim(STREAM_KEY, MAX_STREAM_LENGTH, true);
        } catch (Exception e) {
            log.warn("任务唤醒信号发送失败（任务已落库，Worker 将通过轮询取到）: taskId={}, cause={}",
                    taskId, e.getMessage());
        }
    }

    /**
     * 阻塞等待唤醒信号，最多阻塞 {@code timeout}。
     * 未启用 Redis 或 Redis 不可用时静默退化为 sleep，调用方无需感知。
     */
    @SuppressWarnings("unchecked")
    public void awaitSignal(Duration timeout) {
        if (!isRedisEnabled()) {
            sleep(timeout.toMillis());
            return;
        }
        try {
            redis.opsForStream().read(
                    StreamReadOptions.empty().block(timeout),
                    StreamOffset.create(STREAM_KEY, ReadOffset.latest()));
        } catch (Exception e) {
            sleep(timeout.toMillis());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
