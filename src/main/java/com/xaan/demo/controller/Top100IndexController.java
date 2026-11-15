package com.xaan.demo.controller;

import com.xaan.demo.domain.mapper.StatsMapper;
import com.xaan.demo.dto.BoardResponseDto;
import com.xaan.demo.service.BoardService;
import jakarta.servlet.http.HttpSession;
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

    private final StatsMapper statsMapper;
    private final BoardService boardService;

    public Top100IndexController(StatsMapper statsMapper, BoardService boardService) {
        this.statsMapper = statsMapper;
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
        // SQL은 StatsMapper.xml
        return List.of(Map.of("nrows", statsMapper.countBoards()));
    }
}