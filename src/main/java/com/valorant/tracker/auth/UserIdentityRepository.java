package com.valorant.tracker.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {
  Optional<UserIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);
}
