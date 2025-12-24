# Contributing to Genkit for Java

Thank you for contributing to Genkit for Java! This document provides guidelines for contributing.

## Conventional Commits

This project uses [Conventional Commits](https://www.conventionalcommits.org/) for automated versioning and changelog generation.

### Commit Message Format

Each commit message must follow this format:

```
<type>(<scope>): <subject>

[optional body]

[optional footer(s)]
```

### Types

| Type       | Description                                           | Version Bump |
|------------|-------------------------------------------------------|--------------|
| `feat`     | A new feature                                         | Minor        |
| `fix`      | A bug fix                                             | Patch        |
| `docs`     | Documentation only changes                            | None         |
| `style`    | Code style changes (formatting, semicolons, etc.)     | None         |
| `refactor` | Code change that neither fixes a bug nor adds feature | None         |
| `perf`     | Performance improvements                              | Patch        |
| `test`     | Adding or updating tests                              | None         |
| `build`    | Build system or external dependency changes           | None         |
| `ci`       | CI configuration changes                              | None         |
| `chore`    | Other changes (maintenance, dependencies)             | None         |
| `revert`   | Revert a previous commit                              | Varies       |

### Breaking Changes

To indicate a breaking change, add `!` after the type/scope or add `BREAKING CHANGE:` in the footer:

```
feat!: remove deprecated API endpoints

BREAKING CHANGE: The /v1/old endpoint has been removed.
```

Breaking changes trigger a **major** version bump.

### Examples

```bash
# Feature
feat(ai): add streaming support for generate

# Bug fix
fix(core): resolve null pointer in flow execution

# Documentation
docs: update README with new examples

# Breaking change
feat(api)!: change response format for generate endpoint

BREAKING CHANGE: Response now returns a structured object instead of raw text.
```

### Scopes

Common scopes for this project:

- `core` - Core module changes
- `ai` - AI module changes
- `genkit` - Main genkit module
- `google-genai` - Google GenAI plugin
- `openai` - OpenAI plugin
- `jetty` - Jetty server plugin
- `localvec` - Local vector plugin
- `mcp` - MCP plugin
- `samples` - Sample applications
- `deps` - Dependency updates

## Development Setup

1. Clone the repository
2. Ensure you have JDK 21+ installed
3. Build the project: `mvn clean install`

## Pull Request Process

1. Create a feature branch from `main`
2. Make your changes following the conventional commits format
3. Ensure all tests pass: `mvn test`
4. Check code formatting: `mvn spotless:check`
5. Submit a pull request

## Release Process

Releases are automated using [Release Please](https://github.com/googleapis/release-please). When commits are pushed to `main`:

1. Release Please analyzes conventional commits
2. Creates/updates a release PR with version bump and changelog
3. When the release PR is merged, it:
   - Creates a GitHub release with the new version
   - Publishes packages to GitHub Packages

### Version Bumps

| Commit Type | Version Change |
|-------------|----------------|
| `fix:`      | Patch (0.0.X)  |
| `feat:`     | Minor (0.X.0)  |
| `BREAKING CHANGE` | Major (X.0.0) |

## Code Style

This project uses:
- **Spotless** with Eclipse JDT formatter
- **Checkstyle** with Google checks

Run formatting: `mvn spotless:apply`
Check formatting: `mvn spotless:check`
