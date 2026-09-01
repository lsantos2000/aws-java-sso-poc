# Code review

An assessment of the codebase as it currently stands: what holds up, what is missing, and what I
would build differently.

Baseline: `mvn test` passes (19 tests), `npm test` passes (7 tests), `npm run build` succeeds, and
`docker compose up --build` brings up a healthy stack with the mock flow working end to end.

Findings are labelled **verified** where a command demonstrates them and **by inspection** where
they follow from reading the code.

---

## 1. Security

### 1.1 Mock sign-in is unauthenticated, and the mock profile is the default — verified

`application.yml` sets `spring.profiles.active: mock`, so every one of these activates the mock
stack:

```
java -jar app.jar
docker run <backend-image>
mvn spring-boot:run
```

In that stack `POST /api/auth/mock-login` is `permitAll` and mints a fully authenticated session for
any caller:

```
$ curl -i -c c.txt -X POST http://localhost:8080/api/auth/mock-login
HTTP/1.1 200

$ curl -b c.txt http://localhost:8080/api/me
{"name":"Demo User","email":"demo@example.com","subject":"mock-user-001", ...}
```

`MockAuthController` logs a `WARN` at startup naming the risk, and `docker-compose.yml` sets the
profile explicitly. Neither is a control — the safe behaviour depends on whoever runs it, and the
failure mode is silent: a deployment that omits the variable does not break, it comes up with
authentication disabled.

**This is the one thing to fix before this repository runs anywhere other people can reach.**
Options are in `PLAN-MULTI-APP.md` section 8.0: reject non-loopback callers at `MockAuthController`,
require an explicit `app.allow-mock-login`, or make the secure profile the default.

### 1.2 `/api/logs/**` is unauthenticated — verified, and intentional

The console's BACKEND tab reads `GET /api/logs/stream`, which publishes server log lines to any
caller that can reach it. It is `permitAll` deliberately: the console has to show the sign-in
handshake, which happens before a session exists.

Two things confine it. Both beans carry `@ConditionalOnProperty("app.log-stream.enabled")`, which
only `application-mock.yml` sets to `true`; and only four fields leave the server — time, level,
abbreviated logger name, formatted message — so throwables and thread detail stay server-side, which
`LogStreamTest` pins.

Acceptable for a local simulator and nothing else. Do not enable it under `local` without putting
authentication in front of it.

### 1.3 `/api/**` is CSRF-exempt while sessions live in a cookie — by inspection

Both configs carry `csrf.ignoringRequestMatchers("/api/**")` and authenticate via a session cookie.
That is the shape CSRF exploits: any origin can cause a browser to send an authenticated
`POST /api/auth/logout`, and under the mock profile `POST /api/auth/mock-login` too.

The CORS allowlist does not mitigate it. CORS governs whether JavaScript may *read* a response; it
does not stop the request being sent or the side effect happening.

Use `CookieCsrfTokenRepository.withHttpOnlyFalse()` and have the frontend echo `X-XSRF-TOKEN`. For a
repository that exists to teach authentication, this being off and unexplained at the call site is
the part that bothers me most — either implement it, or comment why it is disabled.

### 1.4 No session cookie hardening — verified

There is no `server.servlet.session.cookie` configuration, so `SameSite` and `Secure` are unset. On
`localhost` this is harmless — 5173 and 8080 differ only by port, so the cookie is same-site — but
nothing carries that safety anywhere else. Set `same-site: lax` now; `secure: true` matters only
behind TLS.

---

## 2. Test coverage

All 19 backend tests exercise the mock stack.

### 2.1 The Cognito path has no automated coverage — verified

`SecurityConfig`, the class that matters in production, is never instantiated by any test. The suite
would stay green if it were deleted. The JSON authentication entry point it installs is likewise
covered only on the mock side.

**This is the largest gap in the repository.** A test with a stubbed `ClientRegistrationRepository`
would cover the filter chain, the CORS rules, and the entry-point behaviour without needing a real
user pool.

### 2.2 Other uncovered behaviour

- The CORS configuration. No test asserts that a disallowed origin is rejected, or that credentials
  are permitted for the configured origin.
- The `cognito` branch of the frontend's sign-in button.
- `AuthController.currentUser`'s fallback name and email through the HTTP layer, rather than only as
  a unit test.

---

## 3. Configuration

### 3.1 The frontend origin is hardcoded in five places — verified

`http://localhost:5173` appears in `SecurityConfig` (CORS, `defaultSuccessUrl`, logout redirect) and
`MockSecurityConfig` (CORS, `logoutSuccessUrl`). Changing where the frontend runs means editing
Java. It belongs in a property.

---

## 4. Repository

**4.1 No LICENSE.** The repository is public and the UI footer carries a copyright line, but there is
no license file, so nobody can legally reuse it.

**4.2 No linter or formatter** — no ESLint, Prettier, Checkstyle, or Spotless. `npm run build` (via
`tsc -b`) and `mvn test` are the only gates.

**4.3 CI does not exercise the container path.** `.github/workflows/ci.yml` runs both test suites but
never builds the images or runs compose, so a broken Dockerfile or healthcheck reaches `main`
unnoticed. It declares no `permissions:` block, so it inherits the default token scope, and there is
no Dependabot configuration.

**4.4 The base stylesheet is minified.** `frontend/src/style.css` holds its original rules on two
very long lines. The console styles are readable; the rest is not, in a repository whose purpose is
to be read.

---

## 5. What holds up

- **The profile-gated split** between `SecurityConfig` and `MockSecurityConfig` is the right shape.
  Two filter chains beat one chain full of conditionals, and it genuinely keeps Cognito
  uninitialised when credentials are absent.
- **The authorization-code flow with a server-side session**, and no token reaching the browser, is
  the correct pattern to demonstrate. `/api/me` whitelists claims rather than returning the raw
  attribute map, so a claim the provider starts releasing later cannot reach the browser without
  someone editing `EXPOSED_CLAIMS`.
- **The test suite is layered sensibly** — a plain unit test, a `MockMvc` test, a real-HTTP test that
  round-trips an actual session cookie, plus tests pinning the JSON error contract and the log feed.
  `MockSsoE2ETest` asserting that `/api/me` fails *after* logout is the assertion most POCs skip.
- **Errors are JSON whatever the caller's `Accept` header.** Spring's error rendering is
  content-negotiated by default, which is easy to get wrong: `curl` sees JSON while a browser sees an
  HTML error page for the same URL. `ErrorResponseTest` sends a browser-style `Accept` header for
  exactly this reason.
- **SSE survives the nginx hop.** `/api/logs/` has its own location block with `proxy_buffering off`
  and `proxy_http_version 1.1`; without both, the stream works in the Vite dev server and silently
  produces nothing under `docker compose`.
- **The console makes the handshake observable** rather than described, which is most of this
  repository's teaching value.
- **CI runs on every push and PR**, and the README documents a followable manual test with
  screenshots generated by a committed script.

---

## 6. Order of work

1. Guard mock sign-in (1.1). The only item that is not optional.
2. Add a LICENSE (4.1).
3. Test the non-mock security configuration (2.1).
4. Extract the frontend origin to a property (3.1).
5. CSRF tokens and cookie hardening (1.3, 1.4).
6. Build the images in CI (4.3).
7. Expand the stylesheet (4.4).

`PLAN-MULTI-APP.md` sequences this against the larger question of what would make this demonstrate
single sign-on rather than one application's session.
