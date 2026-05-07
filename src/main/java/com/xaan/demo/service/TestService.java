package com.xaan.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TestService {

    private final JdbcTemplate jdbcTemplate;

    public TestResult runQueries() {
        List<Long> normalQueryTimes = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        long slowStart = System.currentTimeMillis();
        runSlowQuery();
        long slowQueryTime = System.currentTimeMillis() - slowStart;

        List<String> normalQueries = getNormalQueries();
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
        String slowQuery = """
            select count(1)
            from (
                select * from board order by created_date desc fetch next 5000 rows only
            ) a
            full join
            (
                select * from board order by title desc fetch next 5000 rows only
            ) b
            on a.title = b.title
            """;
        jdbcTemplate.queryForList(slowQuery);
    }

    private List<String> getNormalQueries() {
        List<String> queries = new ArrayList<>();

        queries.add("select * from board order by id desc fetch first 10 rows only");
        queries.add("select * from board order by password desc fetch first 10 rows only");
        queries.add("select * from board order by author desc fetch first 10 rows only");
        queries.add("select * from board order by modified_date desc fetch first 10 rows only");
        queries.add("select * from board order by created_date desc fetch next 10 rows only");

        queries.add("select id, count(*) as cnt from board group by id order by id desc fetch first 10 rows only");
        queries.add("select password, count(*) as cnt from board group by password order by password desc fetch first 10 rows only");
        queries.add("select author, count(*) as cnt from board group by author order by author desc fetch first 10 rows only");
        queries.add("select modified_date, count(*) as cnt from board group by modified_date order by modified_date desc fetch first 10 rows only");
        queries.add("select created_date, count(*) as cnt from board group by created_date order by created_date desc fetch next 10 rows only");

        queries.add("select id, count(1) as cnt from board group by id order by id desc fetch first 10 rows only");
        queries.add("select password, count(1) as cnt from board group by password order by password desc fetch first 10 rows only");
        queries.add("select author, count(1) as cnt from board group by author order by author desc fetch first 10 rows only");
        queries.add("select modified_date, count(1) as cnt from board group by modified_date order by modified_date desc fetch first 10 rows only");
        queries.add("select created_date, count(1) as cnt from board group by created_date order by created_date desc fetch next 10 rows only");

        queries.add("select id, count(id) as cnt from board group by id order by id desc fetch first 10 rows only");
        queries.add("select password, count(password) as cnt from board group by password order by password desc fetch first 10 rows only");
        queries.add("select author, count(author) as cnt from board group by author order by author desc fetch first 10 rows only");
        queries.add("select modified_date, count(modified_date) as cnt from board group by modified_date order by modified_date desc fetch first 10 rows only");
        queries.add("select created_date, count(created_date) as cnt from board group by created_date order by created_date desc fetch next 10 rows only");

        queries.add("select count(id) from board");
        queries.add("SELECT count(1) nrows FROM board a full join board b on a.id = b.id");

        return queries;
    }

    public record TestResult(
        long slowQueryTime,
        List<Long> normalQueryTimes,
        long totalTime
    ) {}
}
