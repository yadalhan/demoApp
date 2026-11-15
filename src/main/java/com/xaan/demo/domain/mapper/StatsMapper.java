package com.xaan.demo.domain.mapper;

import org.apache.ibatis.annotations.Mapper;

/**
 * 통계/요약 조회. SQL은 {@code src/main/resources/mapper/StatsMapper.xml}.
 */
@Mapper
public interface StatsMapper {

    long countBoards();
}
