package com.xaan.demo.controller;

import com.xaan.demo.dto.BoardResponseDto;
import com.xaan.demo.service.BoardService;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class Top100IndexController {

    private final JdbcTemplate jdbcTemplate;
    private final BoardService boardService;

    public Top100IndexController(JdbcTemplate jdbcTemplate, BoardService boardService) {
        this.jdbcTemplate = jdbcTemplate;
        this.boardService = boardService;
    }

    @GetMapping("/last100")
    public String getLast100Page(Model model, HttpSession session) {
        List<BoardResponseDto> posts = boardService.findLast100();
        model.addAttribute("posts", posts);
        Set<Long> needsPopupIds = posts.stream()
                .filter(BoardResponseDto::isPasswordProtected)
                .filter(post -> !Boolean.TRUE.equals(session.getAttribute("verifiedPost:" + post.getId())))
                .map(BoardResponseDto::getId)
                .collect(Collectors.toSet());
        model.addAttribute("needsPopupIds", needsPopupIds);
        return "last100";
    }

    @GetMapping("/summary")
    @ResponseBody
    public List<Map<String, Object>> getSummaryData() {
        return jdbcTemplate.queryForList("SELECT count(1) nrows FROM board a full join board b on a.id = b.id");
    }
}