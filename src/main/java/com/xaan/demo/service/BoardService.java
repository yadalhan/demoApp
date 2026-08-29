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

    // 1. save - 비밀번호는 평문 그대로 넘긴다. BoardMapper.insert()의 BoardPasswordTypeHandler가
    // AES-GCM으로 암호화해 저장하므로 여기서 vault-crypto를 직접 호출할 필요가 없다.
    @Transactional
    public Long save(BoardSaveRequestDto requestDto) {
        Board board = requestDto.toEntity();
        boardMapper.insert(board);
        return board.getId();
    }

    // 2. update - 제목/내용 수정과 비밀번호 교체를 별도 쿼리로 분리한다(BoardMapper.updateTitleContent 주석 참고):
    // board.password는 항상 암호문으로만 다뤄지므로, 바뀌지 않은 기존 비밀번호를 TypeHandler가 있는 파라미터로
    // 다시 흘려보내면 이미 암호문인 값을 이중 암호화하게 된다.
    @Transactional
    public Long update(Long id, BoardUpdateRequestDto requestDto) {
        int rows = boardMapper.updateTitleContent(id, requestDto.getTitle(), requestDto.getContent());
        if (rows == 0) {
            throw new IllegalArgumentException("no ariticle for id=" + id);
        }
        if (requestDto.getPassword() != null && !requestDto.getPassword().isEmpty()) {
            boardMapper.updatePassword(id, requestDto.getPassword()); // 평문 - TypeHandler가 암호화
        }
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
