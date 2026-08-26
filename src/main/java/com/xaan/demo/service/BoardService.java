package com.xaan.demo.service;

import com.xaan.demo.domain.entity.Board;
import com.xaan.demo.domain.mapper.BoardMapper;
import com.xaan.demo.dto.BoardResponseDto;
import com.xaan.demo.dto.BoardSaveRequestDto;
import com.xaan.demo.dto.BoardUpdateRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class BoardService {
    private static final int PAGE_SIZE = 10;

    private final BoardMapper boardMapper;
    private final PasswordService passwordService;

    // 1. save - 게시글 비밀번호는 AES-GCM으로 암호화
    @Transactional
    public Long save(BoardSaveRequestDto requestDto) {
        Board board = requestDto.toEntity();
        if (board.getPassword() != null && !board.getPassword().isEmpty()) {
            board.updatePassword(passwordService.encryptBoardPassword(board.getPassword()));
        }
        boardMapper.insert(board);
        return board.getId();
    }

    // 2. update - 게시글 비밀번호는 AES-GCM으로 암호화
    @Transactional
    public Long update(Long id, BoardUpdateRequestDto requestDto) {
        Board board = findBoard(id);
        board.update(requestDto.getTitle(), requestDto.getContent());
        if (requestDto.getPassword() != null && !requestDto.getPassword().isEmpty()) {
            board.updatePassword(passwordService.encryptBoardPassword(requestDto.getPassword()));
        }
        boardMapper.update(board);
        return id;
    }

    // 3. query
    public BoardResponseDto findById(Long id) {
        return new BoardResponseDto(findBoard(id));
    }

    private Board findBoard(Long id) {
        return Optional.ofNullable(boardMapper.findById(id))
                .orElseThrow(() -> new IllegalArgumentException("no ariticle for id=" + id));
    }

    @Transactional(readOnly = true)
    public List<BoardResponseDto> findAllDesc(){
        return boardMapper.findAllByOrderByIdDesc().stream()
                .map(BoardResponseDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BoardResponseDto> getBoardList() {
            return boardMapper.findRecent(PAGE_SIZE).stream()
                    .map(BoardResponseDto::new)
                    .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BoardResponseDto> getBoardList1stOnly() {
            return boardMapper.findRecent(PAGE_SIZE).stream()
                    .map(BoardResponseDto::new)
                    .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BoardResponseDto> findLast100() {
        return boardMapper.findTop100ByOrderByIdDesc().stream()
                .map(BoardResponseDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<com.xaan.demo.dto.BoardSummaryDto> getBoardSummary() {
        return boardMapper.getBoardSummary();
    }

    @org.springframework.cache.annotation.Cacheable(value = "boardSummary")
    @Transactional(readOnly = true)
    public List<com.xaan.demo.dto.BoardSummaryResponseDto> getBoardSummaryCached() {
        return boardMapper.getBoardSummary().stream()
                .map(summary -> new com.xaan.demo.dto.BoardSummaryResponseDto(
                        summary.getPostDate(),
                        summary.getContentSize(),
                        summary.getArticles(),
                        summary.getLastTime()))
                .collect(Collectors.toList());
    }

    // 게시글 비밀번호 검증
    public boolean verifyPassword(Long id, String password) {
        Board board = findBoard(id);
        return passwordService.validateBoardPassword(password, board.getPassword());
    }
}
