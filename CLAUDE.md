# CLAUDE.md

## Project Overview

`aws-java-sso-poc` is a proof of concept for AWS single sign-on using:

- AWS Cognito as the OIDC identity provider
- Spring Boot and Spring Security OAuth2 Client as the Java backend
- React, Vite, and TypeScript as the frontend

The project is intentionally small and local-development focused. Do not add AWS credentials, Cognito client secrets, or user-pool identifiers to source control.

## Repository Layout

- `backend/`: Maven Spring Boot application on port `8080`
- `frontend/`: Vite React application on port `5173`
- `README.md`: setup and run instructions

## Authentication Flow

1. The frontend sends the browser to `/oauth2/authorization/cognito`.
2. Spring Security redirects the browser to the Cognito hosted UI.
3. Cognito redirects to `http://localhost:8080/login/oauth2/code/cognito`.
4. Spring Security exchanges the authorization code and creates the server-side session.
5. The frontend calls `/api/auth/status` and `/api/me` with credentials included.
6. Logout uses `/logout` and redirects to the frontend.

Keep the authorization-code flow and server-managed session model unless the task explicitly asks to redesign authentication.

When AWS credentials are unavailable, run the `mock` Spring profile. It uses `/api/auth/mock-login` to create a local demo session and does not initialize Cognito. Keep this simulator clearly separated from the real Cognito profile.

## Configuration

The backend reads these environment variables:

- `AWS_COGNITO_CLIENT_ID`
- `AWS_COGNITO_CLIENT_SECRET`
- `AWS_COGNITO_ISSUER_URI`

Use the `mock` Spring profile for local simulation, or the `local` Spring profile when running with real Cognito values. The Cognito app client must allow:

- Callback URL: `http://localhost:8080/login/oauth2/code/cognito`
- Sign-out URL: `http://localhost:5173`
- Scopes: `openid`, `profile`, `email`

Never hardcode secrets. For a public Cognito client, use PKCE and update the Spring registration deliberately rather than silently falling back to an insecure flow.

## Development Commands

From `frontend/`:

```powershell
npm install
npm run dev
npm run build
```

From `backend/`:

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=local
mvn test
```

Java 17+ is required. Java 21 is known to be installed in the current environment. Maven must be installed separately if `mvn` is unavailable.

## Coding Guidance

- Keep backend endpoints under `/api` and preserve credentialed CORS for `http://localhost:5173`.
- Keep authentication behavior in `SecurityConfig`; keep response shaping in controllers.
- Do not expose access tokens or client secrets through API responses or frontend state.
- Preserve the frontend proxy routes in `frontend/vite.config.ts` when adding OAuth-related endpoints.
- Keep UI changes responsive on desktop and mobile and consistent with the existing warm, editorial visual style.
- Avoid unrelated changes to the legacy sibling projects in the parent workspace.

## Validation Expectations

For frontend changes, run `npm run build` from `frontend/`.

For backend changes, run `mvn test` from `backend/` when Maven is available. At minimum, verify that the application compiles and that authentication status and authenticated user behavior remain covered by tests.

This is a POC. Before production use, review session storage, cookie security, CSRF strategy, logout behavior, secrets management, redirect validation, observability, and deployment topology.
