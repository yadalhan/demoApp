# 암호화 컬럼 조회 개발 가이드

demoApp에서 AES-GCM으로 암호화된 컬럼(`board.password`, `users.id_no`, `users.phone`)을 다루는 코드를 작성/수정할 때 참고하는 가이드입니다. 실제로 이 프로젝트에서 겪었던 버그들(TypeHandler 오등록, 이중 암호화, 캐시에 평문 노출, self-invocation 등)을 근거로 정리했습니다 - 전부 실제로 한 번씩 터졌던 것들입니다.

암호화/복호화 자체의 설계(KEK-DEK, blind index, TypeHandler 구현)는 [`vault-crypto/README.md`](../vault-crypto/README.md)에 있습니다. 이 문서는 **demoApp 안에서 그것들을 "어떻게 호출하며 쓰느냐"**에 집중합니다.

## 1. 기본 원칙

**암/복호화는 Mapper 경계에서 TypeHandler가 처리하고, Service 코드는 평문 Java 문자열만 다룬다.** `BoardService`/`UserService`는 `EnvelopeCryptoService`를 직접 호출하지 않습니다 - `BoardMapper`/`UserMapper`의 SQL에 `typeHandler=...`를 붙여서 저장/조회 시점에 자동으로 암/복호화되게 합니다. 새 컬럼을 추가할 때도 이 원칙을 유지하세요: Service 계층에 `cryptoService.encrypt(...)`/`decrypt(...)` 호출이 새로 생긴다면, 십중팔구 TypeHandler로 옮길 수 있는 코드입니다.

## 2. 컬럼마다 성격이 다르다 - 먼저 이것부터 판단

새 암호화 컬럼을 추가하기 전에, 그 컬럼이 있는 테이블에 **이 라이브러리 도입 이전의 데이터(레거시/비정형 포맷)가 섞여 있는지**부터 확인하세요. 이 판단에 따라 TypeHandler를 읽기 경로에도 걸 수 있는지가 갈립니다.

| | **레거시 데이터 없음** (예: `users.id_no`/`phone`) | **레거시 데이터 있음** (예: `board.password`, ~4.6만 건) |
|---|---|---|
| TypeHandler 적용 범위 | 읽기 + 쓰기 모두 | **쓰기만** |
| `@Select`에 typeHandler 지정 | 함 (`@Result(..., typeHandler = X.class)`) | **안 함** - 컬럼을 암호문 그대로 반환 |
| 이유 | 모든 행이 봉투 포맷이라 복호화 실패가 날 수 없음 | 봉투 포맷이 아닌 행을 읽으면 `CryptoException`이 터져서, 그 행을 스치는 **모든** `SELECT`(목록 조회 포함)가 깨짐 |
| "맞는지" 확인 방법 | 복호화된 값을 직접 비교해도 되지만, `EnvelopeCryptoService.validate()`(상수 시간 비교) 사용 권장 | 반드시 `EnvelopeCryptoService.validate()` - 암호문 상태 그대로 넘기고 그 안에서만 복호화 |

예시: [`UserMapper.findByUserId()`](src/main/java/com/xaan/demo/domain/mapper/UserMapper.java#L28-L36)는 `id_no`/`phone`에 `UserPiiTypeHandler`를 읽기 경로에도 걸어 자동 복호화하지만, [`BoardMapper.findById()`](src/main/java/com/xaan/demo/domain/mapper/BoardMapper.java#L24-L25)는 `password`에 아무 typeHandler도 안 걸어서 암호문 그대로 돌려줍니다.

## 3. 조회 상황별 패턴

### 3-1. 단건 조회 - 값 자체가 필요할 때 (레거시 없는 컬럼)

```java
// UserMapper - id_no/phone에 typeHandler를 걸어 자동 복호화
@Select("select id, user_id, password, username, id_no, phone, id_no_blind_idx, phone_blind_idx " +
        "from users where user_id = #{userId}")
@Results({
        @Result(column = "id_no", property = "residentRegistrationNumber", typeHandler = UserPiiTypeHandler.class),
        @Result(column = "phone", property = "phone", typeHandler = UserPiiTypeHandler.class),
})
Optional<User> findByUserId(String userId);
```
호출하는 Service 코드는 그냥 `user.getResidentRegistrationNumber()`를 쓰면 됩니다 - 복호화를 신경 쓸 필요 없음.

### 3-2. "맞는지"만 확인할 때 (레거시 있는 컬럼 - 게시글 비밀번호가 이 경우)

값을 노출/복원할 필요 없이 일치 여부만 필요하면 `decrypt()` 대신 `validate()`를 씁니다. [`PasswordService.validateBoardPassword()`](src/main/java/com/xaan/demo/service/PasswordService.java)가 이 패턴입니다:

```java
public boolean validateBoardPassword(String rawPassword, String encryptedPassword) {
    return boardCryptoService.validate(rawPassword, encryptedPassword); // 내부에서 decrypt + 상수시간 비교
}
```
`board.password`처럼 SELECT가 암호문을 그대로 반환하는 컬럼에서, 복호화는 **여기서만** 일어납니다 - 목록/상세 조회에서는 절대 복호화하지 않습니다.

### 3-3. 암호화 컬럼이 필요 없는 조회는 애초에 select하지 않는다

로그인 검증은 BCrypt 비밀번호 해시 하나만 있으면 됩니다. `id_no`/`phone`을 굳이 같이 조회해서 복호화하면, 그 컬럼의 ciphertext 문제(예: 과거 키 재발급으로 무효화된 데이터) 하나가 **비밀번호가 맞는 계정의 로그인 자체를 막아버립니다** - 실제로 이 프로젝트에서 벌어진 일입니다. [`UserMapper.findAuthByUserId()`](src/main/java/com/xaan/demo/domain/mapper/UserMapper.java#L40-L41)처럼 필요한 컬럼만 별도 조회 메서드로 분리하세요:

```java
// PII 컬럼을 아예 select하지 않음 - 그 컬럼들 문제가 로그인을 막을 수 없음
@Select("select id, user_id, password, username from users where user_id = #{userId}")
Optional<User> findAuthByUserId(String userId);
```

**규칙**: 조회 목적에 실제로 필요한 컬럼만 select한다. "어차피 User 객체니까 전부 가져오자"는 안 됩니다.

### 3-4. 목록/여러 행을 한 번에 보여줄 때 - raw 조회 + 행별 복호화(fallback 포함)

여러 행을 한 번에 반환하는 쿼리(`/users` 검색 목록 등)에 typeHandler를 읽기 경로에 걸면, **행 하나의 ciphertext 문제가 전체 조회를 예외로 죽입니다.** 레거시 데이터가 없는 테이블이라도, 과거 키 재발급 등으로 특정 행만 복호화가 안 되는 경우가 실제로 있었습니다. 그래서 목록성 조회는 다음 패턴을 씁니다:

1. **Mapper**: typeHandler 없이 raw(ciphertext) 그대로 반환 - [`UserMapper.search()`](src/main/java/com/xaan/demo/domain/mapper/UserMapper.java#L54-L80) 참고
2. **Service**: 행마다 개별적으로 복호화를 시도하고, 실패하면 그 행만 대체 문자열로 표시 - [`PasswordService.decryptUserPiiForDisplay()`](src/main/java/com/xaan/demo/service/PasswordService.java)

```java
// PasswordService - 실패해도 던지지 않고 대체 문자열 반환
public String decryptUserPiiForDisplay(String encryptedText) {
    if (encryptedText == null || encryptedText.isEmpty()) return encryptedText;
    try {
        return userPiiCryptoService.decrypt(encryptedText);
    } catch (RuntimeException e) {
        logger.warn("Failed to decrypt user-pii value for display: {}", e.getMessage());
        return "(복호화 실패)";
    }
}
```
```java
// UserService.search() - 행별로 개별 복호화
return userMapper.search(name, phoneBlindIndex, rrnBlindIndex).stream()
        .map(user -> new UserResponseDto(
                user,
                passwordService.decryptUserPiiForDisplay(user.getResidentRegistrationNumber()),
                passwordService.decryptUserPiiForDisplay(user.getPhone())))
        .collect(Collectors.toList());
```

**단건 조회(3-1)와 다른 점**: 단건은 그 하나의 값이 잘못되면 예외를 던지는 게 맞는 경우가 많지만(호출자가 뭔가 잘못됐다는 걸 알아야 함), 목록은 한 행 때문에 나머지 정상 행까지 못 보여주면 안 됩니다.

### 3-5. 검색 - 암호화 컬럼은 `=`/`LIKE`로 직접 검색할 수 없다

AES-GCM은 매번 랜덤 IV를 써서 같은 평문도 암호문이 매번 다릅니다. `WHERE phone = ?`는 원천적으로 불가능합니다. 이 프로젝트는 **Blind Index**(결정적 HMAC)로 정확 일치 검색을 지원합니다:

- 저장 시: 암호화된 값 옆에 `phone_blind_idx`/`id_no_blind_idx` 컬럼으로 HMAC을 같이 저장 ([`UserService.register()`](src/main/java/com/xaan/demo/service/UserService.java))
- 검색 시: 검색어를 **같은 방식으로 정규화**한 뒤 HMAC을 계산해 그 컬럼을 `=`로 조회 ([`UserService.search()`](src/main/java/com/xaan/demo/service/UserService.java))

```java
String phoneBlindIndex = passwordService.computePhoneBlindIndex(normalizePhone(phone));
// ... userMapper.search(name, phoneBlindIndex, rrnBlindIndex) - WHERE phone_blind_idx = ?
```

**주의**: 정확 일치만 가능합니다(부분/LIKE 검색 불가). 저장 시점과 검색 시점의 정규화 방식(숫자만 남기기 등)이 다르면 조용히 매칭이 실패하니, 정규화는 반드시 한 곳에서 통일해서 처리하세요(이 프로젝트는 `UserService.normalizePhone()`).

이름처럼 애초에 암호화하지 않는 컬럼은 그냥 평문 `LIKE`로 검색합니다 - blind index가 필요 없습니다.

### 3-6. Redis 등으로 캐싱할 때 - 캐시에는 절대 복호화된 값을 올리지 않는다

`@Cacheable`은 메서드가 리턴하는 값을 그대로 직렬화해 캐시에 저장합니다. 복호화된 DTO를 리턴하는 메서드에 캐싱을 걸면 평문 개인정보가 Redis에 그대로 올라갑니다. [`UserSearchCacheService`](src/main/java/com/xaan/demo/service/UserSearchCacheService.java)가 이 프로젝트의 정답 패턴입니다 - **raw(ciphertext) 조회만 캐싱하고, 복호화는 캐시에서 꺼낸 뒤 매번 수행**:

```java
@Service
public class UserSearchCacheService {
    @Cacheable(value = "userSearchRaw", key = "...")
    public List<User> search(String name, String phoneBlindIndex, String rrnBlindIndex) {
        return userMapper.search(name, phoneBlindIndex, rrnBlindIndex); // ciphertext 그대로
    }
}
```
`UserService.searchCached(...)`는 이 빈을 호출한 뒤 매번 `decryptUserPiiForDisplay(...)`로 복호화합니다 - 캐시 적중 여부와 무관하게.

**함정 (self-invocation)**: 캐싱 메서드는 반드시 **다른 빈**에 둬야 합니다. 같은 클래스 안에서 `this.searchRawCached(...)`처럼 직접 호출하면 Spring 프록시를 우회해서 `@Cacheable`이 조용히 동작하지 않습니다 - 예외도, 로그도 없이 그냥 캐싱이 안 됩니다. 실제로 이 프로젝트에서 처음 이렇게 구현했다가 바로 다음 배포에서 사용자가 "캐시가 안 먹는다"고 발견했습니다. 캐싱 도입 시 반드시:
1. 캐싱 대상 메서드를 별도 `@Service` 빈으로 분리
2. 캐시 값에 담기는 엔티티가 `Serializable`을 구현하는지 확인 (기본 JDK 직렬화 사용 중)
3. 캐싱 대상 쓰기 경로(예: 신규 등록)에 `@CacheEvict`를 걸어 캐시 무효화 - 안 그러면 새 데이터가 TTL 동안 안 보임
4. `redis-cli` 없이 검증해야 한다면, `pg_stat_user_tables.seq_scan`(해당 쿼리가 `Seq Scan`을 쓰는지는 `EXPLAIN`으로 먼저 확인)으로 "같은 조건 재조회 시 DB를 다시 타는지"를 직접 확인하는 것도 유효한 방법입니다.

### 3-7. 재암호화/키 로테이션 배치 - 항상 raw 전용 메서드를 따로 둔다

DEK 로테이션 후 재암호화 배치([`DekReencryptionService`](src/main/java/com/xaan/demo/service/DekReencryptionService.java))는 ciphertext의 `keyVersion` 헤더를 직접 검사해야 하므로, 절대 typeHandler를 거치면 안 됩니다 - 이미 암호문인 값이 다시 `#{...,typeHandler=...}` 파라미터로 들어가면 **이중 암호화**가 됩니다. 그래서 이런 배치 전용 메서드는 이름에 `Raw`를 붙여 명확히 구분합니다:

```java
// 읽기: raw ciphertext 그대로
@Select("select id, user_id, password, username, id_no from users order by id")
List<User> findAllRaw();

// 쓰기: typeHandler 없이 이미 암호화된 값을 그대로 저장
@Update("update users set id_no = #{residentRegistrationNumber} where id = #{id}")
int updateResidentRegistrationNumberRaw(@Param("id") Long id, @Param("residentRegistrationNumber") String residentRegistrationNumber);
```

**같은 함정이 일반 UPDATE에도 있습니다**: [`BoardService.update()`](src/main/java/com/xaan/demo/service/BoardService.java)가 비밀번호를 바꾸지 않는 수정을 별도 메서드(`updateTitleContent`)로 분리한 이유도 이것입니다 - 안 그러면 "바뀌지 않은 기존 비밀번호"(이미 암호문)를 typeHandler가 있는 파라미터로 다시 흘려보내 이중 암호화하게 됩니다. **규칙**: UPDATE 시 typeHandler가 걸린 컬럼은, 그 값을 실제로 새로 암호화해야 할 때만 SQL에 포함시킨다.

## 4. 신규 암호화 컬럼 추가 체크리스트

1. 이 컬럼이 있는 테이블에 레거시/비정형 데이터가 섞여 있는가? → §2 표로 읽기 경로 적용 여부 결정
2. 이 컬럼에 대해 검색(정확 일치)이 필요한가? → 필요하면 Blind Index 컬럼(`*_blind_idx`)과 계산 로직 추가, DB 마이그레이션에 인덱스도 함께 (`migrations/001_add_user_phone_and_blind_index.sql` 참고)
3. 이 컬럼이 필요 없는 조회 경로(로그인 등)가 있는가? → 그 경로는 이 컬럼을 아예 select하지 않는 전용 메서드로 분리
4. 이 컬럼을 여러 행과 함께 목록으로 보여줄 일이 있는가? → raw 조회 + 행별 복호화(fallback) 패턴 적용
5. 이 컬럼이 포함된 조회 결과를 캐싱할 계획이 있는가? → raw만 캐싱, 복호화는 캐시 밖에서, 캐싱 메서드는 별도 빈
6. UPDATE 시 "값이 바뀌지 않는 경우"가 존재하는가? → 그 컬럼을 건드리지 않는 별도 UPDATE 메서드로 분리
7. 재암호화 배치 대상인가? → `*Raw` 메서드 쌍(조회+저장) 추가

## 5. 흔한 실수 Top 5 (전부 실제로 겪은 것)

| # | 실수 | 증상 | 원인 |
|---|------|------|------|
| 1 | 레거시 데이터 있는 컬럼의 읽기 경로에 typeHandler를 검 | 목록/상세 조회가 `CryptoException`으로 500 | §2 표 무시 |
| 2 | 바뀌지 않은 암호문 값을 UPDATE의 typeHandler 파라미터로 다시 흘림 | 데이터가 조용히 이중 암호화되어 나중에 복호화 불가 | §3-7 |
| 3 | 로그인처럼 암호화 컬럼이 필요 없는 조회에서 그 컬럼까지 select | 관련 없는 계정의 PII 문제가 그 계정 로그인을 막음 | §3-3 |
| 4 | 목록 조회에 typeHandler를 읽기 경로에 걸어 한 번에 여러 행을 복호화 | 행 하나의 ciphertext 문제로 전체 목록이 500 | §3-4 |
| 5 | `@Cacheable` 메서드를 같은 클래스 안에서 `this`로 직접 호출 | 예외 없이 캐싱이 조용히 동작하지 않음 | §3-6 |

이외에도 vault-crypto 라이브러리 자체의 버그(TypeHandler가 `BaseTypeHandler`의 `TypeReference` 자동 타입 등록 때문에 의도치 않게 **모든** `String` 컬럼의 기본 핸들러가 되어버린 사건)가 있었습니다 - 라이브러리를 직접 수정하는 게 아니라면 마주칠 일은 적지만, 자세한 내용은 `vault-crypto/README.md`의 Release History v0.0.10 항목 참고.

## 6. 관련 문서

- [`vault-crypto/README.md`](../vault-crypto/README.md) - `EnvelopeCryptoService`/`BlindIndexService`/`EnvelopeCryptoTypeHandler` 자체의 설계와 API
- [`KEY_ROTATION_RUNBOOK.md`](KEY_ROTATION_RUNBOOK.md) - KEK/DEK 로테이션 운영 절차
- [`KEK_DEK_ENCRYPTION_PLAN.md`](KEK_DEK_ENCRYPTION_PLAN.md) - 전체 암호화 아키텍처 설계 배경
- [`AGENTS.md`](AGENTS.md) - 이 가이드에 나온 각 사건의 상세 발견/수정 경위 (날짜별 변경 이력)
