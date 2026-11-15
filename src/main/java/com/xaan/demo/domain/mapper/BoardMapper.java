package com.xaan.demo.domain.mapper;

import com.xaan.demo.domain.entity.Board;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 게시글 매퍼. SQL은 {@code src/main/resources/mapper/BoardMapper.xml}에서 관리한다.
 *
 * <p>password는 쓰기 경로에서만 BoardPasswordTypeHandler를 거친다(암호화) - SELECT는 그대로 암호문을
 * 반환한다(복호화하지 않음). board 테이블에는 이 라이브러리 도입 이전의 레거시 포맷 데이터가 약 4.6만 건
 * 섞여 있어, 읽기 경로에도 걸면 목록/상세 조회 같은 일반적인 SELECT가 CryptoException으로 깨진다.
 * 비밀번호 확인은 PasswordService.validateBoardPassword()가 EnvelopeCryptoService.validate()로 처리한다.
 *
 * <p>제목/내용 수정(updateTitleContent)과 비밀번호 교체(updatePassword)는 분리되어 있다. 이미 암호화된
 * 값을 TypeHandler가 있는 파라미터로 다시 흘려보내면 이중 암호화되기 때문이다.
 */
@Mapper
public interface BoardMapper {

    int insert(Board board);

    Board findById(@Param("id") Long id);

    List<Board> findAllByOrderByIdDesc();

    List<Board> findTop100ByOrderByIdDesc();

    List<Board> findRecent(@Param("limit") int limit);

    /** 비밀번호를 건드리지 않는 수정(title/content만). */
    int updateTitleContent(@Param("id") Long id, @Param("title") String title, @Param("content") String content);

    /** 비밀번호 교체(평문 입력, TypeHandler가 암호화). */
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 재암호화 배치 전용 - 이미 암호화된 ciphertext를 그대로 저장한다. */
    int updatePasswordRaw(@Param("id") Long id, @Param("password") String password);

    List<com.xaan.demo.dto.BoardSummaryDto> getBoardSummary();
}
