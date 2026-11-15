package com.xaan.demo.domain.mapper;

import org.apache.ibatis.annotations.Mapper;

/**
 * 성능 테스트 페이지용 쿼리. SQL은 {@code src/main/resources/mapper/TestMapper.xml}.
 */
@Mapper
public interface TestMapper {

    long slowQuery();
}
