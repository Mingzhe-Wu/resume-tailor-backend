package com.mingzhe.resumetailor.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("""
        SELECT id
        FROM users
        WHERE id = #{id}
        """)
    User findById(Long id);

}
