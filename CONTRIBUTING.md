# Contributing to Porta

Thanks for your interest in contributing! This document covers the
workflow, conventions, and policies you need to know.

## Getting started

See [Quick start](README.md#quick-start) in the README for prerequisites
and setup instructions.

## Branch strategy

We use a **Git Flow** variant with two long-lived branches and
short-lived topic branches.

```
main     ←  releases only (tagged)
develop  ←  integration branch (PRs land here)
 └── feat/42/add-filter   (your working branch)
```

| Branch      | Purpose                       | Who merges        |
| ----------- | ----------------------------- | ----------------- |
| `main`      | Stable releases. Always tagged. | Maintainers only |
| `develop`   | Next-release integration      | Anyone via PR     |
| topic (`feat/…`, `fix/…`, …) | Individual changes | Author     |

### Branch naming

Format: `{type}/{issue_no}/{short-desc}`

| Type       | Example                          |
| ---------- | -------------------------------- |
| `feat`     | `feat/42/add-filter`             |
| `fix`      | `fix/108/stale-step-after-resume`|
| `refactor` | `refactor/65/split-proxy-module` |
| `docs`     | `docs/71/cloudflare-setup`       |
| `chore`    | `chore/90/bump-deps`             |

### Workflow

1. Create a branch from `develop`: `git checkout develop && git checkout -b feat/42/add-filter`
2. Make your changes, commit, push.
3. Open a Pull Request against **`develop`**.
4. CI must pass. At least one maintainer review is required.
5. Squash-merge into `develop`.

### Releases

When `develop` is ready for release, a maintainer merges `develop` into
`main`, tags the merge commit (e.g. `v0.2.0`), and fast-forward merges
`main` back into `develop`.

```bash
git checkout main && git merge --no-ff develop
git tag v0.2.0
git push origin main --tags
git checkout develop && git merge main
git push origin develop
```

> **Do not push directly to `main` or `develop`.** All changes go through PRs.
> Direct commits to `main` are limited to release merges by maintainers.

## Versioning

Porta follows [Semantic Versioning](https://semver.org/):

| Bump    | When                                                   |
| ------- | ------------------------------------------------------ |
| `MAJOR` | Breaking changes to proxy API, config format, or CLI   |
| `MINOR` | New features, new env vars, non-breaking proxy changes |
| `PATCH` | Bug fixes, documentation, dependency updates           |

Releases are tagged on `main` (e.g. `v0.1.0`). The root `package.json`
and workspace packages share the same version number.

> **Pre-1.0**: While the version is `0.x.y`, minor bumps may contain
> breaking changes. This is standard SemVer practice.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <summary>

[optional body]
```

**Types**: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`
**Scopes**: `proxy`, `web`, `telegram`, or omit for repo-wide changes.

Examples:

```
feat(proxy): add workspace filtering to conversation list
fix(web): prevent stale steps after tab resume
docs: update Cloudflare tunnel setup instructions
```

## Pull requests

- Keep PRs focused — one logical change per PR.
- Include a brief description of _why_, not just _what_.
- Link the related Issue: add `Closes #123` in the PR description so
  the Issue is auto-closed on merge.
- Update or add tests when changing behavior.
- Make sure `pnpm build && pnpm test && pnpm lint` all pass locally
  before pushing.

## Issues

### Opening an issue

When you open an Issue, GitHub will present you with a template
chooser. Pick the one that fits:

| Template            | Use when …                                    |
| ------------------- | --------------------------------------------- |
| **Bug Report**      | Something is broken or behaves unexpectedly   |
| **Feature Request** | You want to propose a new feature or change   |
| **Blank issue**     | Anything else (question, discussion, etc.)    |

- **Security vulnerabilities**: See [SECURITY.md](SECURITY.md). Do
  **not** open a public Issue.

### Labels

Maintainers apply labels during triage. The key labels are:

| Label              | Meaning                                        |
| ------------------ | ---------------------------------------------- |
| `bug`              | Confirmed defect                               |
| `enhancement`      | Accepted feature request                       |
| `good first issue` | Suitable for new contributors                  |
| `triage`           | Awaiting maintainer review                     |
| `priority/high`    | Must be fixed before the next release          |
| `priority/low`     | Nice to have; no timeline pressure             |
| `wontfix`          | Declined — with explanation                    |

### Triage flow

1. A new Issue is automatically labelled `triage`.
2. A maintainer reviews it, removes `triage`, and applies the
   appropriate labels (`bug` / `enhancement`, priority, etc.).
3. If more information is needed the maintainer adds a comment and
   applies `needs-info`. The reporter has **14 days** to respond before
   the Issue may be closed.

### Stale issues

Issues with no activity for **60 days** are marked `stale`. After a
further **14 days** of inactivity they are closed automatically. Re-open
if the issue is still relevant.

### Issue → Branch → PR linkage

Every code change should trace back to an Issue:

```
Issue #42  →  branch feat/42/add-filter  →  PR "Closes #42"
```

This keeps the project history navigable and avoids orphan work.

## Code style

- TypeScript strict mode is enabled for `proxy`, `web`, and `telegram`.
- ESLint is configured for the `web` package (`pnpm lint`). The `proxy`
  and `telegram` packages do not have ESLint; they rely on TypeScript
  strict mode for static checks.
- No additional formatter is enforced — match the style of surrounding
  code.

## License

By contributing, you agree that your contributions will be licensed
under the [MIT License](LICENSE).
