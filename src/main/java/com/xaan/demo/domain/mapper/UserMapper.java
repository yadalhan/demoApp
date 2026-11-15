package com.xaan.demo.domain.mapper;

import com.xaan.demo.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 회원 매퍼. SQL은 {@code src/main/resources/mapper/UserMapper.xml}에서 관리한다.
 *
 * <p>업무 조회(findByUserId)는 id_no/phone을 UserPiiTypeHandler로 투명하게 복호화한다. users 테이블은
 * KEK-DEK 도입 시점에 초기화되어 레거시(비-봉투 포맷) 데이터가 없으므로 읽기 경로에 걸어도 안전하다.
 * 다만 단일 행의 ciphertext가 현재 DEK와 맞지 않으면 이 메서드는 통째로 실패하므로, 여러 행을 보여주는
 * 검색(search)은 raw로 읽고 서비스가 행별로 안전하게 복호화한다.
 */
@Mapper
public interface UserMapper {

    int insert(User user);

    Optional<User> findByUserId(@Param("userId") String userId);

    /** 로그인 전용 - BCrypt 비교에 필요한 값만 읽는다(PII 복호화 없음). */
    Optional<User> findAuthByUserId(@Param("userId") String userId);

    boolean existsByUserId(@Param("userId") String userId);

    /**
     * 이름/전화번호/주민등록번호 검색 - 이름은 평문 LIKE, 전화번호/주민등록번호는 blind index 정확 일치.
     * 파라미터가 null/빈 값이면 그 조건은 무시한다(AND 결합, 조건이 하나도 없으면 전체 목록).
     * id_no/phone은 ciphertext 그대로 반환하고 서비스가 행별로 복호화한다.
     */
    List<User> search(@Param("name") String name,
                      @Param("phoneBlindIndex") String phoneBlindIndex,
                      @Param("rrnBlindIndex") String rrnBlindIndex);

    /** 재암호화 배치 전용 - id_no를 ciphertext 그대로 읽는다. */
    List<User> findAllRaw();

    /** 재암호화 배치 전용 - 이미 암호화된 ciphertext를 그대로 저장한다. */
    int updateResidentRegistrationNumberRaw(@Param("id") Long id,
                                            @Param("residentRegistrationNumber") String residentRegistrationNumber);
}
