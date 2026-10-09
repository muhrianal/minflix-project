# Deploy (prod)

Done by you, not by Claude. Prerequisite: minflix-infra applied, backend image deployed.

```
npm run build
aws s3 sync dist/ s3://<frontend_bucket> --delete
aws cloudfront create-invalidation --distribution-id <distribution_id> --paths "/*"
```

| Value | Where it comes from |
|---|---|
| `<frontend_bucket>` | `terraform output` in minflix-infra (the bucket holding the FE static files) |
| `<distribution_id>` | `terraform output` in minflix-infra (the CloudFront distribution) |

- `npm run build` uses Vite mode `production`, i.e. `.env.production`, where `VITE_API_URL=` is empty. API calls become relative (`/api/...`). CloudFront serves the FE (`/`), the API (`/api/*` -> ALB) and HLS (`/hls/*` -> S3) on one domain, so the FE needs **no prod config and no prod hostname**.
- `--delete` removes files from old builds (asset names are content-hashed).
- The invalidation makes CloudFront fetch the new `index.html` immediately.
- Client-side routes (`/watch/:id`, ...) work on refresh because CloudFront rewrites unknown paths to `index.html` (configured in minflix-infra).
- Upload URLs (S3 presigned, on the S3 domain) and `masterPlaylistUrl` (`https://<cf-domain>/hls/...`) come from the API as absolute URLs and are used as given.

Not verified: none of this has been run against AWS yet.
