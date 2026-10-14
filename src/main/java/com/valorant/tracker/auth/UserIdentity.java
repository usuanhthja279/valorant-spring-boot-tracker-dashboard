package com.valorant.tracker.auth;

import jakarta.persistence.*;

@Entity
@Table(name = "user_identities",
    uniqueConstraints = @UniqueConstraint(name = "uk_identity_provider_subject", columnNames = {"provider", "provider_subject"}))
public class UserIdentity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @Column(nullable = false, length = 30)
  private String provider;

  @Column(name = "provider_subject", nullable = false, length = 320)
  private String providerSubject;

  protected UserIdentity() {}

  public UserIdentity(String provider, String providerSubject) {
    this.provider = provider;
    this.providerSubject = providerSubject;
  }

  void setUser(AppUser user) { this.user = user; }
  public AppUser getUser() { return user; }
  public String getProvider() { return provider; }
  public String getProviderSubject() { return providerSubject; }
}
