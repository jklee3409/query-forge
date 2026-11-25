package io.queryforge.backend.admin.console.runtime.controller;

import io.queryforge.backend.admin.console.runtime.dto.AdminRuntimeOptionsDtos;
import io.queryforge.backend.admin.console.runtime.service.AdminRuntimeOptionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class AdminRuntimeOptionsController {

    private final AdminRuntimeOptionsService runtimeOptionsService;

    @GetMapping("/runtime/options")
    public AdminRuntimeOptionsDtos.RuntimeOptionsResponse runtimeOptions() {
        return runtimeOptionsService.getRuntimeOptions();
    }
}
