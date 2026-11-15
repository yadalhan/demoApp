# 성능 테스트 페이지(/test/slowpage)에서 반복 실행하는 일반 쿼리 목록.
# 한 줄에 하나씩, # 으로 시작하는 줄은 주석이다.
select * from board order by id desc fetch first 10 rows only
select * from board order by password desc fetch first 10 rows only
select * from board order by author desc fetch first 10 rows only
select * from board order by modified_date desc fetch first 10 rows only
select * from board order by created_date desc fetch next 10 rows only
select id, count(*) as cnt from board group by id order by id desc fetch first 10 rows only
select password, count(*) as cnt from board group by password order by password desc fetch first 10 rows only
select author, count(*) as cnt from board group by author order by author desc fetch first 10 rows only
select modified_date, count(*) as cnt from board group by modified_date order by modified_date desc fetch first 10 rows only
select created_date, count(*) as cnt from board group by created_date order by created_date desc fetch next 10 rows only
select id, count(1) as cnt from board group by id order by id desc fetch first 10 rows only
select password, count(1) as cnt from board group by password order by password desc fetch first 10 rows only
select author, count(1) as cnt from board group by author order by author desc fetch first 10 rows only
select modified_date, count(1) as cnt from board group by modified_date order by modified_date desc fetch first 10 rows only
select created_date, count(1) as cnt from board group by created_date order by created_date desc fetch next 10 rows only
select id, count(id) as cnt from board group by id order by id desc fetch first 10 rows only
select password, count(password) as cnt from board group by password order by password desc fetch first 10 rows only
select author, count(author) as cnt from board group by author order by author desc fetch first 10 rows only
select modified_date, count(modified_date) as cnt from board group by modified_date order by modified_date desc fetch first 10 rows only
select created_date, count(created_date) as cnt from board group by created_date order by created_date desc fetch next 10 rows only
select count(id) from board
SELECT count(1) nrows FROM board a full join board b on a.id = b.id
