# Plan: multi-app SSO and deployment

A plan for turning this POC from a single local app into something that actually demonstrates
single sign-on, and optionally into a deployed demo on Cloudflare.

Nothing here is built. This is the decision record and the sequencing.

Findings referenced as **R1.1**, **R2.2** and so on point at [REVIEW.md](REVIEW.md).

---

## 0. Decision — this stays a local teaching artifact

**Decided: the repository stays a local teaching artifact. It is not being deployed.**

That settles several things at once:

- **Phases 2, 4, 5 and 6 are not being pursued.** Sections 6 and 7 below are retained because the
  reasoning stays valid if the decision is ever revisited — read them as "what deployment would
  require", not as pending work.
- **Section 8 is re-scoped.** The original phase 1 was sized to make public deployment safe. Most
  of it is still worth doing for a teaching repo, but for different reasons, and one item changes
  shape entirely. See section 8.
- **Sections 1–5 remain the plan of record** for multi-app SSO, if that is ever wanted. Nothing
  about the analysis depends on deployment.

The rest of this document is unchanged from the pre-decision version except where marked.

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
| Why B lets you in | it is literally the same session cookie | Cognito recognised **its own** session and returned a code without prompting |
| Demonstrates Cognito | no | yes |

Two frontends pointed at one backend is the first column. It is cheap, it looks like the demo, and
it teaches nothing about SSO — Cognito is involved exactly once, at the start, and never again.

**The demo is the silent second redirect.** App B performs a full OIDC round trip; it just happens
without a password prompt because the identity provider already knows who you are. Remove the
second backend and that moment does not exist.

So: **not one backend.**

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
two apps except the identity provider.** That constraint is the whole point — if you find yourself
sharing anything else, the demo has quietly become column one of the table above.

### How many frontends?

**Two.** Two demonstrates SSO completely. A third identical app triples the config and teaches
nothing new.

Add a third only if it differs *in kind*. The variant worth building is a **public client using
PKCE with no client secret**, sitting next to two confidential clients — that contrasts two real
client types and is a genuine second lesson. A third copy of app A is not.

---

## 3. The mock profile cannot demonstrate this

Today's `mock` profile has no identity provider. `MockAuthController` fabricates an `OAuth2User`
and writes it straight into the session, skipping the entire handshake. There is no IdP session for
a second app to recognise, so there is nothing to demonstrate — you would be simulating the one
thing the demo is about.

Three ways out:

| Option | Cost | Result |
| --- | --- | --- |
| Cognito only, drop the mock | ~1 day | Real SSO, but the repo needs an AWS account to run **at all** |
| Hand-roll a mock OIDC provider | ~3–4 days | Real choreography, but you are writing an IdP: `/authorize`, `/token`, JWKS, JWT signing |
| **Spring Authorization Server** | **~1 day** | **A real OIDC provider, roughly 50 lines of config** |

### Recommendation: Spring Authorization Server

Add a third service (`idp/`, port 9000) running
[Spring Authorization Server](https://spring.io/projects/spring-authorization-server). Then `mock`
and `local` differ only in **which issuer URI the apps point at**. Same redirect choreography,
same Spring Security code path, no AWS account required.

This is more work than bolting on a second frontend. It buys three things:

1. The default profile demonstrates **real** SSO rather than an illusion of it.
2. `SecurityConfig` — the production class **R2.1** flags as never instantiated by any test —
   becomes the path everyone runs by default. That coverage gap closes as a side effect.
3. `MockSecurityConfig`, `MockAuthController`, the `app.sso-mode` property, and the mode branch in
   the frontend all get deleted. The codebase gets **smaller**.

Point 3 is worth dwelling on. The mock profile is currently a second, divergent security stack that
has to be kept honest by hand. Replacing it with a real IdP removes an entire class of "the
simulator drifted from reality" risk.

---

## 4. Two gotchas that will cost a day each

### 4.1 Cookies are not scoped by port

Per RFC 6265 the port is **not** part of a cookie's scope. `localhost:8080` and `localhost:8081`
share one cookie jar. Two backends both setting `JSESSIONID` will overwrite each other.

The failure is worse than a crash: it can look like SSO *working* when it is actually collision, or
break intermittently depending on request order.

**Set a distinct `server.servlet.session.cookie.name` per app in the very first commit**
(`ASESSION` / `BSESSION`). This one line is most of the difference between a demo that works and an
afternoon of confusion.

### 4.2 Single sign-on is not single sign-out

Signing out of app A destroys app A's session and nothing else. The IdP session survives, so app B
stays signed in — and clicking sign-in on B still sails through.

Ending the IdP session needs RP-initiated logout against the provider's `end_session_endpoint`
(Cognito exposes `/logout` with a `logout_uri` that must be a registered sign-out URL).

Do not treat this as a defect to hide. **It is the most instructive step in the demo** — it is
where people discover that "single sign-on" does not imply "single sign-out" for free.

---

## 5. The demo script

Two browser windows, side by side, both signed out.

| Step | Action | What to point at |
| --- | --- | --- |
| 1 | Sign in on **A** | Full login page. A's console shows the redirect and the code exchange. |
| 2 | Click sign in on **B** | **No prompt.** B's console shows the same round trip completing instantly. This is the moment. |
| 3 | Compare cookies | Two different session cookies. Independent sessions. |
| 4 | Sign out of **A** | B is *still signed in*. A only killed its own session. |
| 5 | RP-initiated logout at the IdP | *Now* B requires a fresh login. |

Step 4 is the one that teaches the most.

The console built in the current version pays off heavily here: with both apps open you can watch
B's silent handshake happen line by line, rather than asserting that it happened.

---

## 6. Deployment on Cloudflare

### What fits and what does not

| Piece | Cloudflare option | Verdict |
| --- | --- | --- |
| Frontend (Vite build) | **Pages** | Ideal. It is already a static bundle. |
| Backend (Spring Boot) | Workers / Pages Functions | **No.** JS and WASM isolates; they cannot run a JVM. |
| Backend (Spring Boot) | **Containers** | Works. Takes a Dockerfile, bound to a Worker via Durable Objects. Requires the **Workers Paid** plan. |
| Backend, for a live demo | **Tunnel** | Free. Real hostname pointing at a backend on your machine. Not always-on. |
| Backend, alternative | Fly / Render / Railway behind Cloudflare | Works, more moving parts. |

`backend/Dockerfile` already exists, so Containers is a genuine fit — **confirm the account is on
Workers Paid before planning around it.**

### The cookie problem moves, it does not disappear

Locally the session cookie is first-party because everything is `localhost`. Split the frontend
onto `*.pages.dev` and the backend onto another host and it becomes a **third-party cookie**,
requiring `SameSite=None; Secure` — which browsers are actively restricting.

**Keep them same-site.** Serve the SPA and route `/api/*` to the backend under **one hostname**,
mirroring what `frontend/vite.config.ts` and `frontend/nginx.conf` already do locally (**R4.3**).
Decide this before wiring Cognito callbacks, not after.

For the multi-app version that means two hostnames, one per app:

```
app-a.example.com   →  SPA  +  /api/* → backend A
app-b.example.com   →  SPA  +  /api/* → backend B
```

which also disposes of gotcha 4.1 entirely — genuinely distinct domains, genuinely distinct cookie
scopes.

---

## 7. Blocker: this code must not be deployed as it stands

Deployment converts three review findings from theoretical to live.

**7.1 — Public authentication bypass (R1.1).** `backend/Dockerfile` sets no profile, so
`application.yml`'s default applies: `mock`. Verified by running with no profile — anyone who can
reach the URL can `POST /api/auth/mock-login` and receive a valid authenticated session. Deploying
the current image publishes an open auth bypass.

**7.2 — Public log stream (R1.5a).** `/api/logs/**` is `permitAll` and streams server logs to any
caller. Acceptable on localhost, an information leak on the public internet.

**7.3 — It would not work anyway (R4.2).** `http://localhost:5173` is hardcoded in five places
across both security configs: CORS origin ×2, `defaultSuccessUrl`, logout redirect ×2.

---

## 8. Phase 1 — hardening, re-scoped for a teaching artifact

> **Revised after the section 0 decision.** The original list was sized to make public deployment
> safe. Most items survive, for teaching reasons rather than exposure reasons. Item 8.1 changes
> shape — see 8.0 first.

Roughly half a day as re-scoped.

Order matters: **8.2 before 8.1**, or the test suite breaks for an unrelated reason.

### 8.0 Does hardening take the mock away? No.

The simulator stays. Nothing in this list removes `MockAuthController`, and running it is unchanged:

```powershell
$env:SPRING_PROFILES_ACTIVE='mock'; mvn spring-boot:run
```

That is already what the README documents and what the dev terminals run. What the original 8.1
removed was the *silent* case — a bare `mvn spring-boot:run` or a `docker run` with no profile
coming up in mock without anyone asking for it.

**For a local teaching artifact, doing that as written is the wrong trade.** A repo where
`mvn spring-boot:run` fails on a fresh clone teaches worse, and the DX cost was only justified by
making deployment safe. Deployment is off the table.

So invert it: **keep mock as the default, and make the mock endpoint refuse to serve anyone who is
not local.** `MockAuthController` checks that the request comes from a loopback address and returns
403 otherwise. Local development is completely unaffected; the dangerous case — this image running
somewhere public — stops working on its own, with no configuration to remember.

Pair it with an explicit `app.allow-mock-login` property so there are two independent guards, and
with the startup `WARN` in 8.6 so a learner can see the simulator announce itself.

Caveat worth knowing: `server.forward-headers-strategy: framework` is already set, so behind a
proxy `getRemoteAddr()` reflects `X-Forwarded-For`, which is spoofable if the proxy is not trusted.
That is acceptable for a local-only artifact and is exactly why the property guard exists too — but
it is why this is *not* a substitute for the original 8.1 if the deployment decision is ever
reversed.

### The list

Rows marked **done** were applied in a hardening pass after this plan was written.

| # | File | Change | Finding |
| --- | --- | --- | --- |
| 8.1 | `backend/.../web/MockAuthController.java` | **Revised:** keep mock as default; reject non-loopback callers, and gate on `app.allow-mock-login`. | R1.1 |
| 8.2 | `backend/src/test/java/.../AuthControllerTest.java` | **done** — pinned with `@ActiveProfiles("mock")`. | R2.2 |
| 8.3 | `backend/.../config/SecurityConfig.java`, `MockSecurityConfig.java` | Replace the five hardcoded `http://localhost:5173` with `@Value("${app.frontend-origin}")`. | R4.2 |
| 8.4 | `application.yml` | Add `app.frontend-origin: http://localhost:5173` as the local default. | R4.2 |
| 8.5 | `application.yml` | Open. `same-site: lax` is worth setting; `secure: true` is meaningless over plain-HTTP localhost. | R1.3 |
| 8.6 | `MockAuthController.java` | **done** — logs a startup `WARN` naming the risk whenever mock login is registered. | R1.1 |
| 8.7 | Both security configs + `frontend/src/main.tsx` | `CookieCsrfTokenRepository.withHttpOnlyFalse()`; frontend echoes `X-XSRF-TOKEN` on POSTs. **Judgement call for a teaching repo** — see below. | R1.2 |
| 8.8 | `backend/Dockerfile` | **done** — wildcard jar, non-root user, and `curl` for the healthcheck. | R5.1, R5.2 |
| 8.9 | `docker-compose.yml` | **done** — healthcheck gating the frontend. Not yet run against a live daemon. | R5.6 |
| 8.10 | `AuthController.currentUser` | **done** — 401 on a null principal, and `EXPOSED_CLAIMS` whitelists what reaches the browser. | R3.1, R1.4 |

### On 8.7, CSRF

Local risk is low, and implementing it properly is roughly half a day. But this repository exists
to teach authentication, and "CSRF is disabled on `/api/**`" sitting unexplained next to
cookie-based sessions is a bad lesson for anyone reading it as a reference.

Two acceptable answers, in order of preference:

1. **Implement it.** It is a genuine part of the cookie-session story, and a teaching repo about
   auth is the right place to show it done correctly.
2. **Document why it is off**, prominently, next to the code that disables it — not only in
   `REVIEW.md`. A deliberate, explained simplification teaches something; an unexplained one
   teaches the wrong habit.

What is not acceptable is leaving it as-is with no comment at the call site.

### Dropped from the original list

- **Deployment-only items**: `secure: true` cookies, the same-hostname routing in section 6, and
  removing the log stream. All were driven by exposure that no longer exists.
- **8.3 / 8.4** (frontend origin as a property) are retained but demoted to nice-to-have. They
  remove real hardcoding (R4.2), but with one app on one origin the payoff is tidiness, not
  correctness.

---

## 9. Sequencing

Status reflects the section 0 decision.

| Phase | Work | Effort | Status |
| --- | --- | --- | --- |
| **1** | Hardening, as re-scoped in section 8 | ~half a day | **Recommended** |
| **2** | Deploy one app to Cloudflare | ~1 day | Not pursued — not deploying |
| **3** | Add Spring Authorization Server as `idp/` | ~1 day | **Optional, high value** |
| **4** | Split into app-a / app-b | ~1–2 days | Not pursued for now |
| **5** | RP-initiated logout | ~half a day | Depends on 4 |
| **6** | Third app, public client + PKCE | ~half a day | Not pursued |

### Phase 3 is worth reconsidering on its own

Phase 3 was originally a step toward the SSO demo, but it stands up independently and is the
single highest-value change left for a *teaching* repo — regardless of whether phases 4–5 ever
happen:

- It makes the default path exercise the **real** authorization-code flow instead of short-circuiting
  it, so a learner reading the code sees the thing the README describes.
- It closes **R2.1**: `SecurityConfig` stops being a class no test ever instantiates.
- It **deletes** `MockSecurityConfig`, `MockAuthController`, `app.sso-mode`, and the mode branch in
  the frontend. Less code, one security stack instead of two.

Note the interaction with section 8: doing phase 3 makes most of item 8.1 moot, because there is no
longer a fabricated-identity endpoint to guard. If phase 3 is likely, do the cheap half of 8.1 (the
startup warning) and skip the loopback guard.

### If the deployment decision is ever reversed

Read section 7 first, and do phase 2 **before** phase 4 — every hardcoded-origin problem has to be
solved regardless, and solving it once on one app is far cheaper than discovering it twice across
two. Item 8.1 also reverts to its original form; the loopback guard is not a substitute.

---

## 10. If you do nothing else

~~Do item 8.2, then 8.6 and 8.10.~~ **All three are done**, along with 8.8 and 8.9.

What is left that matters most, in order: **8.1** (the loopback guard from 8.0), **8.7** (CSRF —
decide implement or document), and **R2.1** from the review, which is the largest remaining gap:
no test exercises the non-mock security configuration at all.

The original "if you do nothing else" was 8.1, on the grounds that the default profile is an auth
bypass waiting for a `docker run`. That still holds **if this is ever deployed** — but under the
section 0 decision it is a landmine for a hypothetical future reader rather than a live exposure,
which is why it drops behind the correctness items.

---

## 11. Open questions

Live, given the section 0 decision:

- **Is phase 3 wanted?** It is the highest-value remaining change for a teaching repo and it makes
  the codebase smaller, but it is a day and it deletes the mock stack people may be used to.
- **8.7 (CSRF): implement, or document why not?** See section 8. Either is defensible; leaving it
  silent is not.

Deferred with the deployment decision, kept in case it is revisited:

- Is the Cloudflare account on **Workers Paid**? Determines Containers vs Tunnel vs third-party
  host.
- Custom domain, or `*.pages.dev`? Cognito callback URLs must be registered either way.
- Would a deployed demo use **real Cognito** or the local IdP?
- Would a deployed version keep the backend log stream? Recommendation: no (**R1.5a**).
