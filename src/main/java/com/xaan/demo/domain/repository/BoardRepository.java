package com.xaan.demo.domain.repository;

import com.xaan.demo.domain.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.util.List;

public interface BoardRepository extends JpaRepository<Board,Long> {
    List<Board>  findAllByOrderByIdDesc();
    Page<Board>  findAllByOrderByIdDesc(Pageable pageable);
    Slice<Board> findByOrderByIdDesc(Pageable pageable);
    List<Board>  findTop100ByOrderByIdDesc();
    
    @org.springframework.data.jpa.repository.Query(value = "select created_date\\:\\:date as postDate, sum(length(content)) as contentSize, count(id) as articles, to_char(max(created_date),'yyyy-mm-dd hh24:mi:ss') as last_time " +
            "from ebiz.board " +
            "group by created_date\\:\\:date " +
            "order by 1 desc", nativeQuery = true)
    List<com.xaan.demo.dto.BoardSummaryDto> getBoardSummary();
}
