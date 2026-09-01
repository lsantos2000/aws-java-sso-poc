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

Until that call answers, `status` is `null` and **there is no sign-in route to offer** — the button
renders disabled. Do not reintroduce a default branch here: an `else` that assumes Cognito sends
mock-profile users to an endpoint that does not exist under that profile, which is precisely the
bug the disabled state prevents.

### Endpoints

| Route | Purpose |
| --- | --- |
| `GET /api/auth/status` | Public. `{ authenticated, mode }`. Drives all frontend rendering. |
| `GET /api/me` | Authenticated. Name, email, subject, and a **whitelisted** claim subset. |
| `POST /api/auth/logout` | `LogoutController`. What the frontend actually calls (fetch-friendly, no redirect). |
| `/logout` | Spring's own logout, redirects to `http://localhost:5173`. Kept for the browser flow. |
| `/oauth2/authorization/cognito`, `/login/oauth2/code/cognito` | Spring Security's authorization-code endpoints (non-mock only). |
| `GET /api/logs/stream` | SSE feed of backend log lines for the console. Mock profile only. |
| `GET /api/logs/recent` | Buffered history for the same, for clients without EventSource. |
| `GET /actuator/health` | Only exposed actuator endpoint. |

Cognito flow: frontend navigates to `/oauth2/authorization/cognito` → hosted UI → callback at `http://localhost:8080/login/oauth2/code/cognito` → Spring exchanges the code and creates the server-side session → frontend re-reads `/api/auth/status` and `/api/me` with `credentials: 'include'`. Keep the authorization-code flow and server-managed session model unless a task explicitly asks to redesign authentication.

### The console is the page's centre of gravity

`frontend/src/main.tsx` renders a two-tab console. The **SESSION** tab is written by the frontend
itself: `api()` takes a `Recorder` and logs every request, status, and response summary, so the
handshake is visible as it happens. The **BACKEND** tab is an `EventSource` on `/api/logs/stream`.

Auth lives here, not in the hero — a single button that switches between `signInLabel` and
"Sign out". There was briefly one button in each place; two controls firing the same action is
both a design smell and an ambiguous test query. Keep it to one.

`summarize()` deliberately drops the `claims` key when rendering a response, so the console never
prints the claim set even though `/api/me` now whitelists it — two independent guards.

`signOut()` checks the response before clearing state. Clearing unconditionally would render a
failed logout as a clean sign-out while the server session is still live, which is the most
misleading direction for that failure to point.

### Streaming backend logs

`LogStreamService` attaches a Logback appender to the root logger and fans lines out to SSE
subscribers; `LogStreamController` exposes `/api/logs/stream` and `/api/logs/recent`.

Three things hold this together and should not be removed casually:

- Both beans are `@ConditionalOnProperty("app.log-stream.enabled")`, which only
  `application-mock.yml` sets to `true`. **This endpoint publishes server logs to any caller that
  can reach it**, and it is `permitAll` so the console can show the handshake before a session
  exists. That combination is only acceptable because it is confined to the local simulator.
- A `ThreadLocal` re-entrancy flag guards `append()`. Writing to a dead SSE connection makes Tomcat
  log, which would re-enter the appender and fail again; the flag breaks that cycle.
- Only four fields leave the server (`LogEvent`) — never the raw `ILoggingEvent`, so throwables and
  thread detail stay server-side. `LogStreamTest` pins that.

### Errors are always JSON

`ApiErrorController` implements `ErrorController`, which makes Spring Boot back off its Whitelabel
page, and both security configs install JSON `authenticationEntryPoint`/`accessDeniedHandler` pairs
via `JsonErrorWriter`. Every failure therefore leaves the app in one shape, whatever the caller's
`Accept` header:

```json
{"status": 401, "error": "Unauthorized", "message": "...", "path": "/api/me"}
```

This matters because Spring's default error rendering is content-negotiated: curl received JSON
while a browser received the HTML Whitelabel page for the same request. Test error behavior with a
browser-style `Accept: text/html` header or the regression will not reproduce — `ErrorResponseTest`
does exactly this.

Two details to preserve. Under the non-mock profile only `/api/**` gets the JSON 401; everything
else keeps `oauth2Login`'s redirect entry point, because a browser hitting a protected page should
still be sent to the hosted UI. And under `mock`, requests to `/oauth2/**` return a message naming
the active profile — that path has no filter behind it in this profile, so a bare 403 is unhelpable.

Because `anyRequest().authenticated()` runs before dispatch, an anonymous request to an unknown path
is a 401, not a 404. Only authenticated callers can reach a real 404.

### Route proxying is duplicated in two places

`/api`, `/oauth2`, `/login`, `/logout` are proxied to port 8080 by **both** `frontend/vite.config.ts` (dev server) and `frontend/nginx.conf` (the Docker image). Adding an OAuth-related path prefix means editing both, or it will work in `npm run dev` and 404 under `docker compose`.

### Layout is built to fit one viewport

The page is meant to land within a single screen: two-column hero (headline left, supporting copy
and the health link right), the console in the middle, and the PROVIDER/JAVA LAYER/SESSION strip
below it as a summary of what the console just showed. `scripts/screenshots.mjs` prints the
rendered page height on each run — if a change pushes it past the viewport, that number moves.

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

Screenshots, from the repo root with both servers on the `mock` profile:

```bash
npm install --no-save playwright && npx playwright install chromium
node scripts/screenshots.mjs      # writes docs/screenshots/, prints page height
```

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
- Do not expose access tokens or client secrets through API responses or frontend state.
- `/api/me` returns only the claims listed in `AuthController.EXPOSED_CLAIMS`. Add to that list deliberately, never by switching back to `user.getAttributes()` — the whole point is that a claim the provider starts releasing later cannot reach the browser without someone editing that list.
- Keep the mock simulator clearly separated from the real Cognito profile.
- Avoid unrelated changes to the legacy sibling projects in the parent workspace.

## Validation Expectations

- Frontend changes: `npm test` and `npm run build` from `frontend/`.
- Backend changes: `mvn test` from `backend/`. Keep auth status, authenticated-identity, session-cookie, and logout behavior covered — that's what `AuthControllerUnitTest` (plain unit), `AuthControllerTest` (`MockMvc` + `oauth2Login()` post-processor), and `MockSsoE2ETest` (real HTTP, `RANDOM_PORT`, cookie round-trip) exist for. `ErrorResponseTest` pins the JSON error contract and `LogStreamTest` the console feed.
- Every `@SpringBootTest` must declare `@ActiveProfiles("mock")` explicitly. Inheriting it from the root `application.yml` default makes the test break for an unrelated reason the moment that default changes.
- Container changes: `docker compose up --build` from the root and verify the mock flow at `http://localhost:5173`.
- GitHub Actions (`.github/workflows/ci.yml`) runs `mvn --batch-mode test` and `npm ci && npm test && npm run build` on pushes and PRs to `main`.

Screenshots in `docs/screenshots/` are generated from the local mock flow with Playwright. If you regenerate them, capture both signed-out and signed-in states and include no cookies, tokens, or real user data.

This is a POC. Before production use, review session storage, cookie security, CSRF strategy (`/api/**` is currently CSRF-exempt), logout behavior, secrets management, redirect validation (the frontend origin is hardcoded in both security configs), observability, and deployment topology.
