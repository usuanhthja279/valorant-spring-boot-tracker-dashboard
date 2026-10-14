package com.valorant.tracker.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.auth.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
  private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
  private final AuthService authService;
  private final ObjectMapper objectMapper;
  private final RestClient restClient = RestClient.create();

  public CustomOAuth2UserService(AuthService authService, ObjectMapper objectMapper) {
    this.authService = authService;
    this.objectMapper = objectMapper;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
    String provider = request.getClientRegistration().getRegistrationId().toUpperCase(Locale.ROOT);
    try {
      OAuthProfile profile = "TWITCH".equals(provider)
          ? loadTwitchProfile(request)
          : loadStandardProfile(request);

      if (profile.email() == null || profile.email().isBlank()) {
        throw new OAuth2AuthenticationException(new OAuth2Error("email_missing"),
            "The provider did not return an email address");
      }

      var user = authService.loginOAuth(provider, profile.subject(), profile.name(),
          profile.email(), profile.picture());

      return new DefaultOAuth2User(
          List.of(new SimpleGrantedAuthority("ROLE_USER")),
          Map.of("email", user.getEmail(), "username", user.getUsername(), "name", user.getName(), "userId", user.getId()),
          "email");
    } catch (IllegalArgumentException ex) {
      throw new OAuth2AuthenticationException(new OAuth2Error("account_error"), ex.getMessage(), ex);
    } catch (OAuth2AuthenticationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new OAuth2AuthenticationException(new OAuth2Error("oauth_user_load_failed"),
          "Unable to load the account from the OAuth provider", ex);
    }
  }

  private OAuthProfile loadStandardProfile(OAuth2UserRequest request) {
    Map<String, Object> a = delegate.loadUser(request).getAttributes();
    String subject = text(a, "sub");
    if (subject == null) subject = text(a, "id");
    String email = text(a, "email");
    if (email == null) email = text(a, "preferred_username");
    String name = text(a, "name");
    if (name == null) name = text(a, "display_name");
    if (name == null) name = text(a, "username");
    String picture = text(a, "picture");
    if (picture == null) picture = text(a, "profile_image_url");
    return new OAuthProfile(subject, email, name, picture);
  }

  private OAuthProfile loadTwitchProfile(OAuth2UserRequest request) throws Exception {
    String body = restClient.get()
        .uri("https://api.twitch.tv/helix/users")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.getAccessToken().getTokenValue())
        .header("Client-Id", request.getClientRegistration().getClientId())
        .retrieve()
        .body(String.class);

    JsonNode root = objectMapper.readTree(body);
    JsonNode user = root.path("data").path(0);
    if (user.isMissingNode() || user.isNull()) {
      throw new IllegalArgumentException("Twitch did not return a user profile");
    }
    return new OAuthProfile(
        user.path("id").asText(null),
        user.path("email").asText(null),
        firstNonBlank(user.path("display_name").asText(null), user.path("login").asText(null)),
        user.path("profile_image_url").asText(null));
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static String text(Map<String, Object> attributes, String key) {
    Object value = attributes.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private record OAuthProfile(String subject, String email, String name, String picture) {}
}
