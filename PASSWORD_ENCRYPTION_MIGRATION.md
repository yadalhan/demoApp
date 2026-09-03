# Password Encryption Migration 문서

## 개요

본 문서는 demoApp의 비밀번호 암호화 방식을 BCrypt 단방향 해시로 마이그레이션한 작업을 기록합니다.

### 요구사항
- **users.password**: BCrypt 단방향 해시 적용
- **board.password**: 기존 AES-GCM 양방향 암호화 유지

---

## 변경된 파일 목록

### 1. PasswordService.java
**위치**: `src/main/java/com/xaan/demo/service/PasswordService.java`

**변경 내용**:
- 기존 VaultCryptoService 기반의 AES-GCM 암호화를 직접 구현으로 대체
- BCrypt 단방향 해시 메서드 추가 (users.password용)
- AES-GCM 암호화/복호화 메서드 유지 (board.password용)
- Vault KV v2 형식 파싱 지원

**주요 메서드**:
```java
// 사용자 비밀번호 (BCrypt 단방향 해시)
public String hashUserPassword(String password)  // BCrypt 해시 생성
public boolean validateUserPassword(String rawPassword, String hashedPassword)  // BCrypt 검증

// 게시글 비밀번호 (AES-GCM 양방향 암호화)
public String encryptBoardPassword(String password)  // AES-GCM 암호화
public String decryptBoardPassword(String encryptedPassword)  // AES-GCM 복호화
public boolean validateBoardPassword(String rawPassword, String encryptedPassword)  // 검증
```

### 2. UserService.java
**위치**: `src/main/java/com/xaan/demo/service/UserService.java`

**변경 내용**:
- register(): BCrypt 해시 사용
- validateLogin(): BCrypt 검증 사용

```java
// 등록 시
String hashedPassword = passwordService.hashUserPassword(dto.getPassword());

// 로그인 검증 시
return passwordService.validateUserPassword(rawPassword, user.getPassword());
```

### 3. BoardService.java
**위치**: `src/main/java/com/xaan/demo/service/BoardService.java`

**변경 내용**:
- save(): AES-GCM 암호화 사용
- update(): AES-GCM 암호화 사용

```java
// 저장/수정 시
board.updatePassword(passwordService.encryptBoardPassword(board.getPassword()));

// 비밀번호 검증
public boolean verifyPassword(Long id, String password) {
    return passwordService.validateBoardPassword(password, board.getPassword());
}
```

### 4. User.java (엔티티)
**위치**: `src/main/java/com/xaan/demo/domain/entity/User.java`

**변경 내용**:
- 비밀번호 업데이트를 위한 `updatePassword()` 메서드 추가

```java
public void updatePassword(String password) {
    this.password = password;
}
```

### 5. build.gradle
**위치**: `build.gradle`

**변경 내용**:
- `vault-crypto` 의존성 제거
- `spring-security-crypto` 의존성 추가 (BCrypt 지원)

```gradle
// 추가
implementation 'org.springframework.security:spring-security-crypto'

// 제거
// implementation 'com.xaan:vault-crypto:0.0.1-SNAPSHOT'
```

### 6. deploy.sh
**위치**: `deploy.sh`

**변경 내용**:
- JAR 파일 버전 업데이트 (0.0.3 → 0.0.4)

### 7. decrypt_passwords.py
**위치**: `decrypt_passwords.py`

**변경 내용**:
- BCrypt 검증 스크립트로 재작성
- AES-GCM 암호화 감지 기능 추가
- 로컬 BCrypt 기능 테스트 추가

### 8. Python 환경 (my_env)
**위치**: `my_env/lib/python3.12/site-packages/`

**변경 내용**:
- bcrypt 패키지 설치
- psycopg2-binary 패키지 설치

---

## 아키텍처

### 암호화 방식 분리

```
┌─────────────────────────────────────────────────────────────────┐
│                     PasswordService                              │
├─────────────────────────────────────────────────────────────────┤
│  users.password                          board.password          │
│  ────────────────                        ──────────────          │
│  BCrypt (단방향 해시)                    AES-GCM (양방향 암호화) │
│                                                                  │
│  hashUserPassword() ──────► 해시        encryptBoardPassword()  │
│  validateUserPassword() ◄── 검증        decryptBoardPassword() │
│                                          validateBoardPassword()│
└─────────────────────────────────────────────────────────────────┘
```

### 데이터 흐름

**사용자 등록/로그인**:
```
사용자 입력 → hashUserPassword() → BCrypt 해시 → DB 저장
로그인 시 → validateUserPassword() → BCrypt.matches() → 검증결과
```

**게시글 비밀번호**:
```
사용자 입력 → encryptBoardPassword() → AES-GCM 암호화 → DB 저장
검증 시 → decryptBoardPassword() → 평문비교 → 검증결과
```

---

## 데이터베이스 검증 결과

### 현재 상태 (2026-05-11)

| 테이블 | BCrypt 해시 | AES-GCM 암호화 | 비고 |
|--------|-------------|---------------|------|
| users  | 3개 (신규)  | 5개 (기존)    | 기존은 마이그레이션 필요 |
| board  | 0개         | 11개          | 모두 AES-GCM 유지 |

### 검증된 신규 데이터

**BCrypt 해시 사용자**:
- testbcrypt (TestBCrypt)
- testbcrypt2 (TestUser2)
- testbcrypt3 (TestUser3)

**AES-GCM 암호화된 게시물**:
- ID 2017593 (신규 생성)

---

## 마이그레이션 고려사항

### 기존 사용자 비밀번호

기존 5명의 사용자의 비밀번호는 AES-GCM으로 암호화되어 있습니다.

**마이그레이션 옵션**:
1. **자동 마이그레이션**: 사용자가 로그인할 때 BCrypt로 재해싱 (추후 구현 필요)
2. **수동 재설정**: 사용자가 비밀번호 재설정

### 기존 게시물 비밀번호

기존 게시물의 비밀번호는 AES-GCM으로 유지됩니다. 복호화가 필요한 경우:
- `decryptBoardPassword()` 메서드 사용
- Python 스크립트로 검증 가능

---

## 테스트 방법

### 1. Java 빌드 및 배포

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

cd /home/xaan/projects/oc/demoApp
./gradlew clean build
bash deploy.sh
```

### 2. Python 검증 스크립트

```bash
PYTHONPATH="/home/xaan/projects/oc/demoApp/my_env/lib/python3.12/site-packages:$PYTHONPATH" \
python3 /home/xaan/projects/oc/demoApp/decrypt_passwords.py
```

### 3. API 테스트

**사용자 등록**:
```bash
curl -X POST "http://192.168.2.57:8080/register?userId=testuser&username=TestUser&password=password123"
```

**게시글 생성**:
```bash
curl -X POST "http://192.168.2.57:8080/api/v1/posts" \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","content":"Content","author":"test","password":"boardpass"}'
```

---

##Known Issues / TODO

1. **Vault 연결 실패 처리**: PasswordService의 init() 메서드가 Vault 연결 실패 시 예외를 던짐 (fail-fast 동작)
2. **레거시 사용자 마이그레이션**: 기존 AES-GCM 암호화된 사용자 비밀번호를 BCrypt로 자동 마이그레이션하는 로직 구현 필요
3. **버전 관리**: JAR 버전을 0.0.4로 업데이트 (추후语义 versioning 적용 권장)

---

## 참고 문서

- BCrypt: https://en.wikipedia.org/wiki/Bcrypt
- AES-GCM: https://en.wikipedia.org/wiki/Galois/Counter_Mode
- Spring Security BCrypt: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html

---

## 변경 이력

| 날짜 | 변경 내용 | 버전 |
|------|-----------|------|
| 2026-05-11 | 초기 구현 | 0.0.4 |