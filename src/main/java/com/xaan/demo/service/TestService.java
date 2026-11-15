package com.xaan.demo.service;

import com.xaan.demo.domain.mapper.TestMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class TestService {

    private final JdbcTemplate jdbcTemplate;
    private final TestMapper testMapper;
    private final List<String> normalQueries;

    public TestService(JdbcTemplate jdbcTemplate,
                       TestMapper testMapper,
                       @Value("classpath:sql/test-queries.sql") Resource queriesResource) {
        this.jdbcTemplate = jdbcTemplate;
        this.testMapper = testMapper;
        this.normalQueries = loadQueries(queriesResource);
    }

    /** 쿼리 목록은 src/main/resources/sql/test-queries.sql 에서 읽는다(Java에 SQL 문자열 없음). */
    private static List<String> loadQueries(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("테스트 쿼리 파일을 읽을 수 없습니다: " + resource, e);
        }
    }

    public TestResult runQueries() {
        List<Long> normalQueryTimes = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        long slowStart = System.currentTimeMillis();
        runSlowQuery();
        long slowQueryTime = System.currentTimeMillis() - slowStart;

        int totalNormalCalls = 250;
        for (int i = 0; i < totalNormalCalls; i++) {
            String query = normalQueries.get(i % normalQueries.size());
            long start = System.currentTimeMillis();
            jdbcTemplate.queryForList(query);
            long elapsed = System.currentTimeMillis() - start;
            normalQueryTimes.add(elapsed);
        }

        long totalTime = System.currentTimeMillis() - totalStart;

        return new TestResult(slowQueryTime, normalQueryTimes, totalTime);
    }

    private void runSlowQuery() {
        // SQL은 TestMapper.xml
        testMapper.slowQuery();
    }

    public record TestResult(
        long slowQueryTime,
        List<Long> normalQueryTimes,
        long totalTime
    ) {}
}
