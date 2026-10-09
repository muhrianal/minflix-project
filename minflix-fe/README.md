# minflix-fe

The web app of [minflix](../README.md), a small video-on-demand platform. Users sign up, upload a video with a live progress bar, watch the library update while it is transcoded, and play it with an adaptive HLS player that has a quality selector.

Built AI-first with Claude Code from a written spec ([`CLAUDE.md`](CLAUDE.md)) and the shared [`contract`](../docs/CONTRACT.md); see [How it was built](../README.md#how-it-was-built-ai-first-development).

## Stack

| | |
|---|---|
| UI | React 18 + TypeScript, Vite 7 |
| Routing | react-router-dom 7 |
| Playback | hls.js (Media Source Extensions), native HLS as a fallback |
| Data | `fetch` with a small helper; `XMLHttpRequest` only for the upload (progress events) |
| State | React state + one `AuthContext`; no state library |
| Styling | One plain CSS file, dark theme, responsive grid |

## Pages

| Route | What it does |
|---|---|
| `/login`, `/register` | Email + password. The JWT is stored in `localStorage`. |
| `/` Library | Grid of videos with a status badge (`UPLOADING`, pulsing `PROCESSING`, red `FAILED`, `READY`), duration and date. Refreshes every 5 s **only while something is processing**. Empty state links to Upload. |
| `/upload` | Title + file → create → direct upload with % and MB progress → complete → back to the library |
| `/watch/:id` | Player, title and duration; a short message if the video is not ready |

Every route except login/register goes through `ProtectedRoute`. A `401` on any authenticated call clears the token and returns to `/login`.

## How the upload works

```
Upload page ──POST /api/videos {title, filename, contentType}──► API
            ◄── {videoId, uploadUrl} (presigned, 15 min) ────────
            ──PUT uploadUrl  Content-Type: file.type  (no Authorization)──► S3 / MinIO
               xhr.upload.onprogress → progress bar
            ──POST /api/videos/{id}/complete──► API  → transcoding starts
```

- The file goes **straight to object storage**, so the API never handles video bytes.
- **No `Authorization` header** on the PUT. The URL already carries its signature, and an extra auth header would make storage reject it.
- `Content-Type` must exactly match the value sent when creating the video, because it is part of the signature.

## How the player works

- hls.js loads `master.m3u8`, picks a rendition, then fetches that rendition's playlist and its 6-second segments. It moves between renditions as bandwidth changes.
- On `MANIFEST_PARSED` the dropdown is built from `hls.levels`: **Auto** plus one entry per height (720p, 480p, 360p), highest first.
- **Auto** sets `hls.currentLevel = -1` (adaptive). Choosing a level pins it immediately.
- On `LEVEL_SWITCHED` the label shows what is actually playing, e.g. **"Auto (720p)"**.
- Native HLS (`video.src = url`) is used only when hls.js is unsupported (e.g. older iOS Safari); the browser then picks the quality and the dropdown is hidden.
- The browser's own `<video controls>` handle playback; there are no custom controls.

Details: [docs/player.md](docs/player.md).

## dev vs prod

| | dev (`npm run dev`) | prod (`npm run build`) |
|---|---|---|
| Env file | `.env.development` | `.env.production` |
| `VITE_API_URL` | `http://localhost:8080` | *(empty)* |
| API calls | `http://localhost:8080/api/...` (CORS allowed for :5173) | `/api/...` relative, same CloudFront domain |
| Upload URL | MinIO, from the API | S3 presigned URL, from the API |
| Video | `http://localhost:9000/minflix/hls/...` | `https://<cf-domain>/hls/...` |

The production build contains **no hostnames**. CloudFront serves the app, the API and the video under one domain, so the same build works in any account. Deploy steps: [docs/deploy.md](docs/deploy.md).

## Running locally

Prerequisites: Node 20+ (tested on Node 24). For real use, start the backend stack first (`docker compose up --build` in [minflix-be](../minflix-be/README.md)).

```bash
npm install
npm run dev          # http://localhost:5173
npm run build        # type-check + production build → dist/
npx tsc --noEmit     # type-check only
```

## Project structure

```
src/
├── main.tsx          routes, AuthProvider
├── api.ts            fetch helper (Bearer token, 401 handling) + XHR upload with progress
├── auth.tsx          AuthContext (token in localStorage)
├── types.ts          Video, VideoStatus (exactly the contract shape)
├── format.ts         mm:ss
├── index.css
├── pages/            Login, Register, Library, Upload, Watch
└── components/       Navbar, ProtectedRoute, VideoCard, StatusBadge, Player
```

## What has been verified

- `npx tsc --noEmit` and `npm run build` pass. The build fails on any type error.
- The production bundle calls `/api/...` with relative URLs and contains no `localhost` URL.
- In dev mode the API base URL resolves to `http://localhost:8080`.
- **Player in Chrome 154** against a public multi-bitrate HLS test stream:
  - The dropdown listed Auto plus every rendition, highest first.
  - Auto played and showed "Auto (720p)".
  - Picking 480p switched the picture to 480 lines and the label to "480p".
  - Switching back to Auto resumed adaptive playback.
- `npm audit`: 0 vulnerabilities.

**Not verified yet:** the pages against the live backend (use the checklist below), Safari/iOS native playback, and production through CloudFront.

## Manual test checklist

With the backend dev stack running:
- [ ] `/` without a token redirects to `/login`.
- [ ] Register a new email → lands on `/` (empty state with a link to Upload).
- [ ] Register the same email again → "Email already registered".
- [ ] Log out, log in with a wrong password → "Invalid credentials"; with the right one → `/`.
- [ ] Upload: button disabled until title and file are set; progress shows % and MB; then redirected to `/`.
- [ ] Library shows the new video as PROCESSING (pulsing), refreshes every 5 s, becomes READY with mm:ss; polling then stops (no more `/api/videos` requests in DevTools).
- [ ] READY cards open `/watch/:id`; other cards are not clickable.
- [ ] Watch: plays; dropdown shows Auto, 720p, 480p, 360p; label "Auto (720p)"; picking 360p switches and shows "360p"; Auto returns to adaptive.
- [ ] In DevTools, the PUT to the upload URL has no `Authorization` header.
- [ ] A second user sees the first user's READY videos, but not their PROCESSING/FAILED ones.
- [ ] Put a garbage token in localStorage and reload `/` → back on `/login`.
- [ ] Refreshing on `/watch/:id` still works.
- [ ] Mobile width: one-column grid, navbar and player fit.

## Nice to have

- Playwright end-to-end tests, including real playback and quality switching
- Resumable / multipart uploads for large files and flaky connections
- Status pushed by the server (SSE/WebSocket) instead of polling
- Poster thumbnails in the library, seek-bar previews, subtitles and audio-track selection
- Resume from last position
- Playback QoE metrics: startup time, rebuffering, bitrate switches
- Token refresh instead of re-login after 24 h

## Documentation

| Doc | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Component tree; login, upload and watch flows; dev vs prod origins |
| [docs/player.md](docs/player.md) | What hls.js does, the quality dropdown, the native fallback |
| [docs/deploy.md](docs/deploy.md) | Build, S3 sync, CloudFront invalidation |
| [docs/decisions.md](docs/decisions.md) | Every assumption and deviation, one line each |
