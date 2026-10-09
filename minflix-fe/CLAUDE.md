# minflix-fe

Frontend for a mini Netflix (VOD): auth, upload, library, HLS player with quality selector. Two environments: `dev` (`npm run dev`) and `prod` (static files on S3 behind CloudFront, provisioned by `minflix-infra`).

The shared contract (`../docs/CONTRACT.md`, imported by the root `CLAUDE.md`) is the source of truth for API, environments, statuses and URLs. The backend is built separately and may not be running. Never change the contract unilaterally. If something is ambiguous, pick the simplest option and add one line to `docs/decisions.md`.

## Engineering rules (apply to every change)
- Minimal and straightforward. Do the simplest thing that satisfies the contract.
- No defensive patterns: no speculative null-checks, no blanket try/catch, no retry logic, no wrapper abstractions, no unused config.
- No features beyond the contract.
- Use libraries as intended; don't wrap them.
- Never claim something works without running it. Anything needing the real backend or AWS is "unverified".

## Stack (fixed)
React 18 + TypeScript + Vite, react-router-dom, hls.js. Plain `fetch` for JSON (small `api.ts` helper), `XMLHttpRequest` ONLY for the S3 upload (needed for progress events). Plain CSS (or Tailwind, pick one and keep it small). No component library. No global state library: React state + one AuthContext.

## Environments (Vite mode files, committed, no secrets)
- `.env.development` (used by `npm run dev`): `VITE_API_URL=http://localhost:8080`
- `.env.production` (used by `npm run build`): `VITE_API_URL=` (EMPTY on purpose)
- In `api.ts` build URLs as `` `${import.meta.env.VITE_API_URL}/api/...` `` with NO fallback default. In prod the value is empty, so calls are relative (`/api/...`) because CloudFront serves the FE and the API on one domain (see contract). Do not add a prod hostname anywhere in the FE code.
- Upload URLs (`uploadUrl`) and `masterPlaylistUrl` are absolute URLs from the API; use them as given in both environments.

## Structure
```
src/api.ts        fetch helper adding Bearer token; register, login, createVideo, completeVideo, listVideos, getVideo
src/auth.tsx      AuthContext: token in localStorage, login/logout, isAuthenticated
src/types.ts      Video, VideoStatus (match the contract exactly)
src/pages/        Login, Register, Library, Upload, Watch
src/components/   VideoCard, StatusBadge, Player, ProtectedRoute, Navbar
```

## Commands
```
npm install
npm run dev          # dev mode, http://localhost:5173
npm run build        # production mode -> dist/
npx tsc --noEmit
```
Prod deploy is done by the USER, not Claude (values come from `terraform output` in minflix-infra):
```
npm run build
aws s3 sync dist/ s3://<frontend_bucket> --delete
aws cloudfront create-invalidation --distribution-id <distribution_id> --paths "/*"
```
Temporary public HLS source for testing the player alone (remove afterwards):
`https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`

## Requirements

### Auth
- Login and Register pages (email + password). On success store the token and go to `/`. On any 401, clear the token and go to `/login`.
- Navbar: Library, Upload, Logout.
- Routes: `/login`, `/register`, `/` (Library), `/upload`, `/watch/:id`. All but login/register go through ProtectedRoute. (In prod, CloudFront rewrites unknown paths to `index.html`, so client-side routing works on refresh.)

### Library (/)
- `GET /api/videos`, responsive grid of VideoCards: title, status badge, duration as mm:ss when present, created date.
- READY cards link to `/watch/:id`. Non-READY are not clickable and show a badge: UPLOADING, PROCESSING (animated), FAILED (red).
- While any video is PROCESSING, re-fetch every 5 seconds; stop when none are.
- Empty state linking to Upload.

### Upload (/upload)
- Form: title (required) + file input `accept="video/*"`. Submit disabled until both are set.
- On submit:
  1. `POST /api/videos` with `{ title, filename: file.name, contentType: file.type }` -> `{ videoId, uploadUrl }`
  2. PUT the file to `uploadUrl` via XMLHttpRequest with Content-Type EXACTLY `file.type`. **Do NOT send the Authorization header to the upload URL** (it is a presigned URL; extra headers break the signature). Progress bar from `xhr.upload.onprogress` (percentage + MB uploaded).
  3. On 200, `POST /api/videos/{id}/complete`.
  4. Navigate to `/`.
- On failure show the error message on the page. No retries.

### Watch (/watch/:id)
- `GET /api/videos/{id}`. If not READY show a short message instead of the player.
- Player component:
  - `new Hls()`, `loadSource(masterPlaylistUrl)`, `attachMedia(video)`. If `video.canPlayType('application/vnd.apple.mpegurl')` (Safari), set `video.src` directly and hide the quality dropdown. Destroy the Hls instance on unmount.
  - On `Hls.Events.MANIFEST_PARSED`, build options from `hls.levels`: "Auto" plus one per level labeled by height (e.g. "720p"), highest to lowest.
  - "Auto" sets `hls.currentLevel = -1`. A level sets `hls.currentLevel` to that level index (immediate switch).
  - On `Hls.Events.LEVEL_SWITCHED`, show the playing quality, e.g. "Auto (720p)" while auto is on.
  - Native `<video controls>`. No custom controls.
- Show title and duration under the player.

### UI
Dark theme, simple, readable on mobile. CSS in one or two files.

## Documentation (write as you go, Markdown, in the repo)
- `README.md`: what it is, stack, prerequisites, setup, dev vs prod behavior, relation to the backend (link to the contract), manual test checklist.
- `docs/architecture.md`: pages/components tree; data flows for login, upload (explain why the file goes straight to storage via presigned URL and why no Authorization header is sent there) and watch, with ASCII diagrams. Show dev (separate origins) vs prod (one CloudFront origin) side by side.
- `docs/player.md`: HLS from the frontend point of view: what hls.js does (fetch master.m3u8, pick a rendition, fetch segments, adapt to bandwidth), how the dropdown maps to `hls.levels` / `currentLevel` (-1 = auto), the native-HLS (Safari) fallback.
- `docs/deploy.md`: the three prod deploy commands above, where each value comes from, and that the FE needs no prod config because the API base URL is empty.
- `docs/decisions.md`: every assumption or deviation, one line each.

## Working method
1. Output a short plan with the file list first.
2. Implement in order: (a) scaffold + env files + routing + auth, (b) Library, (c) Upload, (d) Player/Watch, (e) docs.
3. After each step run `npm run build` and `npx tsc --noEmit`.
4. If the backend isn't running, use a mock (Vite proxy or MSW) ONLY if needed to check pages, and remove it afterwards.
5. Never run AWS commands. Report what you verified and what you did not (prod deploy and relative-URL behavior are unverified until the user deploys).
