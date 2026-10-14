package com.valorant.tracker.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
  Optional<AppUser> findByEmailIgnoreCase(String email);
  Optional<AppUser> findByUsernameIgnoreCase(String username);
  boolean existsByUsernameIgnoreCase(String username);
}
