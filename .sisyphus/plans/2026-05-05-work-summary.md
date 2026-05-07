# 2026-05-05 Work Summary

## Project: demoApp (Spring Boot BBS)

### 1. Fixed /list1st and /list1stonly Pages (No Data Issue)

**Problem**: Pages rendered but showed no data ("조회목록이 없습니다").

**Root Causes**:
1. **IndexController** methods returned `Page`/`Slice` objects directly instead of populating `Model` and returning view name
2. **Repository method names** used "First" keyword which Spring Data JPA interpreted as limiting results to 1

**Fixes Applied**:

#### IndexController.java
- Changed return type from `Page<BoardResponseDto>` / `Slice<BoardResponseDto>` to `String`
- Added `Model model` parameter
- Added `model.addAttribute("posts", ...)` to populate template
- Return view name strings `"list1st"` / `"list1stonly"`
- Extract content from Page/Slice via `.getContent()`

#### BoardRepository.java
| Before (Broken) | After (Fixed) |
|---|---|
| `findFirstPageByOrderByIdDesc(Pageable)` | `findAllByOrderByIdDesc(Pageable)` |
| `findFirstPageOnlyByOrderByIdDesc(Pageable)` | `findByOrderByIdDesc(Pageable)` |

#### deploy.sh
- Changed `PROD_SERVER="hactus57"` to `PROD_SERVER="192.168.2.57"` (hostname unresolved)

**Verification**:
- `/list1st` → 200 OK, 10 rows ✓
- `/list1stonly` → 200 OK, 10 rows ✓

---

### 2. Created /test/slowpage Test Page

**Requirements**:
- URL mapping: `/test/slowpage`
- 1 slow query execution
- 250 normal query executions (cycling through 22 unique queries)

**Files Created/Modified**:

#### New: TestService.java
- `runQueries()`: Executes slow query once, then 250 normal queries
- `runSlowQuery()`: Full join of 5000-row subqueries (expensive)
- `getNormalQueries()`: Returns 22 unique queries including:
  - `select * from board order by [column] desc fetch first/next 10 rows only` (5 queries)
  - `select count(*) from board group by [column] order by [column] desc fetch first/next 10 rows only` (5 queries)
  - `select count(1) from board group by [column] order by [column] desc fetch first/next 10 rows only` (5 queries)
  - `select count([column]) from board group by [column] order by [column] desc fetch first/next 10 rows only` (5 queries)
  - `select count(id) from board` (1 query)
  - `SELECT count(1) nrows FROM board a full join board b on a.id = b.id` (1 query)
- Returns `TestResult` record with slowQueryTime, normalQueryTimes list, totalTime

#### Modified: TestController.java
- Added `maxNormalTime` and `minNormalTime` to model attributes
- Passes all timing data to Thymeleaf template

#### New: test/slowpage.html
- Bootstrap 5 styled page
- Shows slow query execution time (typically ~3.5s)
- Shows normal query stats: count (250), avg, max, min times
- Shows total page load time (typically ~60s)
- "Run Again" button to re-execute queries

**SQL Fix**: Originally used `select count(*) from board order by ...` which is invalid in PostgreSQL (aggregate without GROUP BY). Fixed by adding `group by [column]` clause.

**Verification**:
- `curl -s --max-time 120 http://192.168.2.57:8080/test/slowpage` → 200 OK ✓
- Page displays: Slow Query ~3569ms, Normal Queries: 250 total, avg ~234ms, max 962ms, min 2ms

---

### Deployment
- All changes built with Java 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean build -x test`
- Deployed via `deploy.sh` to `192.168.2.57`
- Application restarted and verified running on port 8080
