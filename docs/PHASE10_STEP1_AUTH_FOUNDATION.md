# Phase 10 — Step 1: Authentication Foundation

Implemented the authentication backend foundation for stremng.

## Supported sign-in methods

- Google OAuth2
- Discord OAuth2
- Twitch OAuth2
- Microsoft OAuth2/OIDC
- Email + password

## Implemented

- Spring Security
- Spring Security OAuth2 Client
- BCrypt password hashing
- `AppUser` entity
- `UserIdentity` entity for provider identities
- User and identity repositories
- Local registration endpoint: `POST /api/auth/register`
- Authentication state endpoint: `GET /api/auth/me`
- Logout endpoint: `POST /api/auth/logout`
- OAuth2 user provisioning for first-time provider users
- Separate provider identity records so account linking can be added later
- Environment-variable based OAuth client credentials
- Twitch-specific profile loading because Twitch returns the user profile inside `data[]`

## Not implemented yet

- Custom login/signup UI
- Historical API authorization
- Guest blurred cards
- Account linking UI
- Forgot/reset password
- Email verification

Those will be handled in later Phase 10 steps.

## OAuth environment variables

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `DISCORD_CLIENT_ID`
- `DISCORD_CLIENT_SECRET`
- `TWITCH_CLIENT_ID`
- `TWITCH_CLIENT_SECRET`
- `MICROSOFT_CLIENT_ID`
- `MICROSOFT_CLIENT_SECRET`
