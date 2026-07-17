# runway

runway is a self-hosted decision ledger for conservative running plans, workout results, activity imports, history, and stats.

It is not a GPS tracker. Watches and phone apps record activities; runway preserves the generated recommendation, the runner's current plan, actual work, and each explicit next-plan decision.

Current planning paths are an established distance baseline, the exact nine-week NHS Couch to 5K foundation (with or without a later race goal), and a two-week timed calibration. Future non-race workouts are editable and reversible; activity facts count immediately, while future plan changes require confirmation.

Imported records remain outside actual totals and training signals until the runner confirms them. Beginner-phase completion likewise shows the observed baseline for confirmation before any retained race goal can become a distance phase.

## Local Run

```sh
corepack pnpm install
cp .env.example .env
corepack pnpm db:start
corepack pnpm db:migrate
corepack pnpm dev
```

The development server binds to `0.0.0.0:4100` and is available locally at:

```text
http://localhost:4100/
```

To test from another device, set `ORIGIN` and `PUBLIC_APP_ORIGIN` to the address that device uses to
reach the development machine. Set `SITE_URL` to the same address only when running `verify:preview`.

Sample Gadgetbridge or Amazfit exports belong in `samples/`. Do not commit real GPX, FIT, or TCX files.

## Verification

Use focused checks for small changes and broader checks for product, auth, privacy, deployment, or browser-facing work.

```sh
corepack pnpm format:check
corepack pnpm verify:docs
corepack pnpm lint
corepack pnpm check
corepack pnpm test:unit
corepack pnpm test:e2e
corepack pnpm test:visual
corepack pnpm verify:migrations
corepack pnpm verify:compose
corepack pnpm verify:compose:production
corepack pnpm verify:dependencies
```

Browser suites allocate an ephemeral PostgreSQL database and available preview port for each run, so
functional and visual checks can run concurrently without sharing account or training state.

Visual snapshot updates are evidence that pixels changed, not proof that the experience improved. Inspect the browser and screenshot diffs before accepting them.

To verify the built application, start the production preview in one terminal:

```sh
corepack pnpm build
corepack pnpm preview
```

Then run the live checks from another terminal:

```sh
SITE_URL=http://localhost:4100 corepack pnpm verify:preview
```

## Canonical Docs

- [Product](docs/PRODUCT.md): product boundary, audience, value, and non-goals.
- [Design system](docs/DESIGN_SYSTEM.md): visual direction, interaction rules, copy rules, and visual testing.
- [Architecture](docs/ARCHITECTURE.md): stack, routes, runtime shape, data ownership, training logic, and imports.
- [Security](docs/SECURITY.md): privacy defaults, auth, threat model, GPX handling, Nextcloud sync, and audit expectations.
- [Deployment](docs/DEPLOYMENT.md): production environment, Authentik, SMTP, Nextcloud, Caddy, Cloudflare, and PWA checks.
- [Training sources](docs/TRAINING_SOURCES.md): source-backed training claims and product limits.

Contribution setup and review expectations are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Current Limits

- An HTTP preview is for product review. Passkeys, OIDC redirects, secure cookies, installability,
  and offline behavior need the real HTTPS origin.
- Installed Chromium-family PWAs can receive GPX files from the operating-system share sheet; shared files always enter the authenticated review inbox without changing the plan automatically.
- Supported Chromium PWAs can read an explicitly approved Gadgetbridge Auto GPX export directory on app open/focus; the bounded browser-local source handles one newest file per foreground check, never modifies the folder, and forgets the capability on sign-out.
- Password reset needs `MAIL_ENABLED=true` and SMTP configuration. Without SMTP, reset requests should fail safely without exposing account existence.
- Nextcloud sync expects a password-protected public folder share and a production `NEXTCLOUD_ALLOWED_ORIGINS` exact-origin allowlist.
- The current release stores aggregate GPX activity data. Route maps and route geometry display stay deferred until privacy controls exist.
- Training guidance is planning support, not medical advice or individualized coaching.

## License

Copyright © 2026 runway contributors.

runway is licensed under the [GNU Affero General Public License v3.0 only](LICENSE)
(`AGPL-3.0-only`). If you modify runway and make it available over a network, the license requires
you to offer the corresponding source code to its users. Set `PUBLIC_SOURCE_URL` to that source when
deploying a fork or modified version.
