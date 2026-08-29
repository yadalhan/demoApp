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
    @Select("select id, user_id, password, username, id_no, phone, id_no_blind_idx, phone_blind_idx " +
            "from users where user_id = #{userId}")
    @Results({
            @Result(column = "id_no", property = "residentRegistrationNumber", typeHandler = UserPiiTypeHandler.class),
            @Result(column = "phone", property = "phone", typeHandler = UserPiiTypeHandler.class),
            @Result(column = "id_no_blind_idx", property = "residentRegistrationNumberBlindIndex"),
            @Result(column = "phone_blind_idx", property = "phoneBlindIndex")
    })
    Optional<User> findByUserId(String userId);

    @Select("select exists(select 1 from users where user_id = #{userId})")
    boolean existsByUserId(String userId);

    /**
     * 이름/전화번호/주민등록번호로 검색 - 이름은 평문 LIKE, 전화번호/주민등록번호는 blind index 정확 일치.
     * 파라미터가 null/빈 값이면 그 조건은 무시한다(AND 결합, 조건이 하나도 없으면 전체 목록).
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
            @Result(column = "id_no", property = "residentRegistrationNumber", typeHandler = UserPiiTypeHandler.class),
            @Result(column = "phone", property = "phone", typeHandler = UserPiiTypeHandler.class),
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
