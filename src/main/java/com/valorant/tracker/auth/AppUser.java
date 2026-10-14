package com.valorant.tracker.auth;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "app_users")
public class AppUser {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 30)
  private String username;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(name = "password_hash", length = 100)
  private String passwordHash;

  @Column(name = "profile_image_url", columnDefinition = "TEXT")
  private String profileImageUrl;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "last_login_at")
  private OffsetDateTime lastLoginAt;

  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<UserIdentity> identities = new ArrayList<>();

  protected AppUser() {}

  public AppUser(String username, String name, String email, String passwordHash, String profileImageUrl) {
    this.username = username;
    this.name = name;
    this.email = email;
    this.passwordHash = passwordHash;
    this.profileImageUrl = profileImageUrl;
    this.createdAt = OffsetDateTime.now();
  }

  public void updateProfile(String name, String profileImageUrl) {
    if (name != null && !name.isBlank()) this.name = name.trim();
    if (profileImageUrl != null && !profileImageUrl.isBlank()) this.profileImageUrl = profileImageUrl;
  }

  public void markLogin() { this.lastLoginAt = OffsetDateTime.now(); }

  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public void setName(String name) { if (name != null && !name.isBlank()) this.name = name.trim(); }
  public void setEmail(String email) { if (email != null && !email.isBlank()) this.email = email.trim().toLowerCase(java.util.Locale.ROOT); }
  public void setProfileImageUrl(String profileImageUrl) { this.profileImageUrl = (profileImageUrl == null || profileImageUrl.isBlank()) ? null : profileImageUrl.trim(); }
  public void addIdentity(UserIdentity identity) {
    identities.add(identity);
    identity.setUser(this);
  }

  public Long getId() { return id; }
  public String getUsername() { return username; }
  public String getName() { return name; }
  public String getEmail() { return email; }
  public String getPasswordHash() { return passwordHash; }
  public String getProfileImageUrl() { return profileImageUrl; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
  public List<UserIdentity> getIdentities() { return identities; }
}
