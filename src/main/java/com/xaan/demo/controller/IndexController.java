package com.xaan.demo.controller;

import com.xaan.demo.service.BoardService;
import com.xaan.demo.dto.BoardResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;

@RequiredArgsConstructor
@Controller
public class IndexController {
    private final BoardService boardService;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("posts", boardService.findLast100());
        return "last100";
    }

    @GetMapping("/posts/save")
    public String saveForm() {
        return "posts/save";
    }

    @GetMapping("/posts/update/{id}")
    public String updateForm(@PathVariable Long id, Model model) {
        model.addAttribute("post", boardService.findById(id));
        return "posts/update";
    }

    @GetMapping("/list1st")
    public String getBoardList(@PageableDefault(size = 10) Pageable pageable, Model model) {
        Page<BoardResponseDto> posts = boardService.getBoardList(pageable);
        model.addAttribute("posts", posts.getContent());
        return "list1st";
    }

    @GetMapping("/list1stonly")
    public String getBoardList1stOnly(@PageableDefault(size = 10) Pageable pageable, Model model) {
        Slice<BoardResponseDto> posts = boardService.getBoardList1stOnly(pageable);
        model.addAttribute("posts", posts.getContent());
        return "list1stonly";
    }
}
