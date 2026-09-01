# AWS Java SSO POC

Proof of concept for AWS single sign-on using Java Spring Security and an interactive Vite frontend.

## Architecture

- AWS Cognito provides the OIDC identity provider and hosted sign-in experience.
- Spring Boot and Spring Security OAuth2 Client handle the authorization-code flow and authenticated session.
- Vite, React, and TypeScript call `/api/auth/status` and `/api/me` with credentials included.

## Development prerequisites

### Required

1. **Java JDK 21**
	- [Amazon Corretto 21 downloads](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
	- Choose the installer for your operating system and CPU architecture. Apple Silicon users should select `macOS aarch64`.
2. **Apache Maven**
	- [Download Maven](https://maven.apache.org/download.cgi)
	- Download the binary archive or installer for your operating system. Maven runs using Java.
3. **Node.js LTS**
	- [Node.js downloads](https://nodejs.org/en/download)
	- Choose the `LTS` release and the installer matching your operating system and CPU architecture.
4. **Visual Studio Code**
	- [VS Code downloads](https://code.visualstudio.com/download)
	- Choose the build matching your operating system and CPU architecture.

### Recommended VS Code extensions

- [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack)
- [Spring Boot Extension Pack](https://marketplace.visualstudio.com/items?itemName=vmware.vscode-boot-dev-pack)

### Optional

- **Git:** [git-scm.com downloads](https://git-scm.com/downloads)
- **AWS CLI:** [AWS CLI installation guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- **AWS account/Cognito:** Only required for the real AWS SSO flow. The local mock flow does not require AWS.

## Configure Cognito

Create a Cognito app client with callback URL `http://localhost:8080/login/oauth2/code/cognito` and sign-out URL `http://localhost:5173`. Enable `openid`, `profile`, and `email` scopes.

Set these variables before starting the backend:

```powershell
$env:AWS_COGNITO_CLIENT_ID = "your-app-client-id"
$env:AWS_COGNITO_CLIENT_SECRET = "your-app-client-secret"
$env:AWS_COGNITO_ISSUER_URI = "https://cognito-idp.us-east-1.amazonaws.com/your-user-pool-id"
```

## Run with the local simulator

No AWS account or Cognito values are required for the simulated flow:

From `backend/`: `mvn spring-boot:run -Dspring-boot.run.profiles=mock`

From `frontend/`: `npm run dev`

Open `http://localhost:5173` and choose **Sign in as demo user**. The backend creates a session containing a demo identity, so you can exercise the frontend and protected `/api/me` endpoint locally.

## Run with Cognito

From `backend/`: `mvn spring-boot:run -Dspring-boot.run.profiles=local`

From `frontend/`: `npm install` then `npm run dev`

Open `http://localhost:5173`. The Vite dev server proxies API and OAuth routes to port 8080.

## How to test

### Browser test with the local simulator

1. Start the backend with the `mock` profile.
2. Start the frontend with Vite.
3. Open `http://localhost:5173`.
4. Confirm the initial state shows `Awaiting sign-in` and `Local simulator`.
5. Select **Sign in as demo user**.
6. Confirm the page shows `Authenticated`, `Demo User`, `demo@example.com`, and `mock-user-001`.
7. Select **Sign out** and confirm the page returns to the signed-out state.

### API test

Check backend health:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

Test the mock session from PowerShell:

```powershell
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest http://localhost:8080/api/auth/mock-login -Method POST -WebSession $session
Invoke-RestMethod http://localhost:8080/api/me -WebSession $session
```

Test the mock session from Bash:

```bash
curl -i -c cookies.txt -X POST http://localhost:8080/api/auth/mock-login
curl -i -b cookies.txt http://localhost:8080/api/me
curl -i http://localhost:8080/api/me
rm cookies.txt
```

The first request should return `200` and save the session cookie. The second request should return `200` with the demo identity. The third request should return `401` or `403` because it does not include the session cookie.

The protected endpoint should return the demo identity. A request to `/api/me` without the session cookie should be rejected with HTTP `401` or `403`.

### Automated tests

From `backend/`:

```bash
mvn test
```

The suite includes controller unit tests and Spring `MockMvc` tests for authentication status, identity claims, and fallback values. From `frontend/`:

```bash
npm run build
```

The production build validates TypeScript and Vite bundling.

### Capture screenshots

Capture these two states for the POC:

1. **Signed out:** the screen showing `Local simulator` and `Awaiting sign-in`.
2. **Signed in:** the identity section showing `Demo User`, `demo@example.com`, and the authenticated session.

Use your operating system screenshot shortcut or the browser developer tools device toolbar to capture desktop and mobile layouts. Store committed images under `docs/screenshots/` and reference them like this:

```markdown
![Signed-out state](docs/screenshots/signed-out.png)
![Signed-in state](docs/screenshots/signed-in.png)
```

Do not include cookies, access tokens, client secrets, or real user information in screenshots.

The backend requires Java 17+ and Maven. Java 21 is compatible.
