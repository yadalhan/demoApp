package com.xaan.demo.controller;

import com.xaan.demo.dto.BoardResponseDto;
import com.xaan.demo.dto.BoardSaveRequestDto;
import com.xaan.demo.dto.BoardUpdateRequestDto;
import com.xaan.demo.service.BoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
public class BoardApiController {
    private final BoardService boardService;

    // save API
    @PostMapping("/api/v1/posts")
    public Long save(@RequestBody BoardSaveRequestDto requestDto) {
        return boardService.save(requestDto);
    }

    // update API
    @PutMapping("/api/v1/posts/{id}")
    public Long update(@PathVariable Long id, @RequestBody BoardUpdateRequestDto requestDto) {
        return boardService.update(id, requestDto);
    }

    // qluery API
    @GetMapping("/api/v1/posts/{id}")
    public BoardResponseDto findById(@PathVariable Long id) {
        return boardService.findById(id);
    }
}
