# Dependency & Security Rules

## Installing Dependencies — ALWAYS ASK FIRST

- NEVER run `npm install`, `yarn add`, `pip install`, `brew install`, `pod install`, or any package manager install command without asking the user first
- Before suggesting a dependency, state:
  1. **What** package and version
  2. **Why** it's needed (what problem it solves)
  3. **Alternatives** — can this be done without a new dependency?
  4. **Size/impact** — bundle size, transitive dependencies
- Prefer standard library / platform APIs over third-party packages
- Prefer well-maintained packages with active contributors and no known vulnerabilities

## Security Checks on Dependencies

Before recommending ANY external package:
- Check for known vulnerabilities (CVEs)
- Verify the package is actively maintained (last publish < 6 months)
- Check download counts — avoid obscure packages with < 1K weekly downloads
- Verify the package name is correct (typosquatting is common)
- Check license compatibility (MIT, Apache 2.0 preferred)

## Code Security Rules

- NEVER log, print, or expose secrets, tokens, API keys, or credentials
- NEVER commit `.env` files, API keys, or service account JSONs
- Validate ALL external input (user input, API responses, URL params)
- Use parameterized queries — never string-concatenate SQL
- Sanitize anything a provider returns before it reaches a WebView or an HTML parser —
  extension code is third-party and its output is untrusted input
- Use HTTPS for all external requests
- No dynamic code execution from untrusted sources
- Kotlin: use `runCatching` at boundaries, validate intent data

## Gradle/Build Dependencies

- Adding to `gradle/libs.versions.toml` requires user approval
- Pin exact versions — no floating ranges like `^` or `~` without user consent
