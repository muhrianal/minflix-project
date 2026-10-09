# minflix-be

Backend for a mini Netflix (VOD). Spring Boot API + FFmpeg transcode worker. Two environments: `dev` (docker compose) and `prod` (AWS, provisioned by the separate `minflix-infra` repo).

The shared contract (`../docs/CONTRACT.md`, imported by the root `CLAUDE.md`) is the source of truth for API, environments, storage layout and the prod env-var interface. Never change it unilaterally. If something is ambiguous, pick the simplest option and add one line to `docs/decisions.md`.

## Engineering rules (apply to every change)
- Minimal and straightforward. Do the simplest thing that satisfies the contract.
- No defensive patterns: no speculative null-checks, no blanket try/catch, no retry frameworks, no custom exception hierarchies, no single-implementation interfaces, no feature flags, no unused config, no property defaults in `application-prod.yml`.
- Validate only what the contract requires.
- No features beyond the contract (no thumbnails, search, comments, admin, DRM).
- Use libraries as intended; don't wrap them.
- Never claim something works without running it. `prod` cannot be run locally: list it as unverified.

## Stack (fixed)
Java 17, Spring Boot 3.2+ (needed for SSL bundles), Maven. Spring Web, Spring Security (stateless JWT, jjwt, BCrypt), Spring Data MongoDB, Spring AMQP (RabbitMQ), Spring Boot Actuator, AWS SDK v2 (S3 + S3Presigner), Lombok allowed. FFmpeg/ffprobe via `ProcessBuilder` (no wrapper lib). Docker + docker-compose (dev only).

## Environments and profiles
- Environment profile: `dev` or `prod`. Config lives in exactly TWO files: `src/main/resources/application-dev.yml` and `application-prod.yml`. There is NO `application.yml`.
- Mode profile: `worker` turns a process into the transcode worker (`TranscodeListener`/`Transcoder` are `@Profile("worker")`). Active profiles look like `dev`, `dev,worker`, `prod`, `prod,worker`.
- A worker process has no web server: set the env var `SPRING_MAIN_WEB_APPLICATION_TYPE=none` (compose in dev, task definition in prod). Do not add a third yml for this.
- The `Queue` bean (`transcode`, durable) is declared without a profile so both api and worker declare it.

### application-dev.yml (hardcoded local values; docker compose overrides hostnames through env vars)
```
spring.data.mongodb.uri: mongodb://localhost:27017/minflix
spring.data.mongodb.auto-index-creation: true      # needed for the unique email index
spring.rabbitmq: host localhost, port 5672, username guest, password guest
storage.bucket: minflix
storage.region: us-east-1
storage.endpoint: http://localhost:9000            # used by api/worker (compose overrides to http://minio:9000)
storage.public-endpoint: http://localhost:9000     # used by the presigner, must be browser-reachable
storage.access-key / secret-key: minioadmin
storage.playlist-base-url: http://localhost:9000/minflix
jwt.secret: <dev-only string, 32+ bytes>
app.cors-allowed-origin: http://localhost:5173
management.endpoints.web.exposure.include: health
```

### application-prod.yml (every value from an env var, NO defaults; names are in the contract)
```
spring.data.mongodb.uri: ${MONGODB_URI}
spring.data.mongodb.auto-index-creation: true
spring.ssl.bundle.pem.docdb.truststore.certificate: classpath:certs/global-bundle.pem
spring.data.mongodb.ssl.bundle: docdb              # DocumentDB requires TLS; verify these property names against the Boot version in use
spring.rabbitmq: host ${RABBITMQ_HOST}, port 5671, ssl.enabled true, username ${RABBITMQ_USERNAME}, password ${RABBITMQ_PASSWORD}
storage.bucket: ${STORAGE_BUCKET}
storage.region: ${AWS_REGION}
storage.playlist-base-url: ${PUBLIC_BASE_URL}
jwt.secret: ${JWT_SECRET}
app.cors-allowed-origin: ${PUBLIC_BASE_URL}
management.endpoints.web.exposure.include: health
```
- No `storage.endpoint`, `public-endpoint`, or access keys in prod: real S3 with credentials from the ECS task role.
- Download the Amazon RDS/DocumentDB CA bundle (https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem) into `src/main/resources/certs/global-bundle.pem` and commit it. If you have no network access, stop and ask the user to put the file there.

## Structure
ONE Spring Boot project, ONE codebase, ONE Docker image (api and worker run from the same image with different env vars).

Package by feature, not by layer:
```
com.minflix.auth     AuthController, JwtService, JwtFilter, SecurityConfig, User, UserRepository
com.minflix.video    VideoController, VideoService, Video, VideoRepository, VideoStatus, dto records
com.minflix.storage  DevStorageConfig (@Profile("dev")), ProdStorageConfig (@Profile("prod")), StorageService
com.minflix.worker   TranscodeJob record, TranscodeListener, Transcoder
```
- `DevStorageConfig`: `S3Client` with `endpointOverride(storage.endpoint)`, path-style, static creds, region; `S3Presigner` with `endpointOverride(storage.public-endpoint)`, same creds/path-style.
- `ProdStorageConfig`: default builders with `Region.of(storage.region)`; credentials from the default provider chain (task role).
- `StorageService` uses `storage.bucket` everywhere (never a literal bucket name).

## Commands
```
docker compose up -d mongo rabbitmq minio minio-init                                    # dev infra only
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev                                   # API from IDE/terminal
SPRING_MAIN_WEB_APPLICATION_TYPE=none ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,worker   # worker
docker compose up --build                                                               # everything in docker (dev)
./mvnw verify
```
Prod image (the USER builds and pushes it, not Claude): `docker buildx build --platform linux/amd64 -t <ecr-repo-url>:latest --push .` (Fargate tasks are x86_64; building on an ARM Mac without `--platform` produces an image that will not start).

Sample video for testing:
`ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 -f lavfi -i sine=duration=10 -c:v libx264 -c:a aac sample.mp4`

## Requirements

### Auth
- User: id, email (unique index), passwordHash. register/login return a JWT.
- JWT: HS256, secret from `jwt.secret`, 24h expiry, subject = userId.
- JwtFilter reads the Bearer token and sets the principal (userId). `/api/auth/**` and `/actuator/health` public, everything else authenticated.
- CORS: allow origin `app.cors-allowed-origin`, all methods/headers. CSRF disabled.

### Video API
- Video: id, ownerId, title, status, rawKey, masterPlaylistUrl, durationSec, createdAt.
- `POST /api/videos`: create with UPLOADING and rawKey `raw/{id}/original.<ext from filename>`. Return a presigned PUT URL (15 min) bound to the given contentType.
- `POST /api/videos/{id}/complete`: owner only. Set PROCESSING, save, publish `TranscodeJob(videoId)`, return Video.
- List = status READY OR ownerId = caller, sorted by createdAt desc.
- **dev gotcha:** the BROWSER uses the presigned URL, so in dev it must contain `http://localhost:9000`, not `minio:9000` (hence the separate `storage.public-endpoint`).
- masterPlaylistUrl = `{storage.playlist-base-url}/hls/{id}/master.m3u8`, set by the worker when READY. Note the dev base URL contains the bucket name and the prod one does not.

### Queue
- One durable queue `transcode`, JSON message `{"videoId": "..."}` (Jackson converter).
- Listener concurrency = 1 (FFmpeg is CPU heavy).
- `default-requeue-rejected=false`. Catch the failure once in the listener to set FAILED so jobs are never redelivered forever.

### Worker / Transcoder
1. Load Video, download `raw/{id}/original.*` to a temp dir.
2. `ffprobe` for duration in seconds.
3. ONE ffmpeg command producing 3 renditions + master playlist: 720p (2800k video), 480p (1400k), 360p (800k), audio 128k. libx264, aac, `-preset veryfast`, `-hls_time 6`, `-hls_playlist_type vod`, `-master_pl_name master.m3u8`, `-var_stream_map` with `name:` entries so directories are exactly `720p`, `480p`, `360p`, and `-hls_segment_filename` so segments land in `{rendition}/segN.ts`. Scale with `scale=-2:<height>`.
4. Upload every output file to `hls/{id}/...` with Content-Type `application/vnd.apple.mpegurl` (.m3u8) or `video/mp2t` (.ts). (CloudFront serves whatever Content-Type S3 has, so this matters in prod.)
5. Set READY + durationSec + masterPlaylistUrl. Delete temp dir.
On any failure: set FAILED, log the error. Stream ffmpeg stderr to the log.

### Health
Actuator with only `health` exposed. `/actuator/health` is public (the prod ALB health check calls it).

### Dev infra (docker-compose.yml at repo root, dev ONLY; there is no prod compose)
- Services: mongo, rabbitmq (management image), minio, one-shot `minio-init` (minio/mc: create bucket `minflix`, anonymous download on `hls/` only, CORS), `api` and `worker` from the same Dockerfile.
- `api`: `SPRING_PROFILES_ACTIVE=dev`. `worker`: `SPRING_PROFILES_ACTIVE=dev,worker` and `SPRING_MAIN_WEB_APPLICATION_TYPE=none`.
- Both override container hostnames via env: `SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/minflix`, `SPRING_RABBITMQ_HOST=rabbitmq`, `STORAGE_ENDPOINT=http://minio:9000` (leave `storage.public-endpoint` at localhost:9000).
- Dockerfile: multi-stage (Maven build on a JDK 17 image, then a JRE 17 image) with `apt-get install -y ffmpeg`. The same image is used in prod.
- Provide `.env.example` for compose ports/credentials.

## Documentation (write as you go, Markdown, in the repo)
- `README.md`: what it is, stack, prerequisites, run options (A: all in docker, B: infra in docker + app from IDE), 60-second curl quickstart (register -> create video -> PUT file -> complete -> poll -> fetch master URL), pointer to minflix-infra for prod.
- `docs/architecture.md`: ASCII component diagram for dev AND prod; upload, processing and watch flows; why each component exists (presigned URL, queue, worker, HLS, CDN) and what replaces each in prod.
- `docs/configuration.md`: dev vs prod yml side by side, every property, and for prod where each env var comes from (Terraform / Secrets Manager).
- `docs/api.md`: every endpoint with real request/response examples and error cases.
- `docs/transcoding.md`: the exact FFmpeg command, each flag explained, bitrate ladder table, resulting storage layout.
- `docs/local-setup.md`: dev ports, MinIO console credentials, RabbitMQ UI, inspecting the bucket, troubleshooting (presigned URL host mismatch, CORS, ffmpeg not found).
- `docs/decisions.md`: every assumption or deviation, one line each.

## Working method
1. Output a short plan with the file list first.
2. Implement in order: (a) docker-compose + MinIO init, (b) auth, (c) video API + presigned URL, (d) queue + worker, (e) prod config + ProdStorageConfig + actuator + Dockerfile, (f) docs.
3. After each dev step: build, start infra, and run the curl flow with the sample mp4.
4. At the end, curl the final dev `master.m3u8` and confirm renditions and segments are listed and reachable.
5. Never push images or touch AWS. Report what you verified and what you did not (prod is unverified until the user deploys it).
6. One happy-path integration smoke test only if cheap. No unit-test boilerplate.
