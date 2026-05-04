package com.xaan.demo.controller;

import com.xaan.demo.service.BoardService;
import com.xaan.demo.dto.BoardResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
    model.addAttribute("posts", boardService.findAllDesc());
        return "index";
    }

    @GetMapping("/list1st")
    public Page<BoardResponseDto> getBoardList(@PageableDefault(size = 10) Pageable pageable) {
        return boardService.getBoardList(pageable);
    }

    @GetMapping("/list1stonly")
    public Slice<BoardResponseDto> getBoardList1stOnly(@PageableDefault(size = 10) Pageable pageable) {
        return boardService.getBoardList1stOnly(pageable);
    }
}
