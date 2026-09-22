package com.zhiyou.opengpu.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档，方便联调时直接试接口。
 * 访问：http://localhost:8080/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI openGpuOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("智由 AI 视频任务平台 — 控制面 API")
                        .version("v1")
                        .description("""
                                任务提交、任务队列、GPU Worker 领取与结果回传。

                                认证方式：
                                - 用户/管理员：POST /api/auth/login 获取 JWT
                                - GPU Worker：管理端创建节点时下发的 wkr_<workerId>_<secret>
                                """)
                        .summary("Fixed MiniMax H3 · Remote GPU Worker · Task Queue"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT / Worker Token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
