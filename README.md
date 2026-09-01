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

The backend requires Java 17+ and Maven. Java 21 is compatible; Maven is not currently installed on this machine.
