package com.xaan.demo.controller;

import com.xaan.demo.service.BoardService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
public class Top100IndexController {

    private final JdbcTemplate jdbcTemplate;

    public Top100IndexController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/last100")
    public List<Map<String, Object>> getLast100Data() {
        return jdbcTemplate.queryForList("SELECT * FROM board order by id desc fetch next 100 rows only");
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> getSummaryData() {
        return jdbcTemplate.queryForList("SELECT count(1) nrows FROM board a full join board b on a.id = b.id");
    }
}