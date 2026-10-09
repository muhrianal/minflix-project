# Decisions and assumptions

One line each. The contract (`../../docs/CONTRACT.md`) wins over anything here.

- Versions pinned exactly: React 18.3.1, react-router-dom 7.18.4, hls.js 1.7.3, Vite 7.3.7, @vitejs/plugin-react 5.2.0, TypeScript 5.9.3 (Vite 8 / TS 7 skipped: newer majors than needed).
- react-router-dom 7 instead of 6: every 6.x release has moderate advisories (GHSA-wrjc-x8rr-h8h6 open redirect); 7.18.4 fixes them, supports React 18 and keeps the same `react-router-dom` API.
- Player picks hls.js first (`Hls.isSupported()`) and uses native HLS only as a fallback. CLAUDE.md said "if `canPlayType('application/vnd.apple.mpegurl')` use native", but Chrome 154 answers `"maybe"` to that, which would hide the quality dropdown in Chrome. Effect: desktop Safari also gets hls.js + dropdown; native playback remains for browsers without MSE (e.g. older iOS Safari).
- 401 handling: only for calls that carried a token. The token is cleared and the page does a full navigation to `/login` (resets all state). Auth endpoints never send the token, so a wrong password shows "Invalid credentials" instead of redirecting.
- Error messages come from the API's `{message}` body. A non-JSON error body (e.g. a proxy page) is not handled specially.
- Upload: success means HTTP 200 from storage, as in the contract. Network errors show "Upload failed (network error)".
- The quality label shows the level hls.js reports in `LEVEL_SWITCHED`; right after a manual switch the picture can lag a few seconds while the already-buffered segments play.
- The Library also shows the status badge on READY cards (the spec lists "status badge" for every card); only non-READY ones are unclickable.
- Small `src/format.ts` (mm:ss) added, shared by VideoCard and Watch.
- `npm run build` runs `tsc --noEmit` first, so a type error fails the build.
- Vite's ">500 kB chunk" warning is accepted: hls.js is most of the bundle; no code splitting for a 5-page app.
- Login and Register are separate small pages with near-identical forms (CLAUDE.md structure), not a shared form component.
