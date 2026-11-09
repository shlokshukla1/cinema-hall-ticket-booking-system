package com.cinemahall.report;

import com.cinemahall.common.PageResponse;
import com.cinemahall.report.ReportDtos.OccupancyRow;
import com.cinemahall.report.ReportDtos.SalesSummary;
import com.cinemahall.show.ShowService;
import com.cinemahall.show.ShowStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/reports")
@Tag(name = "Admin: reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/occupancy")
    public PageResponse<OccupancyRow> occupancy(@RequestParam(required = false) Long movieId,
                                                @RequestParam(required = false) Long theatreId,
                                                @RequestParam(required = false) ShowStatus status,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return reports.occupancy(new ShowService.ShowFilter(movieId, theatreId, null, null, null, from, to, status),
                page, size);
    }

    @GetMapping("/sales")
    public SalesSummary sales(@RequestParam(required = false) LocalDate fromDate,
                              @RequestParam(required = false) LocalDate toDate) {
        return reports.sales(fromDate, toDate);
    }
}
