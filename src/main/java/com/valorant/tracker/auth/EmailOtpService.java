package com.valorant.tracker.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailOtpService {
  private final JavaMailSender mailSender;
  private final String from;
  private final SecureRandom random = new SecureRandom();
  private final ConcurrentHashMap<String, OtpEntry> codes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> attempts = new ConcurrentHashMap<>();

  public EmailOtpService(JavaMailSender mailSender,
      @Value("${app.mail.from:${spring.mail.username:}}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  public void sendRegistration(String email) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("Email is required");
    }
    String cleanEmail = email.trim();
    sendKey("registration:" + cleanEmail.toLowerCase(java.util.Locale.ROOT), cleanEmail, "REGISTRATION");
  }

  public boolean verifyRegistration(String email, String code) {
    String normalizedEmail = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    return verifyKey("registration:" + normalizedEmail, email, code);
  }

  private void sendKey(String key, String email, String purpose) {
    if (from == null || from.isBlank()) {
      throw new IllegalStateException("Email delivery is not configured. Set SPRING_MAIL_USERNAME and APP_MAIL_FROM.");
    }
    String code = String.format("%06d", random.nextInt(1_000_000));
    codes.put(key, new OtpEntry(code, Instant.now().plusSeconds(600), email));
    attempts.put(key, new java.util.concurrent.atomic.AtomicInteger());

    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject("Your Stremng verification code");
    message.setText("Your Stremng " + purpose.toLowerCase(java.util.Locale.ROOT)
        + " verification code is " + code
        + ". It expires in 10 minutes. If you did not request this, ignore this email.");
    try {
      mailSender.send(message);
    } catch (MailException ex) {
      codes.remove(key);
      attempts.remove(key);
      throw new IllegalStateException("Unable to send verification email. Check SMTP settings and try again.", ex);
    }
  }

  private boolean verifyKey(String key, String email, String code) {
    if (code == null || code.isBlank() || email == null || email.isBlank()) return false;
    OtpEntry entry = codes.get(key);
    if (entry == null) return false;

    boolean expired = Instant.now().isAfter(entry.expiresAt());
    boolean emailMismatch = !entry.email().equalsIgnoreCase(email.trim());
    boolean codeMismatch = !entry.code().equals(code.trim());
    if (expired || emailMismatch || codeMismatch) {
      int count = attempts.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.AtomicInteger())
          .incrementAndGet();
      if (expired || count >= 5) {
        codes.remove(key, entry);
        attempts.remove(key);
      }
      return false;
    }
    codes.remove(key, entry);
    attempts.remove(key);
    return true;
  }

  public void send(Long userId, String email, String purpose) {
    if (from == null || from.isBlank()) throw new IllegalStateException("Email delivery is not configured. Set SPRING_MAIL_USERNAME and APP_MAIL_FROM.");
    String code = String.format("%06d", random.nextInt(1_000_000));
    String key = key(userId, purpose);
    codes.put(key, new OtpEntry(code, Instant.now().plusSeconds(600), email)); attempts.put(key, new java.util.concurrent.atomic.AtomicInteger());
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from); message.setTo(email);
    message.setSubject("Your Stremng verification code");
    message.setText("Your Stremng verification code is " + code + ". It expires in 10 minutes. If you did not request this, ignore this email.");
    try { mailSender.send(message); }
    catch (MailException ex) { codes.remove(key); throw new IllegalStateException("Unable to send verification email. Check SMTP settings and try again.", ex); }
  }

  public boolean verify(Long userId, String purpose, String email, String code) {
    if (code == null || code.isBlank()) return false;
    String key = key(userId, purpose); OtpEntry entry = codes.get(key);
    if (entry == null || Instant.now().isAfter(entry.expiresAt()) || !entry.email().equalsIgnoreCase(email) || !entry.code().equals(code.trim())) {
      if (entry != null) {
        int count = attempts.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        if (Instant.now().isAfter(entry.expiresAt()) || count >= 5) { codes.remove(key); attempts.remove(key); }
      }
      return false;
    }
    codes.remove(key); attempts.remove(key); return true;
  }
  private static String key(Long id, String purpose) { return id + ":" + purpose; }
  private record OtpEntry(String code, Instant expiresAt, String email) {}
}
