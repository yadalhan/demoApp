package com.xaan.demo.domain.mapper;

import com.xaan.demo.config.mybatis.UserPiiTypeHandler;
import com.xaan.demo.domain.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

@Mapper
public interface UserMapper {

    String INSERT_COLUMNS = "user_id, password, username, id_no, phone, id_no_blind_idx, phone_blind_idx";
    String INSERT_VALUES = "#{userId}, #{password}, #{username}, " +
            "#{residentRegistrationNumber,typeHandler=com.xaan.demo.config.mybatis.UserPiiTypeHandler}, " +
            "#{phone,typeHandler=com.xaan.demo.config.mybatis.UserPiiTypeHandler}, " +
            "#{residentRegistrationNumberBlindIndex}, #{phoneBlindIndex}";

    @Insert("insert into users (" + INSERT_COLUMNS + ") values (" + INSERT_VALUES + ")")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User user);

    // 업무용 조회 - id_no/phone은 UserPiiTypeHandler가 투명하게 복호화해 평문으로 돌려준다.
    // users 테이블은 KEK-DEK 도입 시점에 초기화되어 레거시(비-봉투 포맷) 데이터가 없으므로 읽기 경로에 걸어도 안전하다.
    // 다만 이 컬럼들의 ciphertext가 현재 로드된 DEK와 맞지 않는(예: 과거 키 재발급으로 무효화된) 단일 행이 있으면
    // 이 메서드는 통째로 실패한다 - 한 행 때문에 여러 행을 함께 다루는 화면 전체가 죽는 걸 피해야 하는 곳
    // (검색 목록 등)에서는 이 메서드 대신 search()처럼 raw로 읽고 행별로 안전하게 복호화한다.
    @Select("select id, user_id, password, username, id_no, phone, id_no_blind_idx, phone_blind_idx " +
            "from users where user_id = #{userId}")
    @Results({
            @Result(column = "id_no", property = "residentRegistrationNumber", typeHandler = UserPiiTypeHandler.class),
            @Result(column = "phone", property = "phone", typeHandler = UserPiiTypeHandler.class),
            @Result(column = "id_no_blind_idx", property = "residentRegistrationNumberBlindIndex"),
            @Result(column = "phone_blind_idx", property = "phoneBlindIndex")
    })
    Optional<User> findByUserId(String userId);

    // 로그인 검증 전용 - BCrypt 비교에는 password만 있으면 되고 id_no/phone은 필요 없다. 그 컬럼들을 굳이
    // 복호화하면(findByUserId처럼) PII 쪽 ciphertext 문제 하나가 이 계정의 로그인 자체를 막아버리게 된다.
    @Select("select id, user_id, password, username from users where user_id = #{userId}")
    Optional<User> findAuthByUserId(String userId);

    @Select("select exists(select 1 from users where user_id = #{userId})")
    boolean existsByUserId(String userId);

    /**
     * 이름/전화번호/주민등록번호로 검색 - 이름은 평문 LIKE, 전화번호/주민등록번호는 blind index 정확 일치.
     * 파라미터가 null/빈 값이면 그 조건은 무시한다(AND 결합, 조건이 하나도 없으면 전체 목록).
     *
     * <p>id_no/phone은 일부러 typeHandler 없이 ciphertext 그대로 반환한다 - 목록 조회이므로 행이 여러 개고,
     * 그중 한 행의 ciphertext만 문제가 있어도(findByUserId였다면) 전체 조회가 예외로 죽어 나머지 정상 행까지
     * 볼 수 없게 된다. 복호화는 UserService.search()가 행별로 개별 시도하며 실패한 행만 표시를 대체한다.
     */
    @Select("""
            <script>
            select id, user_id, password, username, id_no, phone, id_no_blind_idx, phone_blind_idx
            from users
            <where>
                <if test="name != null and name != ''">
                    and username like concat('%', #{name}, '%')
                </if>
                <if test="phoneBlindIndex != null and phoneBlindIndex != ''">
                    and phone_blind_idx = #{phoneBlindIndex}
                </if>
                <if test="rrnBlindIndex != null and rrnBlindIndex != ''">
                    and id_no_blind_idx = #{rrnBlindIndex}
                </if>
            </where>
            order by id desc
            </script>
            """)
    @Results({
            @Result(column = "id_no", property = "residentRegistrationNumber"),
            @Result(column = "phone", property = "phone"),
            @Result(column = "id_no_blind_idx", property = "residentRegistrationNumberBlindIndex"),
            @Result(column = "phone_blind_idx", property = "phoneBlindIndex")
    })
    List<User> search(@Param("name") String name,
                       @Param("phoneBlindIndex") String phoneBlindIndex,
                       @Param("rrnBlindIndex") String rrnBlindIndex);

    // 재암호화 배치(DekReencryptionService) 전용 - id_no를 암호문 그대로 읽어야 keyVersion 헤더를 검사할 수 있으므로
    // UserPiiTypeHandler를 걸지 않는다. phone은 이 메서드가 다루지 않는다(별도 배치 없음, 신규 컬럼이라 백필 대상이 없음).
    @Select("select id, user_id, password, username, id_no from users order by id")
    List<User> findAllRaw();

    // 재암호화 배치 전용 - 이미 암호화된 ciphertext를 그대로 저장한다(UserPiiTypeHandler를 걸면 이중 암호화됨).
    // blind index는 평문이 바뀌지 않는 한(DEK 로테이션은 평문에 영향을 주지 않음) 함께 갱신할 필요가 없다.
    @Update("update users set id_no = #{residentRegistrationNumber} where id = #{id}")
    int updateResidentRegistrationNumberRaw(@Param("id") Long id,
                                            @Param("residentRegistrationNumber") String residentRegistrationNumber);
}
