# Contributing to runway

runway is a self-hosted decision ledger for self-coached runners. Changes should strengthen its core loop: show a conservative recommendation, preserve user edits, record actual work, explain the difference, and leave the next plan decision with the runner.

Read these before changing product behavior:

- [Product direction](docs/PRODUCT.md)
- [Design system](docs/DESIGN_SYSTEM.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Security and privacy](docs/SECURITY.md)
- [Training sources](docs/TRAINING_SOURCES.md)

## Development setup

runway requires Node.js 24, Corepack, pnpm 10.32, and Docker with Compose.

```sh
corepack pnpm install
cp .env.example .env
corepack pnpm db:start
corepack pnpm db:migrate
corepack pnpm dev
```

The default development URL is `http://localhost:4100/`. The example environment is safe for local
development; replace its placeholders only as needed. Never commit `.env` or real credentials.

## Change expectations

- Keep recommendations editable and consequences explicit. Do not add automatic plan mutations.
- Treat rest and recovery as planned work, not empty space.
- Keep imported activities in Review until the runner confirms them.
- Back new training rules with reliable sources and record the evidence in `docs/TRAINING_SOURCES.md`.
- Do not add medical claims or imply that planner thresholds are health advice.
- Keep authenticated data user-scoped and route, schedule, pain, pace, heart-rate, and import data private.
- Never commit real GPX, FIT, or TCX files, credentials, private URLs, or machine-specific paths.
- Keep queries bounded and migrations explicit.
- Update documentation and tests with behavior changes.

## Verification

Run focused checks while developing. Before proposing a shared product, auth, privacy, deployment, or UI change, run the relevant broader checks:

```sh
corepack pnpm verify:docs
corepack pnpm verify
corepack pnpm verify:migrations
corepack pnpm verify:compose:production
corepack pnpm test:e2e
corepack pnpm test:visual
```

Use `corepack pnpm verify:full` for a complete release-oriented pass, including the production container build. Browser-facing changes should also be inspected interactively at mobile and desktop sizes; snapshot updates alone are not acceptance evidence.

## Security reports

Do not put vulnerabilities, credentials, private activity data, or reproduction files containing
personal data in a public issue. Use the repository's
[private vulnerability-reporting form](https://github.com/deftmartian/runway/security/advisories/new).
If that form is unavailable, open a public issue containing only a request for a private contact
channel. See [Security](docs/SECURITY.md) for the full reporting policy and trust boundaries.

## License

By contributing, you agree that your contribution is licensed under the repository's [GNU Affero General Public License v3.0 only](LICENSE).
