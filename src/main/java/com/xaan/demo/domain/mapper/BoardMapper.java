package com.xaan.demo.domain.mapper;

import com.xaan.demo.domain.entity.Board;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BoardMapper {

    String COLUMNS = "id, title, content, author, password, created_date, modified_date";

    @Insert("insert into board (title, content, author, password, created_date, modified_date) " +
            "values (#{title}, #{content}, #{author}, #{password}, now(), now())")
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

    @Update("update board set title = #{title}, content = #{content}, password = #{password}, " +
            "modified_date = now() where id = #{id}")
    int update(Board board);

    @Update("update board set password = #{password} where id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

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
