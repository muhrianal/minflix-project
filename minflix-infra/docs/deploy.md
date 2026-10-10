# Deploy (prod)

All commands are run by **you**. Work from `minflix-infra/` unless stated. Requires AWS credentials for the target account (`aws sts get-caller-identity` works) and Docker with buildx.

## 1. First deploy
```
terraform init
terraform apply -target=aws_ecr_repository.app        # creates only the ECR repo
```
Build and push the backend image (from `minflix-be/`), x86_64 for Fargate:
```
REPO=$(terraform -chdir=../minflix-infra output -raw ecr_repository_url)
REGION=ap-southeast-1                                   # or your var.region
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin ${REPO%%/*}
docker buildx build --platform linux/amd64 -t $REPO:latest --push .
```
Then everything else (back in `minflix-infra/`). DocumentDB, Amazon MQ and CloudFront each take several minutes; expect 15–30 min in total:
```
terraform apply
terraform output
```

## 2. Frontend
From `minflix-fe/`:
```
npm ci
npm run build
aws s3 sync dist/ s3://$(terraform -chdir=../minflix-infra output -raw frontend_bucket) --delete
aws cloudfront create-invalidation --distribution-id $(terraform -chdir=../minflix-infra output -raw cloudfront_distribution_id) --paths "/*"
```
The FE needs no prod config: `VITE_API_URL` is empty in prod, so it calls `/api/...` on the CloudFront domain.

## 3. Smoke test
1. Open `https://<cloudfront_domain>` (`terraform output -raw cloudfront_domain`).
2. Register, then upload a short video with audio (e.g. the 10 s sample from minflix-be README).
3. Library shows PROCESSING, then READY (worker logs below).
4. Open it and play; switch qualities.
Quick API check: `curl -s https://<cloudfront_domain>/api/videos` → `401 {"message":"Unauthorized"}`.

## 4. Updating the backend
Push a new image (same `docker buildx build ... --push`), then:
```
aws ecs update-service --cluster minflix-prod --service api    --force-new-deployment
aws ecs update-service --cluster minflix-prod --service worker --force-new-deployment
```
(Using a new `image_tag` instead: set it in `terraform.tfvars` and `terraform apply`.)

## 5. Logs
```
aws logs tail /ecs/minflix-prod-api --follow
aws logs tail /ecs/minflix-prod-worker --follow
```

## 6. Teardown
```
terraform destroy
```
Deletes everything, including all videos, users and the ECR images.

## 7. Troubleshooting
**Task stops with `CannotPullContainerError`.** The image is not pushed yet (step 1), the tag differs from `image_tag`, or it was built for arm64: an ARM Mac needs `--platform linux/amd64` (`exec format error` in the logs means wrong platform). If `docker buildx` is missing (`fork/exec .../docker-buildx: no such file or directory`), install it (`brew install docker-buildx`) and link it into `~/.docker/cli-plugins/`.

**api tasks keep restarting / target unhealthy.** Check `aws logs tail /ecs/minflix-prod-api`. A `Could not resolve placeholder` means an env var is missing from the task definition. Startup needs DocumentDB reachable (index creation), so DB errors stop the app before `/actuator/health` answers. Health check: `GET /actuator/health` on 8080 must return 200 within the 60 s grace period plus retries; a slow first start can need another deployment.

**DocumentDB TLS errors** (`PKIX path building failed`, `SSLHandshakeException`). The backend must contain `src/main/resources/certs/global-bundle.pem` and use `spring.data.mongodb.ssl.bundle: docdb`. The `MONGODB_URI` secret must keep `tls=true` and `retryWrites=false`.

**Amazon MQ connection refused / timeouts.** `RABBITMQ_HOST` must be the bare hostname (check the task definition env), port 5671 with SSL. The broker must be in the same VPC; MQ SG allows 5671 only from the api/worker SGs.

**CORS error on upload.** The media bucket CORS allows only `https://<cloudfront_domain>`. Open the site through that exact URL (not the S3 or ALB URL). The PUT must use the same `Content-Type` as `POST /api/videos` and no `Authorization` header.

**FE route 404/403 after refresh** (`/watch/...`). The `minflix-prod-spa-rewrite` CloudFront Function must be associated with the default behavior; after FE deploys, invalidate `/*`.

**Video stuck in PROCESSING.** Worker not running (`worker_desired_count = 0`?) or crashing: check worker logs. FAILED means ffmpeg failed (e.g. a corrupt or non-video file) or the raw upload was missing.
