package io.queryforge.backend.admin.console.runtime;

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
