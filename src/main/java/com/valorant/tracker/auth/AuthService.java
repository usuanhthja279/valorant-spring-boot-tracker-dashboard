package com.valorant.tracker.auth;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,30}$");
  private final AppUserRepository userRepository;
  private final UserIdentityRepository identityRepository;
  private final PasswordEncoder passwordEncoder;

  public AuthService(AppUserRepository userRepository, UserIdentityRepository identityRepository,
                     PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.identityRepository = identityRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public AppUser registerLocal(String username, String name, String email, String password) {
    String normalizedUsername = normalizeUsername(username);
    String normalizedEmail = normalizeEmail(email);
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");
    if (password == null || password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters");
    if (userRepository.findByUsernameIgnoreCase(normalizedUsername).isPresent())
      throw new IllegalArgumentException("An account already exists with this username");
    if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent())
      throw new IllegalArgumentException("An account already exists with this email");
    AppUser user = new AppUser(normalizedUsername, name.trim(), normalizedEmail, passwordEncoder.encode(password), null);
    user.addIdentity(new UserIdentity("LOCAL", normalizedEmail));
    return userRepository.save(user);
  }

  @Transactional
  public AppUser loginOAuth(String provider, String subject, String name, String email, String picture) {
    if (subject == null || subject.isBlank()) throw new IllegalArgumentException("OAuth provider did not return a subject");
    String normalizedProvider = provider.toUpperCase(Locale.ROOT);
    var existingIdentity = identityRepository.findByProviderAndProviderSubject(normalizedProvider, subject);
    if (existingIdentity.isEmpty()) {
      String normalizedEmail = normalizeEmail(email);
      AppUser sameEmail = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
      if (sameEmail != null) {
        if (sameEmail.getPasswordHash() != null)
          throw new IllegalArgumentException("An account already exists with this email. Sign in with email/password first and link this provider later.");
        throw new IllegalArgumentException("An account exists for this email but this provider is not linked. Contact support to link it.");
      }
      throw new IllegalArgumentException("Username is required for a new account");
    }
    AppUser user = existingIdentity.get().getUser();
    user.updateProfile(name, picture);
    user.markLogin();
    return userRepository.save(user);
  }

  @Transactional
  public AppUser completeOAuthSignup(String provider, String subject, String username,
                                    String name, String email, String picture) {
    String normalizedProvider = provider.toUpperCase(Locale.ROOT);
    String normalizedSubject = require(subject, "OAuth provider did not return a subject");
    String normalizedUsername = normalizeUsername(username);
    String normalizedEmail = normalizeEmail(email);
    if (identityRepository.findByProviderAndProviderSubject(normalizedProvider, normalizedSubject).isPresent())
      throw new IllegalArgumentException("This provider account is already linked. Please sign in again.");
    if (userRepository.findByUsernameIgnoreCase(normalizedUsername).isPresent())
      throw new IllegalArgumentException("That username is already taken");
    if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent())
      throw new IllegalArgumentException("An account already exists with this email");
    if (name == null || name.isBlank()) name = normalizedUsername;
    AppUser user = new AppUser(normalizedUsername, name.trim(), normalizedEmail, null, picture);
    user.markLogin();
    user.addIdentity(new UserIdentity(normalizedProvider, normalizedSubject));
    return userRepository.save(user);
  }

  public String normalizeUsername(String username) {
    if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required");
    String normalized = username.trim().toLowerCase(Locale.ROOT);
    if (!USERNAME_PATTERN.matcher(normalized).matches())
      throw new IllegalArgumentException("Username must be 3-30 characters and contain only letters, numbers, _ or -");
    return normalized;
  }

  public String normalizeEmail(String email) {
    if (email == null || email.isBlank() || !email.contains("@"))
      throw new IllegalArgumentException("A valid email is required");
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private static String require(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    return value;
  }
}
