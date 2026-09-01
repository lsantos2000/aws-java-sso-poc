# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`aws-java-sso-poc` is a proof of concept for AWS single sign-on:

- AWS Cognito as the OIDC identity provider
- Spring Boot 3.5 / Spring Security OAuth2 Client as the Java backend (`backend/`, port `8080`)
- React 19 + Vite + TypeScript as the frontend (`frontend/`, port `5173`)

It is intentionally small and local-development focused. Do not add AWS credentials, Cognito client secrets, or user-pool identifiers to source control.

## Architecture

### Profile-gated security, not runtime branching

The single most important structural fact: there are **two mutually exclusive security stacks**, selected by Spring profile.

- `SecurityConfig` is `@Profile("!mock")` — real Cognito. Registers `oauth2Login()`, so Spring Boot needs `AWS_COGNITO_*` env vars to build a `ClientRegistrationRepository`, and the app fails to start without them.
- `MockSecurityConfig` is `@Profile("mock")` — no OAuth2 login filter at all, so no Cognito lookup happens. `MockAuthController` (also `@Profile("mock")`) fabricates a `DefaultOAuth2User` and writes it into the session via `HttpSessionSecurityContextRepository`.

`application.yml` sets `spring.profiles.active: mock` as the default, so a bare `mvn spring-boot:run` and every `@SpringBootTest` without `@ActiveProfiles` run the mock stack. Do not merge these configs into one conditional filter chain — the separation is what keeps Cognito uninitialized when credentials are absent.

### `app.sso-mode` is the contract between backend and frontend

`AuthController` echoes `app.sso-mode` (`mock` in `application-mock.yml`, `cognito` in `application-local.yml`) from `GET /api/auth/status`. The frontend's single sign-in button reads that value and branches: `mock` → `POST /api/auth/mock-login` then reload; `cognito` → full-page navigate to `/oauth2/authorization/cognito`. If you add a mode, update both sides plus the `AuthStatus` union type in `frontend/src/main.tsx`.

### Endpoints

| Route | Purpose |
| --- | --- |
| `GET /api/auth/status` | Public. `{ authenticated, mode }`. Drives all frontend rendering. |
| `GET /api/me` | Authenticated. Name/email/subject/claims from the `OAuth2User`. |
| `POST /api/auth/logout` | `LogoutController`. What the frontend actually calls (fetch-friendly, no redirect). |
| `/logout` | Spring's own logout, redirects to `http://localhost:5173`. Kept for the browser flow. |
| `/oauth2/authorization/cognito`, `/login/oauth2/code/cognito` | Spring Security's authorization-code endpoints (non-mock only). |
| `GET /actuator/health` | Only exposed actuator endpoint. |

Cognito flow: frontend navigates to `/oauth2/authorization/cognito` → hosted UI → callback at `http://localhost:8080/login/oauth2/code/cognito` → Spring exchanges the code and creates the server-side session → frontend re-reads `/api/auth/status` and `/api/me` with `credentials: 'include'`. Keep the authorization-code flow and server-managed session model unless a task explicitly asks to redesign authentication.

### Route proxying is duplicated in two places

`/api`, `/oauth2`, `/login`, `/logout` are proxied to port 8080 by **both** `frontend/vite.config.ts` (dev server) and `frontend/nginx.conf` (the Docker image). Adding an OAuth-related path prefix means editing both, or it will work in `npm run dev` and 404 under `docker compose`.

### Frontend shape

The entire app is one file: `frontend/src/main.tsx` exports `App` (for tests) and mounts it. Styling lives in `frontend/src/style.css` — a warm, editorial visual style; keep changes responsive on desktop and mobile.

## Commands

Frontend, from `frontend/`:

```powershell
npm install
npm run dev            # Vite dev server on 5173, proxies to 8080
npm test               # vitest run (jsdom)
npm run build          # tsc -b && vite build — this is also the typecheck
npx vitest run src/main.test.tsx                       # single file
npm test -- -t "clears the authenticated identity"     # single test by name
```

Backend, from `backend/`:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=mock"    # no AWS needed
mvn spring-boot:run "-Dspring-boot.run.profiles=local"   # requires the env vars below
mvn test
mvn test "-Dtest=AuthControllerUnitTest"                                  # single class
mvn test "-Dtest=MockSsoE2ETest#mockLoginCreatesSessionThatCanAccessCurrentUser"
```

Quote every `-D...` argument. PowerShell terminates a `-`-prefixed token at the first `.`, so an
unquoted `-Dspring-boot.run.profiles=mock` arrives at Maven as `-Dspring-boot` plus a stray
`.run.profiles=mock` that it reads as a lifecycle phase. Quoting is a no-op in Bash.

Whole stack, from the repo root: `docker compose up --build` (backend forced to the `mock` profile, frontend served by nginx on 5173).

Java 17+ required; Java 21 is installed here and used by CI and the Dockerfiles. Maven must be installed separately if `mvn` is unavailable. There is no linter configured — `npm run build` and `mvn test` are the gates.

## Configuration

Backend env vars, read only by `application-local.yml`:

- `AWS_COGNITO_CLIENT_ID`
- `AWS_COGNITO_CLIENT_SECRET`
- `AWS_COGNITO_ISSUER_URI`

The Cognito app client must allow callback `http://localhost:8080/login/oauth2/code/cognito`, sign-out `http://localhost:5173`, and scopes `openid`, `profile`, `email`.

For a public Cognito client, use PKCE and update the Spring registration deliberately rather than silently falling back to an insecure flow.

## Coding Guidance

- Keep backend endpoints under `/api` and preserve credentialed CORS for `http://localhost:5173` (`allowCredentials(true)` with an explicit origin — a wildcard origin will break the session cookie).
- Keep authentication behavior in the `*SecurityConfig` classes; keep response shaping in controllers.
- Do not expose access tokens or client secrets through API responses or frontend state. `/api/me` returns raw `claims` — do not widen it to the token set.
- Keep the mock simulator clearly separated from the real Cognito profile.
- Avoid unrelated changes to the legacy sibling projects in the parent workspace.

## Validation Expectations

- Frontend changes: `npm test` and `npm run build` from `frontend/`.
- Backend changes: `mvn test` from `backend/`. Keep auth status, authenticated-identity, session-cookie, and logout behavior covered — that's what `AuthControllerUnitTest` (plain unit), `AuthControllerTest` (`MockMvc` + `oauth2Login()` post-processor), and `MockSsoE2ETest` (real HTTP, `RANDOM_PORT`, cookie round-trip) exist for.
- Container changes: `docker compose up --build` from the root and verify the mock flow at `http://localhost:5173`.
- GitHub Actions (`.github/workflows/ci.yml`) runs `mvn --batch-mode test` and `npm ci && npm test && npm run build` on pushes and PRs to `main`.

Screenshots in `docs/screenshots/` are generated from the local mock flow with Playwright. If you regenerate them, capture both signed-out and signed-in states and include no cookies, tokens, or real user data.

This is a POC. Before production use, review session storage, cookie security, CSRF strategy (`/api/**` is currently CSRF-exempt), logout behavior, secrets management, redirect validation (the frontend origin is hardcoded in both security configs), observability, and deployment topology.
