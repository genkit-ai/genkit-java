# Security Policy

## Supported Versions

We release patches for security vulnerabilities. Currently supported versions:

| Version | Supported          |
| ------- | ------------------ |
| 1.0.0   | :white_check_mark: |

## Reporting a Vulnerability

We take the security of Genkit Java seriously. If you believe you have found a security vulnerability, please report it to us as described below.

### Please Do Not:

- Open a public GitHub issue for security vulnerabilities
- Disclose the vulnerability publicly before it has been addressed

### Please Do:

**Report security vulnerabilities by emailing:**
- Email: [security contact - update this with your email]

**Include the following information:**
- Type of vulnerability (e.g., SQL injection, XSS, remote code execution)
- Full paths of source file(s) related to the vulnerability
- The location of the affected source code (tag/branch/commit or direct URL)
- Any special configuration required to reproduce the issue
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact of the issue, including how an attacker might exploit it

### What to Expect:

1. **Acknowledgment**: We will acknowledge receipt of your vulnerability report within 48 hours.

2. **Communication**: We will keep you informed about the progress of fixing the vulnerability.

3. **Validation**: We will confirm the vulnerability and determine its severity.

4. **Fix**: We will work on a fix and prepare a security advisory.

5. **Release**: Once the fix is ready, we will:
   - Release a patched version
   - Publish a security advisory
   - Credit you for the discovery (unless you prefer to remain anonymous)

### Timeline:

- Initial response: Within 48 hours
- Status update: Within 7 days
- Fix timeline: Depends on severity and complexity

### Security Update Process:

Security updates will be released as:
- Patch releases for the current major version
- Security advisories published on GitHub
- Announcements in the project's communication channels

## Security Best Practices

When using Genkit Java, we recommend:

1. **Keep Dependencies Updated**: Regularly update to the latest version to receive security patches
2. **Secure API Keys**: Never commit API keys or credentials to version control
3. **Use Environment Variables**: Store sensitive configuration in environment variables
4. **Validate Input**: Always validate and sanitize user input
5. **Follow Principle of Least Privilege**: Grant only necessary permissions
6. **Enable Security Features**: Use security features provided by the framework and plugins

## Known Security Considerations

### API Key Management
- Store API keys securely using environment variables or secret management services
- Rotate API keys regularly
- Never log or expose API keys in error messages

### Data Privacy
- Be mindful of sensitive data in prompts and responses
- Implement appropriate data retention policies
- Consider data residency requirements for your use case

### Dependency Security
- We use Dependabot to monitor dependencies
- Security updates are prioritized and released promptly
- Review the CHANGELOG for security-related updates

## Contact

For security-related questions or concerns, contact:
- Email: [security contact - update this with your email]
- GitHub Security Advisories: https://github.com/genkit-ai/genkit-java/security/advisories

Thank you for helping keep Genkit Java and its users safe!
