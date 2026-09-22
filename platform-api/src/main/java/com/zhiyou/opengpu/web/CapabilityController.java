package com.zhiyou.opengpu.web;

import com.zhiyou.opengpu.common.ApiResponse;
import com.zhiyou.opengpu.service.CapabilityService;
import com.zhiyou.opengpu.web.dto.CapabilityDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频要求能力矩阵。需要登录（USER 或 ADMIN 均可）。
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityService capabilityService;

    public CapabilityController(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping
    public ApiResponse<CapabilityDtos.CapabilityView> capabilities() {
        return ApiResponse.ok(capabilityService.describe());
    }
}
