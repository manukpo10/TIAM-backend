package com.tiam.user.repository;

import com.tiam.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailAndActivoTrue(String email);

    boolean existsByEmail(String email);
}
