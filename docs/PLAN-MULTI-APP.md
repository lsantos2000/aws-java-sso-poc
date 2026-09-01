# Plan: multi-app SSO and deployment

What it would take for this repository to demonstrate single sign-on rather than one application's
session, and what deploying it would require.

Nothing here is built. Findings referenced as **R1.1**, **R2.1** and so on point at
[REVIEW.md](REVIEW.md).

---

## 0. Scope

**This repository is a local teaching artifact. It is not deployed.**

- Sections 1–5 are the plan of record for multi-app SSO, if that is ever wanted.
- Sections 6 and 7 describe what deployment would require. They are reference, not pending work.
- Section 8 is hardening scoped for a teaching repository rather than for public exposure.

---

## 1. The distinction that drives everything

"Sign into app A, and app B lets you straight through" can be produced two ways, and only one of
them is single sign-on.

| | Shared session | Single sign-on |
| --- | --- | --- |
| Backends | one | two |
| Relying parties | one | two |
| Cognito app clients | one | two |
| Sessions | one, shared | two, independent |
| Why B lets you in | it is literally the same session cookie | Cognito recognises **its own** session and returns a code without prompting |
| Demonstrates Cognito | no | yes |

Two frontends pointed at one backend is the first column. It is cheap, it looks like the demo, and
it teaches nothing about SSO — Cognito is involved once, at the start, and never again.

**The demo is the silent second redirect.** App B performs a full OIDC round trip; it just happens
without a password prompt because the identity provider already knows who you are. Remove the second
backend and that moment does not exist.

So: not one backend.

---

## 2. Target architecture

```
                        ┌────────────────────┐
                        │  Identity provider │   holds THE session that
                        │  Cognito / local   │   makes it "single"
                        └─────────┬──────────┘
                    ┌─────────────┴─────────────┐
                    │                           │
          app-a backend  :8080         app-b backend  :8081
          app-a frontend :5173         app-b frontend :5174
          app client A                 app client B
          cookie ASESSION              cookie BSESSION
```

Each app owns its Spring session, its app client, and its cookie. **Nothing is shared between the
two apps except the identity provider.** That constraint is the point — sharing anything else turns
the demo into column one of the table above.

### How many frontends?

**Two.** Two demonstrates SSO completely. A third identical app triples the configuration and
teaches nothing new.

A third earns its place only if it differs in kind. The variant worth building is a **public client
using PKCE with no client secret**, alongside two confidential clients — that contrasts two real
client types and is a genuine second lesson.

---

## 3. The mock profile cannot demonstrate this

The `mock` profile has no identity provider. `MockAuthController` fabricates an `OAuth2User` and
writes it straight into the session, skipping the handshake. There is no IdP session for a second
app to recognise, so there is nothing to demonstrate.

| Option | Cost | Result |
| --- | --- | --- |
| Cognito only, drop the mock | ~1 day | Real SSO, but the repository needs an AWS account to run at all |
| Hand-roll a mock OIDC provider | ~3–4 days | Real choreography, but you are writing an IdP: `/authorize`, `/token`, JWKS, JWT signing |
| **Spring Authorization Server** | **~1 day** | **A real OIDC provider, roughly 50 lines of configuration** |

### Recommendation: Spring Authorization Server

Add a third service (`idp/`, port 9000) running
[Spring Authorization Server](https://spring.io/projects/spring-authorization-server). `mock` and
`local` then differ only in which issuer URI the apps point at: same redirect choreography, same
Spring Security code path, no AWS account required.

It buys three things:

1. The default profile demonstrates real SSO rather than an illusion of it.
2. It closes **R2.1** — `SecurityConfig` stops being a class no test ever instantiates.
3. It deletes `MockSecurityConfig`, `MockAuthController`, `app.sso-mode`, and the mode branch in the
   frontend. The codebase gets **smaller**.

Point 3 matters most. The mock profile is a second, divergent security stack kept honest by hand;
replacing it with a real IdP removes an entire class of "the simulator drifted from reality" risk.

---

## 4. Two gotchas that will cost a day each

### 4.1 Cookies are not scoped by port

Per RFC 6265 the port is not part of a cookie's scope. `localhost:8080` and `localhost:8081` share
one cookie jar, so two backends both setting `JSESSIONID` overwrite each other.

The failure is worse than a crash: it can look like SSO *working* when it is collision, or break
intermittently depending on request order.

**Set a distinct `server.servlet.session.cookie.name` per app in the first commit** (`ASESSION` /
`BSESSION`). This one line is most of the difference between a demo that works and an afternoon of
confusion.

### 4.2 Single sign-on is not single sign-out

Signing out of app A destroys app A's session and nothing else. The IdP session survives, so app B
stays signed in, and clicking sign-in on B still sails through.

Ending the IdP session needs RP-initiated logout against the provider's `end_session_endpoint`
(Cognito exposes `/logout` with a `logout_uri` that must be a registered sign-out URL).

This is not a defect to hide. **It is the most instructive step in the demo** — it is where people
discover that single sign-on does not imply single sign-out.

---

## 5. The demo script

Two browser windows, side by side, both signed out.

| Step | Action | What to point at |
| --- | --- | --- |
| 1 | Sign in on **A** | Full login page. A's console shows the redirect and the code exchange. |
| 2 | Click sign in on **B** | **No prompt.** B's console shows the same round trip completing instantly. This is the moment. |
| 3 | Compare cookies | Two different session cookies. Independent sessions. |
| 4 | Sign out of **A** | B is still signed in. A only killed its own session. |
| 5 | RP-initiated logout at the IdP | Now B requires a fresh login. |

Step 4 teaches the most.

The console pays off here: with both apps open you can watch B's silent handshake line by line,
rather than asserting that it happened.

---

## 6. Deployment on Cloudflare

Reference only — see section 0.

| Piece | Cloudflare option | Verdict |
| --- | --- | --- |
| Frontend (Vite build) | **Pages** | Ideal. It is already a static bundle. |
| Backend (Spring Boot) | Workers / Pages Functions | **No.** JS and WASM isolates cannot run a JVM. |
| Backend (Spring Boot) | **Containers** | Works. Takes a Dockerfile, bound to a Worker via Durable Objects. Requires the **Workers Paid** plan. |
| Backend, for a live demo | **Tunnel** | Free. Real hostname pointing at a backend on your machine. Not always-on. |
| Backend, alternative | Fly / Render / Railway behind Cloudflare | Works, more moving parts. |

`backend/Dockerfile` exists and builds, so Containers is a genuine fit.

### The cookie problem moves, it does not disappear

Locally the session cookie is first-party because everything is `localhost`. Splitting the frontend
onto `*.pages.dev` and the backend onto another host makes it a **third-party cookie**, requiring
`SameSite=None; Secure`, which browsers are actively restricting.

Keep them same-site: serve the SPA and route `/api/*` to the backend under **one hostname**,
mirroring what `frontend/vite.config.ts` and `frontend/nginx.conf` do locally. Decide this before
wiring Cognito callbacks.

For the multi-app version that means two hostnames, one per app:

```
app-a.example.com   →  SPA  +  /api/* → backend A
app-b.example.com   →  SPA  +  /api/* → backend B
```

which also disposes of gotcha 4.1 — genuinely distinct domains, genuinely distinct cookie scopes.

---

## 7. What deployment would require first

Three items from the review become live exposures the moment this is reachable:

**7.1 — Public authentication bypass (R1.1).** `backend/Dockerfile` sets no profile, so
`application.yml`'s default applies: `mock`. Anyone who can reach the URL can
`POST /api/auth/mock-login` and receive a valid session.

**7.2 — Public log stream (R1.2).** `/api/logs/**` is `permitAll` and streams server logs to any
caller.

**7.3 — Hardcoded origins (R3.1).** `http://localhost:5173` appears in five places across both
security configs, so the deployed app would not work regardless.

---

## 8. Hardening for a teaching artifact

Roughly half a day. Start with 8.0, which sets the shape of 8.1.

### 8.0 Does hardening take the mock away? No.

The simulator stays. Nothing here removes `MockAuthController`, and running it is unchanged:

```powershell
$env:SPRING_PROFILES_ACTIVE='mock'; mvn spring-boot:run
```

That is what the README documents and what the dev terminals run. The problem to solve is the
*silent* case — a bare `mvn spring-boot:run` or a `docker run` with no profile coming up in mock
without anyone asking for it.

Making the secure profile the default fixes that, at the cost of a repository where
`mvn spring-boot:run` fails on a fresh clone. For a teaching artifact that trade is not worth it.

So: **keep mock as the default, and make the mock endpoint refuse to serve anyone who is not
local.** `MockAuthController` checks that the request comes from a loopback address and returns 403
otherwise. Local development is unaffected; the dangerous case stops working on its own, with no
configuration to remember. Pair it with an explicit `app.allow-mock-login` property so there are two
independent guards.

Caveat: `server.forward-headers-strategy: framework` is set, so behind a proxy `getRemoteAddr()`
reflects `X-Forwarded-For`, which is spoofable if the proxy is not trusted. That is acceptable for a
local-only artifact, and is why the property guard exists alongside it. It is not sufficient if the
deployment decision is reversed — that needs the secure profile as the default.

### The list

Rows marked **done** are in place; the rest are open.

| # | File | Change | Finding |
| --- | --- | --- | --- |
| 8.1 | `backend/.../web/MockAuthController.java` | Reject non-loopback callers; gate on `app.allow-mock-login`. | R1.1 |
| 8.2 | `backend/src/test/java/.../AuthControllerTest.java` | **done** — pins `@ActiveProfiles("mock")` rather than inheriting the root default. | — |
| 8.3 | `backend/.../config/SecurityConfig.java`, `MockSecurityConfig.java` | Replace the five hardcoded `http://localhost:5173` with `@Value("${app.frontend-origin}")`. | R3.1 |
| 8.4 | `application.yml` | Add `app.frontend-origin: http://localhost:5173` as the local default. | R3.1 |
| 8.5 | `application.yml` | Set `server.servlet.session.cookie.same-site: lax`. `secure: true` matters only behind TLS. | R1.4 |
| 8.6 | `MockAuthController.java` | **done** — logs a startup `WARN` naming the risk. | R1.1 |
| 8.7 | Both security configs + `frontend/src/main.tsx` | `CookieCsrfTokenRepository.withHttpOnlyFalse()`; frontend echoes `X-XSRF-TOKEN` on POSTs. | R1.3 |
| 8.8 | `backend/Dockerfile` | **done** — wildcard jar, non-root user, `curl` for the healthcheck. | — |
| 8.9 | `docker-compose.yml` | **done** — healthcheck gates the frontend on `service_healthy`. | — |
| 8.10 | `AuthController.currentUser` | **done** — 401 on a null principal; `EXPOSED_CLAIMS` whitelists what reaches the browser. | — |

### On 8.7, CSRF

Local risk is low and implementing it properly is roughly half a day. But this repository exists to
teach authentication, and `csrf.ignoringRequestMatchers("/api/**")` sitting unexplained next to
cookie-based sessions is a bad lesson for anyone reading it as a reference.

Two acceptable answers, in order of preference:

1. **Implement it.** It is a genuine part of the cookie-session story, and a teaching repository
   about auth is the right place to show it done correctly.
2. **Document why it is off**, prominently, at the call site — not only in `REVIEW.md`. A deliberate,
   explained simplification teaches something; an unexplained one teaches the wrong habit.

Leaving it silent is not acceptable.

### Out of scope

- **Deployment-only items**: `secure: true` cookies, the same-hostname routing in section 6, and
  removing the log stream. All are driven by exposure this repository does not have.
- **8.3 / 8.4** are nice-to-have. They remove real hardcoding (R3.1), but with one app on one origin
  the payoff is tidiness rather than correctness.

---

## 9. Sequencing

| Phase | Work | Effort | Status |
| --- | --- | --- | --- |
| **1** | Hardening (section 8) | ~half a day | **Recommended** |
| **2** | Deploy one app to Cloudflare | ~1 day | Not pursued |
| **3** | Add Spring Authorization Server as `idp/` | ~1 day | **Optional, high value** |
| **4** | Split into app-a / app-b | ~1–2 days | Not pursued |
| **5** | RP-initiated logout | ~half a day | Depends on 4 |
| **6** | Third app, public client + PKCE | ~half a day | Not pursued |

### Phase 3 stands on its own

Phase 3 is a step toward the SSO demo, but it is also the highest-value change available to a
*teaching* repository regardless of whether phases 4–5 happen — see section 3.

It interacts with section 8: phase 3 makes item 8.1 moot, because there is no longer a
fabricated-identity endpoint to guard. If phase 3 is likely, skip the loopback guard.

### If the deployment decision is reversed

Read section 7 first, and do phase 2 **before** phase 4 — every hardcoded-origin problem has to be
solved regardless, and solving it once on one app is cheaper than discovering it twice across two.
Item 8.1 then needs the secure-profile default; the loopback guard is not a substitute.

---

## 10. If you do nothing else

**Do item 8.1.** `POST /api/auth/mock-login` mints an authenticated session for any caller and the
mock profile is the default, so that guard is the only thing between this image and an open bypass
if it is ever run somewhere reachable.

After that: **8.7** (CSRF — implement or document, but do not leave it silent), and **R2.1**, the
largest gap in the repository — no test exercises the non-mock security configuration.

---

## 11. Open questions

- **Is phase 3 wanted?** It is the highest-value remaining change for a teaching repository and it
  makes the codebase smaller, but it is a day and it deletes the mock stack.
- **8.7: implement CSRF, or document why not?** Either is defensible. Silence is not.

Deferred with the deployment decision:

- Is the Cloudflare account on **Workers Paid**? Determines Containers vs Tunnel vs a third-party
  host.
- Custom domain, or `*.pages.dev`? Cognito callback URLs must be registered either way.
- Would a deployed demo use real Cognito or the local IdP?
- Would a deployed version keep the backend log stream? Recommendation: no (**R1.2**).
