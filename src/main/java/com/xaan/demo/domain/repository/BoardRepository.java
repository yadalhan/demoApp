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
}
