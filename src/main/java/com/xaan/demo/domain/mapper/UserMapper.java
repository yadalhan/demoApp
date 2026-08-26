package com.xaan.demo.domain.mapper;

import com.xaan.demo.domain.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

@Mapper
public interface UserMapper {

    String COLUMNS = "id, user_id, password, username, id_no as resident_registration_number";

    @Insert("insert into users (user_id, password, username, id_no) " +
            "values (#{userId}, #{password}, #{username}, #{residentRegistrationNumber})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User user);

    @Select("select " + COLUMNS + " from users where user_id = #{userId}")
    Optional<User> findByUserId(String userId);

    @Select("select exists(select 1 from users where user_id = #{userId})")
    boolean existsByUserId(String userId);

    @Select("select " + COLUMNS + " from users order by id")
    List<User> findAll();

    @Update("update users set id_no = #{residentRegistrationNumber} where id = #{id}")
    int updateResidentRegistrationNumber(@Param("id") Long id,
                                         @Param("residentRegistrationNumber") String residentRegistrationNumber);
}
