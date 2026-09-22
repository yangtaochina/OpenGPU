package com.zhiyou.opengpu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智由 AI 视频任务平台 — 控制面 API 入口。
 *
 * <p>控制面负责：用户、任务、节点状态与文件元数据。
 * 模型执行面位于远程 GPU 电脑（Python Worker），不在本进程内。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@ConfigurationPropertiesScan
public class OpenGpuApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenGpuApplication.class, args);
    }
}
