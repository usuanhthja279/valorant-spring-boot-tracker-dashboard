package com.valorant.tracker.controller.auth;

import com.valorant.tracker.auth.AppUser;
import com.valorant.tracker.auth.AuthService;
import com.valorant.tracker.auth.AppUserRepository;
import com.valorant.tracker.auth.EmailOtpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private static final int MAX_PICTURE_LENGTH = 60_000; // ~45 KB of image; a 192x192 JPEG is ~5-15 KB
  private static final Pattern HTTP_URL = Pattern.compile("(?i)^https?://.{1,990}$");
  private static final Pattern IMAGE_DATA_URL =
          Pattern.compile("^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$");

  private final AuthService authService;
  private final AppUserRepository userRepository;
  private final EmailOtpService otpService;
  private final PasswordEncoder passwordEncoder;

  public AuthController(AuthService authService, AppUserRepository userRepository,
                        EmailOtpService otpService, PasswordEncoder passwordEncoder) {
    this.authService = authService; this.userRepository = userRepository;
    this.otpService = otpService; this.passwordEncoder = passwordEncoder;
  }

  @PostMapping("/register/request-otp")
  public ResponseEntity<?> requestRegistrationOtp(@RequestBody RegistrationRequest request) {
    try {
      String username = authService.normalizeUsername(request.username());
      String email = authService.normalizeEmail(request.email());
      if (request.name() == null || request.name().isBlank()) throw new IllegalArgumentException("Name is required");
      if (request.password() == null || request.password().length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters");
      if (userRepository.findByUsernameIgnoreCase(username).isPresent()) throw new IllegalArgumentException("An account already exists with this username");
      if (userRepository.findByEmailIgnoreCase(email).isPresent()) throw new IllegalArgumentException("An account already exists with this email");
      otpService.sendRegistration(email);
      return ResponseEntity.ok(Map.of("sent", true, "destination", maskEmail(email), "expiresInSeconds", 600));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    } catch (IllegalStateException ex) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", ex.getMessage()));
    }
  }

  @PostMapping("/register")
  public ResponseEntity<?> register(@RequestBody RegistrationRequest request) {
    try {
      String email = authService.normalizeEmail(request.email());
      if (!otpService.verifyRegistration(email, request.otp())) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired verification code. Request a new code."));
      }
      AppUser user = authService.registerLocal(request.username(), request.name(), email, request.password());
      return ResponseEntity.status(HttpStatus.CREATED).body(userResponse(user));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
  }

  @GetMapping("/me")
  public ResponseEntity<?> me(Authentication authentication) {
    if (isUnauthenticated(authentication)) return ResponseEntity.ok(Map.of("authenticated", false));
    AppUser user = resolveUser(authentication);
    if (user != null) return ResponseEntity.ok(userResponse(user, true));
    Object principal = authentication.getPrincipal();
    if (principal instanceof OidcUser oidcUser) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("authenticated", true); result.put("pendingUsername", Boolean.TRUE.equals(oidcUser.getAttribute("appAccountPending")));
      result.put("id", oidcUser.getAttribute("appUserId")); result.put("username", oidcUser.getAttribute("appUsername"));
      result.put("name", first(oidcUser.getAttribute("appName"), oidcUser.getFullName())); result.put("email", oidcUser.getEmail());
      result.put("picture", first(oidcUser.getAttribute("appPicture"), oidcUser.getPicture())); return ResponseEntity.ok(result);
    }
    return ResponseEntity.ok(Map.of("authenticated", true, "identifier", authentication.getName()));
  }

  @GetMapping("/profile")
  public ResponseEntity<?> profile(Authentication authentication) {
    AppUser user = resolveUser(authentication);
    if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in required"));
    return ResponseEntity.ok(userResponse(user, true));
  }

  @PostMapping("/profile/otp")
  public ResponseEntity<?> sendProfileOtp(@RequestBody OtpRequest request, Authentication authentication) {
    AppUser user = resolveUser(authentication);
    if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in required"));
    String purpose = request.purpose() == null ? "PROFILE" : request.purpose().trim().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("PROFILE", "PASSWORD", "EMAIL").contains(purpose)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid verification purpose"));
    String destination = user.getEmail();
    if ("EMAIL".equals(purpose)) {
      try { destination = authService.normalizeEmail(request.newEmail()); }
      catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
      var existing = userRepository.findByEmailIgnoreCase(destination);
      if (existing.isPresent() && !existing.get().getId().equals(user.getId())) return ResponseEntity.badRequest().body(Map.of("error", "An account already exists with this email"));
    }
    try { otpService.send(user.getId(), destination, purpose); return ResponseEntity.ok(Map.of("sent", true, "destination", maskEmail(destination), "expiresInSeconds", 600)); }
    catch (IllegalStateException ex) { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", ex.getMessage())); }
  }

  @PutMapping("/profile")
  public ResponseEntity<?> updateProfile(@RequestBody ProfileUpdateRequest request, Authentication authentication) {
    AppUser user = resolveUser(authentication);
    if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in required"));
    if (request.name() == null || request.name().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
    if (!isValidPicture(request.pictureUrl())) return ResponseEntity.badRequest().body(Map.of("error", "Profile picture must be an http(s) URL or an uploaded PNG/JPG/WebP image"));
    if (!otpService.verify(user.getId(), "PROFILE", user.getEmail(), request.otp())) return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired verification code. Request a new code."));
    user.setName(request.name()); user.setProfileImageUrl(request.pictureUrl());
    return ResponseEntity.ok(userResponse(userRepository.save(user), true));
  }

  @PutMapping("/profile/email")
  public ResponseEntity<?> updateEmail(@RequestBody EmailUpdateRequest request, Authentication authentication) {
    AppUser user = resolveUser(authentication);
    if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in required"));
    String newEmail;
    try { newEmail = authService.normalizeEmail(request.email()); } catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
    if (!otpService.verify(user.getId(), "EMAIL", newEmail, request.otp())) return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired verification code sent to the new email"));
    var existing = userRepository.findByEmailIgnoreCase(newEmail);
    if (existing.isPresent() && !existing.get().getId().equals(user.getId())) return ResponseEntity.badRequest().body(Map.of("error", "An account already exists with this email"));
    user.setEmail(newEmail); return ResponseEntity.ok(userResponse(userRepository.save(user), true));
  }

  @PutMapping("/password")
  public ResponseEntity<?> updatePassword(@RequestBody PasswordUpdateRequest request, Authentication authentication) {
    AppUser user = resolveUser(authentication);
    if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in required"));
    if (!otpService.verify(user.getId(), "PASSWORD", user.getEmail(), request.otp())) return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired verification code. Request a new code."));
    if (request.password() == null || request.password().length() < 8) return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 8 characters"));
    user.setPasswordHash(passwordEncoder.encode(request.password())); userRepository.save(user);
    return ResponseEntity.ok(Map.of("updated", true));
  }

  @PostMapping("/oauth/complete")
  public ResponseEntity<?> completeOAuth(
          @RequestBody OAuthSignupRequest request,
          Authentication authentication,
          HttpServletRequest httpRequest) {
    if (isUnauthenticated(authentication) || !(authentication.getPrincipal() instanceof OidcUser oidcUser)
        || !Boolean.TRUE.equals(oidcUser.getAttribute("appAccountPending"))) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "No pending OAuth signup was found"));
    }
    try {
      String provider = (String) oidcUser.getAttribute("appProvider");
      String subject = (String) oidcUser.getAttribute("appProviderSubject");
      String appName = (String) oidcUser.getAttribute("appName");
      String appPicture = (String) oidcUser.getAttribute("appPicture");

      AppUser user = authService.completeOAuthSignup(
              provider,
              subject,
              request.username(),
              first(appName, oidcUser.getFullName()),
              oidcUser.getEmail(),
              first(appPicture, oidcUser.getPicture()));

      Map<String, Object> claims = new LinkedHashMap<>(oidcUser.getClaims());
      claims.put("appUserId", user.getId());
      claims.put("appUsername", user.getUsername());
      claims.put("appAccountPending", false);

      var updatedPrincipal = new DefaultOidcUser(
          oidcUser.getAuthorities(), oidcUser.getIdToken(),
          new OidcUserInfo(claims), "sub");
      var updatedAuthentication = new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
          updatedPrincipal, oidcUser.getAuthorities(), authentication instanceof
          org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken token
              ? token.getAuthorizedClientRegistrationId() : "google");

      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(updatedAuthentication);
      SecurityContextHolder.setContext(context);

      httpRequest.getSession(true).setAttribute(
              "SPRING_SECURITY_CONTEXT",
              context);
      return ResponseEntity.status(HttpStatus.CREATED).body(userResponse(user));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    new SecurityContextLogoutHandler().logout(request, response, authentication);
    return ResponseEntity.ok(Map.of("authenticated", false));
  }

  private static boolean isUnauthenticated(Authentication authentication) {
    return authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken;
  }

  private static String first(String a, String b) { return a != null && !a.isBlank() ? a : b; }

  private AppUser resolveUser(Authentication authentication) {
    if (isUnauthenticated(authentication)) return null;
    Object principal = authentication.getPrincipal();
    if (principal instanceof OidcUser oidc) {
      Object id = oidc.getAttribute("appUserId");
      if (id instanceof Number n) return userRepository.findById(n.longValue()).orElse(null);
      if (id != null) try { return userRepository.findById(Long.parseLong(id.toString())).orElse(null); } catch (NumberFormatException ignored) {}
      String email = oidc.getEmail(); return email == null ? null : userRepository.findByEmailIgnoreCase(email).orElse(null);
    }
    if (principal instanceof org.springframework.security.oauth2.core.user.OAuth2User oauth) {
      Object id = oauth.getAttribute("userId");
      if (id instanceof Number n) return userRepository.findById(n.longValue()).orElse(null);
      if (id != null) try { return userRepository.findById(Long.parseLong(id.toString())).orElse(null); } catch (NumberFormatException ignored) {}
      String email = oauth.getAttribute("email"); if (email != null) return userRepository.findByEmailIgnoreCase(email).orElse(null);
    }
    String identifier = authentication.getName();
    return identifier != null && identifier.contains("@") ? userRepository.findByEmailIgnoreCase(identifier).orElse(null) : userRepository.findByUsernameIgnoreCase(identifier).orElse(null);
  }

  private static String maskEmail(String email) {
    int at = email.indexOf('@'); if (at < 1) return "***";
    String local = email.substring(0, at); return local.substring(0, 1) + "***" + email.substring(at);
  }

  private static Map<String, Object> userResponse(AppUser user, boolean authenticated) {
    Map<String, Object> response = userResponse(user); response.put("authenticated", authenticated);
    response.put("pendingUsername", false); response.put("picture", user.getProfileImageUrl());
    response.put("hasPassword", user.getPasswordHash() != null); return response;
  }

  private static Map<String, Object> userResponse(AppUser user) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", user.getId());
    response.put("username", user.getUsername());
    response.put("name", user.getName());
    response.put("email", user.getEmail());
    return response;
  }

  private static boolean isValidPicture(String value) {
    if (value == null || value.isBlank()) return true;
    if (value.length() > MAX_PICTURE_LENGTH) return false;
    return HTTP_URL.matcher(value).matches() || IMAGE_DATA_URL.matcher(value).matches();
  }

  public record RegistrationRequest(String username, String name, String email, String password, String otp) {}
  public record OAuthSignupRequest(String username) {}
  public record OtpRequest(String purpose, String newEmail) {}
  public record ProfileUpdateRequest(String name, String pictureUrl, String otp) {}
  public record EmailUpdateRequest(String email, String otp) {}
  public record PasswordUpdateRequest(String password, String otp) {}
}
