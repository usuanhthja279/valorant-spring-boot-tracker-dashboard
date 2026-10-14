package com.valorant.tracker.security;

import com.valorant.tracker.auth.AuthService;
import com.valorant.tracker.auth.UserIdentityRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final OidcUserService delegate = new OidcUserService();
  private final AuthService authService;
  private final UserIdentityRepository identityRepository;

  public CustomOidcUserService(AuthService authService, UserIdentityRepository identityRepository) {
    this.authService = authService;
    this.identityRepository = identityRepository;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
    OidcUser googleUser = delegate.loadUser(request);
    String provider = request.getClientRegistration().getRegistrationId().toUpperCase();
    String subject = googleUser.getSubject();
    String email = googleUser.getEmail();
    String name = googleUser.getFullName();
    String picture = googleUser.getPicture();

    try {
      var identity = identityRepository.findByProviderAndProviderSubject(provider, subject);
      Map<String, Object> claims = new LinkedHashMap<>(googleUser.getClaims());

      if (identity.isPresent()) {
        var appUser = authService.loginOAuth(provider, subject, name, email, picture);
        claims.put("appUserId", appUser.getId());
        claims.put("appUsername", appUser.getUsername());
        claims.put("appAccountPending", false);
      } else {
        claims.put("appUserId", null);
        claims.put("appUsername", null);
        claims.put("appAccountPending", true);
        claims.put("appProvider", provider);
        claims.put("appProviderSubject", subject);
        claims.put("appName", name);
        claims.put("appEmail", email);
        claims.put("appPicture", picture);
      }

      OidcUserInfo userInfo = new OidcUserInfo(claims);
      return new DefaultOidcUser(
          java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")),
          googleUser.getIdToken(),
          userInfo,
          "sub");
    } catch (IllegalArgumentException ex) {
      throw new OAuth2AuthenticationException(new OAuth2Error("account_error"), ex.getMessage(), ex);
    }
  }
}
