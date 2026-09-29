package com.ticketflow.dashboard;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** from/to are ISO dates (2026-09-28), both inclusive. Default: the last 30 days. */
    @GetMapping("/api/dashboard")
    @PreAuthorize("hasRole('MANAGER')")
    public DashboardResponse get(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return dashboard.build(from, to);
    }
}
