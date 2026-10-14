package com.valorant.tracker.security;

import com.valorant.tracker.auth.AppUserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {
  private final AppUserRepository userRepository;

  public CustomUserDetailsService(AppUserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
    var user = identifier != null && identifier.contains("@")
        ? userRepository.findByEmailIgnoreCase(identifier)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"))
        : userRepository.findByUsernameIgnoreCase(identifier)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));

    if (user.getPasswordHash() == null) {
      throw new UsernameNotFoundException("This account does not have a local password");
    }

    return User.withUsername(user.getUsername())
        .password(user.getPasswordHash())
        .roles("USER")
        .build();
  }
}
