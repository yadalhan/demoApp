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
    private final PasswordService passwordService;

    // 1. save - 게시글 비밀번호는 AES-GCM으로 암호화
    @Transactional
    public Long save(BoardSaveRequestDto requestDto) {
        Board board = requestDto.toEntity();
        if (board.getPassword() != null && !board.getPassword().isEmpty()) {
            board.updatePassword(passwordService.encryptBoardPassword(board.getPassword()));
        }
        return boardRepository.save(board).getId();
    }

    // 2. update - 게시글 비밀번호는 AES-GCM으로 암호화
    @Transactional
    public Long update(Long id, BoardUpdateRequestDto requestDto) {
        Board board = boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no ariticle for id=" + id));
        board.update(requestDto.getTitle(), requestDto.getContent());
        if (requestDto.getPassword() != null && !requestDto.getPassword().isEmpty()) {
            board.updatePassword(passwordService.encryptBoardPassword(requestDto.getPassword()));
        }
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
            Page<Board> boardPage = boardRepository.findAll(pageRequest);
            return boardPage.map(BoardResponseDto::new);
    }

    @Transactional(readOnly = true)
    public Slice<BoardResponseDto> getBoardList1stOnly(Pageable pageable) {
            Pageable pageRequest = PageRequest.of(0, 10, Sort.by("id").descending());
            Slice<Board> boardSlice = boardRepository.findByOrderByIdDesc(pageRequest);
            return boardSlice.map(BoardResponseDto::new);
    }

    @Transactional(readOnly = true)
    public List<BoardResponseDto> findLast100() {
        return boardRepository.findTop100ByOrderByIdDesc().stream()
                .map(BoardResponseDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<com.xaan.demo.dto.BoardSummaryDto> getBoardSummary() {
        return boardRepository.getBoardSummary();
    }

    @org.springframework.cache.annotation.Cacheable(value = "boardSummary")
    @Transactional(readOnly = true)
    public List<com.xaan.demo.dto.BoardSummaryResponseDto> getBoardSummaryCached() {
        return boardRepository.getBoardSummary().stream()
                .map(summary -> new com.xaan.demo.dto.BoardSummaryResponseDto(
                        summary.getPostDate(),
                        summary.getContentSize(),
                        summary.getArticles(),
                        summary.getLastTime()))
                .collect(Collectors.toList());
    }

    // 게시글 비밀번호 검증
    public boolean verifyPassword(Long id, String password) {
        Board board = boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no ariticle for id=" + id));
        return passwordService.validateBoardPassword(password, board.getPassword());
    }
}
