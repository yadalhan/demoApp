package com.xaan.demo.controller;

import com.xaan.demo.service.TestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {
    private final TestService testService;

    @GetMapping("/slowpage")
    public String slowPage(Model model) {
        TestService.TestResult result = testService.runQueries();
        model.addAttribute("slowQueryTime", result.slowQueryTime());
        model.addAttribute("normalQueryTimes", result.normalQueryTimes());
        model.addAttribute("normalCount", result.normalQueryTimes().size());
        model.addAttribute("avgNormalTime", result.normalQueryTimes().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0));
        model.addAttribute("maxNormalTime", result.normalQueryTimes().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0));
        model.addAttribute("minNormalTime", result.normalQueryTimes().stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0));
        model.addAttribute("totalTime", result.totalTime());
        return "test/slowpage";
    }
}
