package com.xaan.demo.service;

import com.xaan.demo.domain.entity.User;
import com.xaan.demo.domain.mapper.UserMapper;
import com.xaan.demo.dto.UserRegisterRequestDto;
import com.xaan.demo.dto.UserResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class UserService {
    private final UserMapper userMapper;
    private final PasswordService passwordService;

    // 신규 가입자가 어떤 검색 조합에든 걸릴 수 있으므로 캐시된 검색 결과 전체를 무효화한다 -
    // 그렇지 않으면 /users2에서 방금 가입한 사용자가 TTL 동안 보이지 않게 된다.
    @CacheEvict(value = "userSearchRaw", allEntries = true)
    @Transactional
    public Long register(UserRegisterRequestDto dto) {
        if (dto.getUserId() == null || dto.getUserId().isEmpty()) {
            throw new IllegalArgumentException("사용자 ID를 입력해주세요.");
        }
        if (dto.getPassword() == null || dto.getPassword().isEmpty()) {
            throw new IllegalArgumentException("비밀번호를 입력해주세요.");
        }
        if (dto.getUsername() == null || dto.getUsername().isEmpty()) {
            throw new IllegalArgumentException("사용자 이름을 입력해주세요.");
        }
        if (dto.getResidentRegistrationNumberFront() == null || !dto.getResidentRegistrationNumberFront().matches("\\d{6}")) {
            throw new IllegalArgumentException("주민등록번호 앞 6자리를 숫자로 입력해주세요.");
        }
        if (dto.getResidentRegistrationNumberBack() == null || !dto.getResidentRegistrationNumberBack().matches("\\d{7}")) {
            throw new IllegalArgumentException("주민등록번호 뒤 7자리를 숫자로 입력해주세요.");
        }
        validateKoreanResidentRegistrationNumber(dto.getResidentRegistrationNumberFront(), dto.getResidentRegistrationNumberBack());

        if (dto.getPhone() == null || dto.getPhone().isEmpty()) {
            throw new IllegalArgumentException("전화번호를 입력해주세요.");
        }
        String normalizedPhone = normalizePhone(dto.getPhone());
        if (!normalizedPhone.matches("\\d{9,11}")) {
            throw new IllegalArgumentException("전화번호는 숫자만 9~11자리로 입력해주세요.");
        }

        if (userMapper.existsByUserId(dto.getUserId())) {
            throw new IllegalArgumentException("이미 존재하는 사용자 ID입니다.");
        }

        // 사용자 비밀번호는 BCrypt 단방향 해시. 주민등록번호/전화번호는 평문 그대로 넘긴다 -
        // UserMapper.insert()의 UserPiiTypeHandler가 AES-GCM으로 암호화해 저장한다.
        String hashedPassword = passwordService.hashUserPassword(dto.getPassword());
        String residentRegistrationNumber = dto.getResidentRegistrationNumber();

        User user = User.builder()
                .userId(dto.getUserId())
                .password(hashedPassword)
                .username(dto.getUsername())
                .residentRegistrationNumber(residentRegistrationNumber)
                .phone(normalizedPhone)
                .residentRegistrationNumberBlindIndex(passwordService.computeRrnBlindIndex(residentRegistrationNumber))
                .phoneBlindIndex(passwordService.computePhoneBlindIndex(normalizedPhone))
                .build();

        userMapper.insert(user);
        return user.getId();
    }

    private String normalizePhone(String phone) {
        return phone.replaceAll("[^0-9]", "");
    }

    /**
     * 사용자 검색 - 이름은 부분 일치, 전화번호/주민등록번호는 blind index를 통한 정확 일치.
     * 검색어가 모두 비어 있으면 전체 목록을 반환한다.
     *
     * <p>userMapper.search()는 id_no/phone을 ciphertext 그대로 돌려준다(UserMapper 주석 참고) -
     * 여기서 행별로 개별 복호화를 시도해, 한 행의 ciphertext에 문제가 있어도 그 행만 표시를 대체하고
     * 나머지 행은 정상적으로 보여준다.
     */
    public List<UserResponseDto> search(String name, String phone, String residentRegistrationNumber) {
        String phoneBlindIndex = (phone == null || phone.isEmpty())
                ? null : passwordService.computePhoneBlindIndex(normalizePhone(phone));
        String rrnBlindIndex = (residentRegistrationNumber == null || residentRegistrationNumber.isEmpty())
                ? null : passwordService.computeRrnBlindIndex(residentRegistrationNumber);
        return userMapper.search(name, phoneBlindIndex, rrnBlindIndex).stream()
                .map(user -> new UserResponseDto(
                        user,
                        passwordService.decryptUserPiiForDisplay(user.getResidentRegistrationNumber()),
                        passwordService.decryptUserPiiForDisplay(user.getPhone())))
                .collect(Collectors.toList());
    }

    /**
     * 사용자 검색(Redis 캐시 적용판, 사용자목록2/{@code /users2}) - 조회 조건은 search()와 동일하지만
     * DB 조회 결과를 Redis에 캐싱한다. 캐시에는 searchRawCached()가 반환하는, id_no/phone이
     * ciphertext 그대로인 User 목록만 저장된다 - 복호화는 캐시에서 꺼낸 뒤 이 메서드가 매번 수행하므로,
     * 캐시 적중 여부와 무관하게 Redis에는 평문 개인정보가 절대 올라가지 않는다.
     */
    public List<UserResponseDto> searchCached(String name, String phone, String residentRegistrationNumber) {
        String phoneBlindIndex = (phone == null || phone.isEmpty())
                ? null : passwordService.computePhoneBlindIndex(normalizePhone(phone));
        String rrnBlindIndex = (residentRegistrationNumber == null || residentRegistrationNumber.isEmpty())
                ? null : passwordService.computeRrnBlindIndex(residentRegistrationNumber);
        return searchRawCached(name, phoneBlindIndex, rrnBlindIndex).stream()
                .map(user -> new UserResponseDto(
                        user,
                        passwordService.decryptUserPiiForDisplay(user.getResidentRegistrationNumber()),
                        passwordService.decryptUserPiiForDisplay(user.getPhone())))
                .collect(Collectors.toList());
    }

    // 캐싱 대상은 반드시 이 raw 조회여야 한다 - userMapper.search()가 id_no/phone을 복호화하지 않고
    // 그대로 반환하므로(UserMapper 주석 참고), Redis에 저장되는 값도 항상 ciphertext뿐이다.
    @Cacheable(value = "userSearchRaw", key = "(#name ?: '') + '|' + (#phoneBlindIndex ?: '') + '|' + (#rrnBlindIndex ?: '')")
    public List<User> searchRawCached(String name, String phoneBlindIndex, String rrnBlindIndex) {
        return userMapper.search(name, phoneBlindIndex, rrnBlindIndex);
    }

    public Optional<User> findByUserId(String userId) {
        return userMapper.findByUserId(userId);
    }

    private void validateKoreanResidentRegistrationNumber(String front, String back) {
        char sexDigit = back.charAt(0);
        if (sexDigit < '1' || sexDigit > '8') {
            throw new IllegalArgumentException("주민등록번호 뒤 첫 자리는 1~8 이어야 합니다.");
        }
        int century = (sexDigit == '1' || sexDigit == '2' || sexDigit == '5' || sexDigit == '6') ? 1900 : 2000;
        int year = century + Integer.parseInt(front.substring(0, 2));
        int month = Integer.parseInt(front.substring(2, 4));
        int day = Integer.parseInt(front.substring(4, 6));
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("주민등록번호의 생년월일이 유효하지 않습니다.");
        }

        int[] weights = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        String full = front + back;
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (full.charAt(i) - '0') * weights[i];
        }
        int expectedCheckDigit = (11 - (sum % 11)) % 10;
        int actualCheckDigit = full.charAt(12) - '0';
        if (expectedCheckDigit != actualCheckDigit) {
            throw new IllegalArgumentException("주민등록번호 검증 실패: 체크섬이 일치하지 않습니다.");
        }
    }

    /**
     * 로그인 검증 - BCrypt 사용. id_no/phone은 필요 없으므로 findAuthByUserId로 조회한다 -
     * 그 컬럼들을 복호화하는 findByUserId를 썼다면, PII ciphertext 문제 하나가 이 계정의
     * 로그인 자체를 막아버리게 된다(비밀번호가 맞아도 로그인 불가).
     */
    public boolean validateLogin(String userId, String rawPassword) {
        Optional<User> userOpt = userMapper.findAuthByUserId(userId);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        // BCrypt 검증
        return passwordService.validateUserPassword(rawPassword, user.getPassword());
    }
}
