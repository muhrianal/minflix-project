# API

Base path `/api`, JSON. Every endpoint except `/api/auth/*` needs `Authorization: Bearer <jwt>`. Errors are always `{"message": string}`. Source of truth: `../../docs/CONTRACT.md`.

Examples below are real responses from the dev stack (ids and tokens shortened).

## Common errors
| Status | When | Body |
|---|---|---|
| 400 | malformed JSON | `{"message":"Malformed request body"}` |
| 400 | required field blank/missing | e.g. `{"message":"email and password are required"}` |
| 401 | missing, invalid or expired token | `{"message":"Unauthorized"}` |

## POST /api/auth/register
```
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"a@x.io","password":"pw"}'
```
`201` `{"token":"eyJhbGciOiJIUzI1NiJ9..."}`
`409` `{"message":"Email already registered"}`
`400` `{"message":"email and password are required"}`

JWT: HS256, `sub` = userId, expires after 24 h.

## POST /api/auth/login
```
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"a@x.io","password":"pw"}'
```
`200` `{"token":"eyJhbGciOiJIUzI1NiJ9..."}`
`401` `{"message":"Invalid credentials"}` (unknown email or wrong password)

## POST /api/videos
```
curl -X POST localhost:8080/api/videos -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Sample","filename":"sample.mp4","contentType":"video/mp4"}'
```
`201`
```json
{"videoId":"6ac8a1521c84d414d82b8086",
 "uploadUrl":"http://localhost:9000/minflix/raw/6ac8a1521c84d414d82b8086/original.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&...&X-Amz-SignedHeaders=content-type%3Bhost&X-Amz-Expires=900&X-Amz-Signature=..."}
```
`400` `{"message":"title, filename and contentType are required"}`

The video is created as `UPLOADING`. Then upload the file (URL valid 15 min); the `Content-Type` must equal the one sent above, otherwise storage answers `403`:
```
curl -X PUT -H 'Content-Type: video/mp4' --upload-file sample.mp4 "$UPLOAD_URL"
```

## POST /api/videos/{id}/complete
```
curl -X POST localhost:8080/api/videos/$ID/complete -H "Authorization: Bearer $TOKEN"
```
`200`
```json
{"id":"6ac8a1521c84d414d82b8086","title":"Sample","status":"PROCESSING","ownerId":"6ac8a1511c84d414d82b8085",
 "masterPlaylistUrl":null,"durationSec":null,"createdAt":"2026-10-09T08:09:53.990Z"}
```
`403` `{"message":"Forbidden"}` (caller is not the owner)
`404` `{"message":"Not found"}`

Sets `PROCESSING` and enqueues the transcode job. The worker later sets `READY` or `FAILED`.

## GET /api/videos
```
curl localhost:8080/api/videos -H "Authorization: Bearer $TOKEN"
```
`200` newest first: all `READY` videos from everyone plus the caller's own videos in any status.
```json
[{"id":"6ac8a1711c84d414d82b8088","title":"Pending","status":"UPLOADING","ownerId":"6ac8a1711c84d414d82b8087",
  "masterPlaylistUrl":null,"durationSec":null,"createdAt":"2026-10-09T08:10:25.617Z"},
 {"id":"6ac8a1521c84d414d82b8086","title":"Sample","status":"READY","ownerId":"6ac8a1511c84d414d82b8085",
  "masterPlaylistUrl":"http://localhost:9000/minflix/hls/6ac8a1521c84d414d82b8086/master.m3u8",
  "durationSec":10.0,"createdAt":"2026-10-09T08:09:53.990Z"}]
```

## GET /api/videos/{id}
```
curl localhost:8080/api/videos/$ID -H "Authorization: Bearer $TOKEN"
```
`200`
```json
{"id":"6ac8a1521c84d414d82b8086","title":"Sample","status":"READY","ownerId":"6ac8a1511c84d414d82b8085",
 "masterPlaylistUrl":"http://localhost:9000/minflix/hls/6ac8a1521c84d414d82b8086/master.m3u8",
 "durationSec":10.0,"createdAt":"2026-10-09T08:09:53.990Z"}
```
`404` `{"message":"Not found"}` — unknown id, or someone else's video that is not `READY` (same visibility as the list).

## Video
| Field | Type | Notes |
|---|---|---|
| id | string | |
| title | string | |
| status | `UPLOADING` \| `PROCESSING` \| `READY` \| `FAILED` | only the backend changes it |
| ownerId | string | |
| masterPlaylistUrl | string \| null | null until `READY` |
| durationSec | number \| null | set when `READY` |
| createdAt | ISO-8601 string | UTC |

## Operational
`GET /actuator/health` -> `200 {"status":"UP"}`, public, used by the prod ALB health check. Only `health` is exposed.
