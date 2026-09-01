# Code review

A review of the POC as it stands, covering what works, what is missing, and
what I would build differently.

Baseline at review time: `mvn test` passed (9 tests), `npm test` passed (3
tests), `npm run build` succeeded. The mock flow worked end to end in the
browser and over curl.

Items marked **FIXED** were addressed after the review, in response to a
Whitelabel error page hit while using the app. Items marked **NEW** cover work
added since. The suites now stand at 17 backend and 6 frontend tests. Everything
else below is still outstanding.

Findings are labelled **verified** when I ran something that demonstrates
them, and **by inspection** when they follow from reading the code but were
not exercised.

---

## 1. Security

### 1.1 The insecure profile is the default — verified

`application.yml` pins `spring.profiles.active: mock`. Nothing overrides it
unless a profile is passed explicitly, so every one of these activates the
mock stack:

```
java -jar app.jar
docker run <backend-image>
mvn spring-boot:run
```

In that stack `POST /api/auth/mock-login` is `permitAll` and mints a fully
authenticated session for anyone who asks. Started with no profile argument
and confirmed:

```
The following 1 profile is active: "mock"

$ curl -i -c c.txt -X POST http://localhost:8080/api/auth/mock-login
HTTP/1.1 200

$ curl -b c.txt http://localhost:8080/api/me
{"email":"demo@example.com","name":"Demo User","subject":"mock-user-001", ...}
```

`docker-compose.yml` sets `SPRING_PROFILES_ACTIVE: mock` explicitly, which is
correct for the demo — but it means the safe behaviour depends on the caller
remembering, and the failure mode is silent. A deployment that forgets the
variable does not break; it comes up with authentication disabled.

The default should be the secure path, with the simulator opted into:

- Make `local`/`cognito` the default in `application.yml` and require
  `SPRING_PROFILES_ACTIVE=mock` for the simulator.
- Log a loud `WARN` at startup whenever `MockAuthController` is registered.
- Consider a second guard (`app.allow-mock-login=true`) so the profile alone
  is not enough.

This is the one finding I would fix before showing the repo to anyone else.

### 1.2 `/api/**` is CSRF-exempt while sessions live in a cookie — by inspection

Both configs carry `csrf.ignoringRequestMatchers("/api/**")` and authenticate
via a session cookie. That is the classic CSRF setup: any origin can cause a
browser to send an authenticated `POST /api/auth/logout`, and under the mock
profile `POST /api/auth/mock-login` as well.

The CORS allowlist does not mitigate this. CORS governs whether JavaScript may
*read* the response; it does not stop the request from being sent and the
side effect from happening.

For a local POC the impact is small, but the shape is wrong to demonstrate.
Prefer `CookieCsrfTokenRepository.withHttpOnlyFalse()` and have the frontend
echo `X-XSRF-TOKEN`.

### 1.3 No session cookie hardening — by inspection

There is no `server.servlet.session.cookie.*` configuration, so `SameSite` and
`Secure` are unset. On `localhost` this is harmless — 5173 and 8080 differ only
by port, so the cookie is same-site — but nothing carries that safety to a
deployment. Add `same-site=lax` now and `secure=true` behind TLS.

### 1.4 `/api/me` returns the entire raw claim set — FIXED

```java
"claims", user.getAttributes()
```

Today the mock identity holds three benign claims. The problem is that this is
an open pipe: any claim Cognito is later configured to release — groups, phone
number, custom attributes — reaches the browser automatically, with no code
change and no review. Whitelist the fields the UI actually renders.

Worth noting the existing guidance in `CLAUDE.md` about not exposing tokens is
being followed — no access token is returned. It is the claim set that is
unbounded.

**Fixed.** `AuthController.EXPOSED_CLAIMS` whitelists `sub`, `name`, `email`, and
`email_verified`; anything else the provider releases is dropped before the
response is built. A unit test feeds in `custom:salary` and `phone_number` and
asserts neither survives.

### 1.5a The backend log stream is an intentional, gated exposure — NEW

Added after this review: `/api/logs/stream` publishes Spring Boot log lines over SSE so the
frontend console can show the handshake. It is `permitAll` — it has to be, or it could not show
anything before a session exists — and it is registered only when `app.log-stream.enabled` is
true, which only `application-mock.yml` sets.

This is a real exposure, recorded here rather than buried: anyone who can reach the port can read
server logs. It is confined to the local simulator by the property gate, and only four fields
leave the server (no stack traces, no thread detail). If 1.1 is fixed by flipping the default
profile, this endpoint stops being registered by default too, which is the right coupling.

Do not enable it under `local` without putting authentication in front of it.

### 1.5 API errors are shaped for browsers, not for callers — FIXED

Under the non-mock profile `oauth2Login` installs a redirecting authentication
entry point. An unauthenticated `GET /api/me` should therefore answer `302` to
the Cognito hosted UI rather than `401`, which is the wrong contract for an
endpoint the frontend calls with `fetch`.

I scored this as latent, on the reasoning that the frontend only calls `/api/me`
after `/api/auth/status` reports `authenticated: true`. That was wrong — see 3.4.

**Fixed.** Both configs now install JSON `authenticationEntryPoint` and
`accessDeniedHandler` pairs, and `ApiErrorController` replaces the Whitelabel
page outright. Under the non-mock profile only `/api/**` gets the JSON 401;
everything else keeps the redirect entry point, so browser sign-in still works.

This surfaced in practice rather than in review: clicking the frontend's sign-in
button produced a `403` Whitelabel HTML page. The review had this as latent and
unexercised, and it was neither — see 3.4 for why the request was made at all.
Worth recording that the reason I mis-scored it is that `curl` already received
JSON for the same URL; Spring's error rendering is content-negotiated, so the
failure only reproduces with a browser's `Accept: text/html`.

---

## 2. Test coverage gaps

### 2.1 The real Cognito path is entirely untested — verified

All 19 backend tests exercise the mock stack. `SecurityConfig` — the class that
matters in production — is never instantiated by any test. The suite would stay
green if it were deleted. The JSON entry point added for 1.5 is likewise only
covered on the mock side.

A test with a stubbed `ClientRegistrationRepository` would cover the filter
chain, the CORS rules, and the entry-point behaviour in 1.5 without needing a
real user pool.

### 2.2 `AuthControllerTest` depends on an implicit profile — FIXED

It is annotated `@SpringBootTest` with no `@ActiveProfiles`, so it inherits
`spring.profiles.active: mock` from `application.yml` and quietly tests the
mock chain. Two consequences:

- The test name suggests general coverage; the reality is mock-only.
- If 1.1 is fixed by flipping the default profile, this test starts trying to
  boot the Cognito config and fails for a reason unrelated to what changed.

**Fixed.** Pinned with `@ActiveProfiles("mock")`, so it no longer depends on the
root `application.yml` default staying what it is.

### 2.3 Untested behaviours worth covering

- The CORS configuration — no test asserts that a disallowed origin is
  rejected or that credentials are permitted for 5173.
- `AuthController.currentUser`'s fallback branch is covered in the unit test
  but not through the HTTP layer.
- The frontend now covers the error path and the unresolved-status case, but
  still has no test for the `cognito` branch of the sign-in button or a failed
  logout.

---

## 3. Correctness and robustness

**3.1 `currentUser` can NPE — FIXED.** `@AuthenticationPrincipal OAuth2User user`
is null whenever the principal is not an `OAuth2User`, and the method dereferenced
it immediately — a 500 rather than a 401. It now throws
`ResponseStatusException(UNAUTHORIZED)`, covered by a unit test.

**3.2 One error message for every failure — FIXED.** The frontend catch
rendered "Start the Spring Boot backend on port 8080 to connect." for any thrown
error, so a 500 from a running backend told the user to start the backend. `api()`
now separates a failed connection from an HTTP error and prefers the backend's
JSON `message` when there is one.

**3.3 Logout ignores its response — FIXED.** `signOut()` awaited the fetch and
then cleared state unconditionally, so a failed POST rendered as a clean sign-out
while the session was still live on the server. It now checks the response, leaves
the session state alone on failure, and reports the failure in the console.
Covered by a frontend test.

**3.4 The sign-in button defaulted to Cognito when the mode was unknown — FIXED.**
This one was missed by the original review and found by using the app. `status`
starts `null` and is fetched once on mount with no retry, so a page loaded while
the backend was down kept `status === null` indefinitely. The button's branch was
`status?.mode === 'mock' ? mock-login : cognito`, so the unknown case fell through
to the Cognito path and navigated to `/oauth2/authorization/cognito` — an endpoint
that does not exist under the `mock` profile. The label read "Continue with AWS
SSO" on a backend running the simulator.

Two lessons worth keeping. A ternary on `status?.mode` silently merges "unknown"
into "not mock", and the fix is to make the third state explicit rather than to
pick a better default. And the failure needed two conditions to appear — page
loaded before the backend, then clicked after — which is why neither the tests
nor a manual walkthrough in the documented order caught it.

The button is now disabled until status resolves, and the PROVIDER tile reads
"Not connected" instead of claiming AWS Cognito. Covered by a regression test.

---

## 4. Configuration drift and dead code

**4.1 `frontend/.env.example` was dead — FIXED.** It declared `VITE_API_ORIGIN`,
which no code read; the frontend uses relative paths plus the dev-server proxy.
Deleted rather than wired up, because the proxy is the design and a second
mechanism would just be a second thing to keep in sync.

**4.2 The frontend origin is hardcoded in five places.** `http://localhost:5173`
appears in `SecurityConfig` (CORS, `defaultSuccessUrl`, logout redirect) and
`MockSecurityConfig` (CORS, `logoutSuccessUrl`). Changing where the frontend
runs means editing Java. This belongs in a property.

**4.3 Proxy prefixes have drifted between dev and Docker.**

| Prefix | `vite.config.ts` | `nginx.conf` |
| --- | --- | --- |
| `/api` | yes | yes |
| `/oauth2` | yes | yes |
| `/login` | yes | yes |
| `/logout` | yes | **no** |
| `/actuator` | no | no |

Spring's `/logout` is unreachable through the Docker frontend. It is unused
today because the app calls `/api/auth/logout`, but the two files are supposed
to mirror each other and no longer do. Neither proxies `/actuator`, which is
why the UI's health link hardcodes `http://localhost:8080` — the single place
in the frontend that bypasses the proxy, and the one that breaks first if the
backend port stops being published.

**4.4 `app.sso-mode` was defined in four places that disagreed — FIXED.** The root
`application.yml` no longer declares it. Each profile states its own value, and the
code fallback (`cognito`) is now the safe default that applies if a profile forgets
to declare one, rather than dead unreachable config contradicting the root file.

**4.5 Unused imports — FIXED.** `ClientRegistrationRepository` was imported and
never used in both `SecurityConfig` and `AuthControllerTest`. Both removed.

---

## 5. Build and repository hygiene

**5.1 The backend image hardcoded the jar version — FIXED.** Now
`COPY --from=build /workspace/target/*.jar app.jar`, so a pom version bump no
longer breaks the image build.

**5.2 The backend image ran as root — FIXED.** It now creates a system user and
switches to it before the entrypoint. The frontend image is stock `nginx:alpine`,
which manages its own privilege drop.

**5.3 No LICENSE.** The repo is public and the UI footer carries a copyright
line, but there is no license file, so nobody can legally reuse it.

**5.4 No `.gitattributes` — FIXED.** Added, with `* text=auto eol=lf`, binary
patterns for images and jars, and CRLF pinned for `.bat`/`.cmd`/`.ps1`. This was
emitting a warning on every commit made while reviewing.

**5.5 No linter or formatter anywhere** — no ESLint, Prettier, Checkstyle, or
Spotless. `npm run build` (via `tsc -b`) and `mvn test` are the only gates.

**5.6 `docker-compose.yml` had no healthcheck — FIXED.** A `curl` healthcheck on
`/actuator/health` now gates the frontend via `condition: service_healthy`. `curl`
is installed explicitly in the backend image rather than assumed present: with
`service_healthy`, a check that can never pass hangs startup forever, which is
worse than the 502s it prevents.

**Not verified end to end** — the Docker daemon was not running, so
`docker compose up --build` has not been executed against this change.

**5.7 CI gaps.** `.github/workflows/ci.yml` runs both test suites but never
builds the Docker images or smoke-tests compose, so 5.1 and 5.6 would not be
caught. It also has no `permissions:` block (so it inherits the default token
scope) and there is no Dependabot configuration.

**5.8 The source is minified.** `frontend/src/style.css` is 3.2 KB on two
lines, and `main.tsx` puts the entire JSX tree on a handful of very long lines.
This is generated-looking code in a repo whose whole purpose is to be read and
learned from. I would expand both and split `App` out of `main.tsx`.

**5.9 `vitest.config.ts` was never typechecked — FIXED.** Added to
`tsconfig.node.json`'s `include`.

---

## 6. What is done well

Worth stating, because these are the decisions I would keep:

- The profile-gated split between `SecurityConfig` and `MockSecurityConfig` is
  the right shape. Two separate filter chains beat one chain full of
  conditionals, and it genuinely keeps Cognito uninitialised when credentials
  are absent.
- The authorization-code flow with a server-side session, and no token ever
  reaching the browser, is the correct pattern to demonstrate.
- The test suite is layered sensibly — a plain unit test, a `MockMvc` test, and
  a real-HTTP test that round-trips an actual session cookie. The E2E test
  asserting that `/api/me` fails *after* logout is exactly the assertion most
  POCs skip.
- CI runs on every push and PR, and the README documents a real, followable
  manual test with screenshots.

---

## 7. Suggested order of work

Sequenced against the deployment and multi-app work in
[PLAN-MULTI-APP.md](PLAN-MULTI-APP.md), whose phase 1 is drawn from this list.


Items 2, 3 (partly), 4 and several from section 5 are now done — see the FIXED
markers above. What remains, in order:

1. Guard mock login (1.1). Under the local-artifact decision this is the loopback
   guard in PLAN section 8.0, not the original default-profile flip.
2. Add a LICENSE (5.3). Still absent, and the repo is public.
3. Extract the frontend origin to a property (4.2).
4. Restore proxy parity and proxy `/actuator` (4.3).
5. Add a non-mock security test (2.1) — still the largest real gap.
6. CSRF tokens and cookie hardening (1.2, 1.3).
7. Expand the minified sources (5.8).
