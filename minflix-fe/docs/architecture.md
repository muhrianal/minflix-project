# Architecture

## Pages and components
```
main.tsx
└── AuthProvider (auth.tsx: token in localStorage)
    └── BrowserRouter
        ├── /login       Login
        ├── /register    Register
        └── ProtectedRoute (no token -> /login; renders Navbar + page)
            ├── /            Library  -> VideoCard -> StatusBadge
            ├── /upload      Upload
            └── /watch/:id   Watch    -> Player (hls.js)
api.ts   fetch helper (adds Bearer token) + uploadFile (XHR)
types.ts Video, VideoStatus (contract shapes)
```

## dev vs prod
```
dev (separate origins)                           prod (one CloudFront origin)

Browser @ http://localhost:5173 (Vite)           Browser @ https://<cf-domain>
  |-- /api/...  -> http://localhost:8080 (CORS)    |-- /        -> CloudFront -> S3 (FE files)
  |-- PUT raw   -> http://localhost:9000 (MinIO)   |-- /api/... -> CloudFront -> ALB -> api   (relative URL)
  |-- HLS       -> http://localhost:9000/minflix/hls/...  |-- /hls/... -> CloudFront -> S3
                                                   |-- PUT raw  -> https://<bucket>.s3...amazonaws.com (presigned)
VITE_API_URL=http://localhost:8080               VITE_API_URL=   (empty)
```

## Login / register
```
Login page --POST /api/auth/login {email,password}--> API
           <-- 200 {token} --------------------------
store token in localStorage, AuthContext -> isAuthenticated, navigate("/")
Later: any authenticated call answering 401 -> clear token, go to /login
```

## Upload
```
Upload page                         API                         Storage (MinIO / S3)
  |--POST /api/videos {title,filename,contentType}-->|
  |<-- 201 {videoId, uploadUrl} ---------------------|
  |--PUT uploadUrl  (Content-Type: file.type, NO Authorization) ------------>|
  |   xhr.upload.onprogress -> progress bar (%, MB)                           |
  |<-- 200 ------------------------------------------------------------------|
  |--POST /api/videos/{id}/complete -->|  -> PROCESSING, transcode job queued
  |<-- 200 Video ----------------------|
navigate("/")  -> Library polls every 5 s while anything is PROCESSING
```
- **Why straight to storage:** video files are large. A presigned URL lets the browser send the bytes directly to S3/MinIO, so the API never holds them in memory, never times out on long uploads, and needs no extra bandwidth. The browser never sees storage credentials; the URL itself carries a time-limited signature (15 min) for exactly one key and one Content-Type.
- **Why no Authorization header there:** the storage server authenticates the request with the signature in the URL query (SigV4). Our JWT means nothing to it, and an extra `Authorization` header makes S3 treat the request as header-signed, so the signature check fails (403). `Content-Type` must equal the value the URL was signed with for the same reason.
- **Why XHR:** `fetch` has no upload progress events.

## Watch
```
Watch page --GET /api/videos/{id}--> API
           <-- Video {status, masterPlaylistUrl, durationSec}
status != READY -> short message
READY -> Player(masterPlaylistUrl) --GET master.m3u8, index.m3u8, seg*.ts--> MinIO (dev) / CloudFront (prod)
```
HLS requests carry no token: `hls/` is public (MinIO anonymous read in dev, CloudFront in prod). See player.md.
