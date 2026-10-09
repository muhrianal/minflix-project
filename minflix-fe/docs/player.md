# Player (HLS from the frontend)

`src/components/Player.tsx`, used by the Watch page with `masterPlaylistUrl` from the API.

## What hls.js does
1. `hls.loadSource(masterPlaylistUrl)` fetches `master.m3u8`. It lists the renditions (for minflix: 720p, 480p, 360p) with bandwidth and resolution.
2. `hls.attachMedia(video)` connects hls.js to the `<video>` element through Media Source Extensions (the video's `src` becomes a `blob:` URL).
3. It picks a starting rendition, fetches that rendition's `index.m3u8`, then its `.ts` segments one after another, and appends them to the media buffer.
4. While playing it measures download speed. In automatic mode it moves to a higher or lower rendition when bandwidth changes (adaptive bitrate). Segments of all renditions start on the same keyframes, so switching is seamless.

```
video.src = blob:...  <-- MSE buffer <-- hls.js --GET--> master.m3u8
                                              --GET--> 720p/index.m3u8
                                              --GET--> 720p/seg0.ts, seg1.ts ...   (or 480p/..., 360p/...)
```

## Quality dropdown
- On `Hls.Events.MANIFEST_PARSED`, `hls.levels` holds one entry per rendition. The dropdown shows **Auto** plus one option per level, labeled `${level.height}p`, sorted highest to lowest. Each option's value is the level's index in `hls.levels` (not its position in the list).
- **Auto** sets `hls.currentLevel = -1`: hls.js chooses the level (ABR).
- A level sets `hls.currentLevel = <index>`: switches now (buffered data is flushed) and stays on it.
- On `Hls.Events.LEVEL_SWITCHED` the label shows the level actually playing: `Auto (720p)` in auto mode, `480p` when fixed.
- Playback controls are the browser's native `<video controls>`.
- The Hls instance is destroyed when the component unmounts or the source changes.

## Native HLS fallback
If `Hls.isSupported()` is false (no MSE, e.g. older iOS Safari) and `video.canPlayType('application/vnd.apple.mpegurl')` is truthy, the player sets `video.src = masterPlaylistUrl` and lets the browser play HLS itself. The browser then chooses the quality, so the dropdown is hidden.

hls.js is checked first on purpose: recent Chrome (154) also reports native HLS support, and checking `canPlayType` first would remove the dropdown there (see decisions.md).

## Verified
With the public test stream `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` in Chrome 154 (temporary test route, removed afterwards): dropdown showed Auto, 1080p, 720p, 480p, 288p, 184p; auto played at "Auto (720p)"; choosing 480p switched the picture to 480 lines and the label to "480p"; back to Auto showed "Auto (1080p)". Safari/iOS native fallback not tested.
