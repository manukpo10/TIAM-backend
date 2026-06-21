package com.tiam.user.mapper;

import com.tiam.user.domain.Role;
import com.tiam.user.domain.User;
import com.tiam.user.dto.UserResponse;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-21T04:32:04-0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.7 (Oracle Corporation)"
)
@Component
public class UserMapperImpl implements UserMapper {

    @Override
    public UserResponse toResponse(User user) {
        if ( user == null ) {
            return null;
        }

        Long id = null;
        String email = null;
        String fullName = null;
        String specialty = null;
        Role role = null;

        id = user.getId();
        email = user.getEmail();
        fullName = user.getFullName();
        specialty = user.getSpecialty();
        role = user.getRole();

        UserResponse userResponse = new UserResponse( id, email, fullName, specialty, role );

        return userResponse;
    }
}
