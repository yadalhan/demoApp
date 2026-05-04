package com.xaan.demo.service;

import com.xaan.demo.domain.entity.Board;
import com.xaan.demo.domain.repository.BoardRepository;
import com.xaan.demo.dto.BoardResponseDto;
import com.xaan.demo.dto.BoardSaveRequestDto;
import com.xaan.demo.dto.BoardUpdateRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class BoardService {
    private final BoardRepository boardRepository;

    // 1. save
    @Transactional
    public Long save(BoardSaveRequestDto requestDto) {
        return boardRepository.save(requestDto.toEntity()).getId();
    }

    // 2. update
    @Transactional
    public Long update(Long id, BoardUpdateRequestDto requestDto) {
        Board board = boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no ariticle for id=" + id));
        // Dirty Checking: if colum is updated in this transaction, execute update query automatically
        board.update(requestDto.getTitle(), requestDto.getContent());
        return id;
    }

    // 3. query
    public BoardResponseDto findById(Long id) {
        Board entity = boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no ariticle for id=" + id));
        return new BoardResponseDto(entity);
    }

    @Transactional(readOnly = true)
    public List<BoardResponseDto> findAllDesc(){
        return boardRepository.findAllByOrderByIdDesc().stream()
                .map(BoardResponseDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<BoardResponseDto> getBoardList(Pageable pageable) {
            Pageable pageRequest = PageRequest.of(0, 10, Sort.by("id").descending());
            Page<Board> boardPage = boardRepository.findFirstPageByOrderByIdDesc(pageRequest);
            return boardPage.map(BoardResponseDto::new);
    }

    @Transactional(readOnly = true)
    public Slice<BoardResponseDto> getBoardList1stOnly(Pageable pageable) {
            // 0페이지, 10개씩, ID 내림차순
            Pageable pageRequest = PageRequest.of(0, 10, Sort.by("id").descending());
            Slice<Board> boardPage = boardRepository.findFirstPageOnlyByOrderByIdDesc(pageRequest);
            return boardPage.map(BoardResponseDto::new);
    }
}
