package com.xaan.demo.domain.mapper;

import com.xaan.demo.domain.entity.Board;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BoardMapper {

    String COLUMNS = "id, title, content, author, password, created_date, modified_date";

    // password는 쓰기 경로에서만 BoardPasswordTypeHandler를 거친다(암호화) - SELECT는 그대로 암호문을
    // 반환한다(복호화하지 않음). board 테이블에는 이 라이브러리 도입 이전의 레거시 포맷 데이터가 약 4.6만 건
    // 섞여 있어, 읽기 경로에도 걸면 목록/상세 조회 같은 일반적인 SELECT가 CryptoException으로 깨진다 -
    // vault-crypto README "3-3. 주의" 참고. 비밀번호 확인은 지금처럼 PasswordService.validateBoardPassword()가
    // EnvelopeCryptoService.validate() 안에서 명시적으로 처리한다.
    @Insert("insert into board (title, content, author, password, created_date, modified_date) " +
            "values (#{title}, #{content}, #{author}, " +
            "#{password,typeHandler=com.xaan.demo.config.mybatis.BoardPasswordTypeHandler}, now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Board board);

    @Select("select " + COLUMNS + " from board where id = #{id}")
    Board findById(Long id);

    @Select("select " + COLUMNS + " from board order by id desc")
    List<Board> findAllByOrderByIdDesc();

    @Select("select " + COLUMNS + " from board order by id desc limit 100")
    List<Board> findTop100ByOrderByIdDesc();

    @Select("select " + COLUMNS + " from board order by id desc limit #{limit}")
    List<Board> findRecent(@Param("limit") int limit);

    // 비밀번호를 바꾸지 않는 수정은 이 메서드만 쓴다 - password 컬럼을 아예 건드리지 않는다. board.password는
    // 항상 암호문 상태로만 다뤄지고(위 주석 참고) 평문으로 복호화해 들고 있지 않으므로, "바뀌지 않은 기존
    // 비밀번호"를 TypeHandler가 있는 파라미터로 다시 흘려보내면 이미 암호문인 값을 또 암호화(이중 암호화)하게
    // 되어 버린다 - 그래서 title/content 수정과 password 교체(updatePassword)를 완전히 분리했다.
    @Update("update board set title = #{title}, content = #{content}, modified_date = now() where id = #{id}")
    int updateTitleContent(@Param("id") Long id, @Param("title") String title, @Param("content") String content);

    @Update("update board set password = #{password,typeHandler=com.xaan.demo.config.mybatis.BoardPasswordTypeHandler} " +
            "where id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    // 재암호화 배치(DekReencryptionService) 전용 - 이미 암호화가 끝난 ciphertext를 그대로 저장해야 하므로
    // BoardPasswordTypeHandler를 걸지 않는다(걸면 이미 암호문인 값을 또 암호화하게 된다).
    @Update("update board set password = #{password} where id = #{id}")
    int updatePasswordRaw(@Param("id") Long id, @Param("password") String password);

    @Select("""
            select created_date::date as "postDate",
                   sum(length(content)) as "contentSize",
                   count(id) as "articles",
                   to_char(max(created_date), 'yyyy-mm-dd hh24:mi:ss') as "lastTime"
            from ebiz.board
            group by created_date::date
            order by 1 desc
            """)
    List<com.xaan.demo.dto.BoardSummaryDto> getBoardSummary();
}
