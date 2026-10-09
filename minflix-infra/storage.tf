resource "random_id" "bucket_suffix" {
  byte_length = 4
}

# --- Media bucket: raw/ (browser uploads via presigned PUT) and hls/ (served by CloudFront) ---

resource "aws_s3_bucket" "media" {
  bucket        = "minflix-prod-media-${random_id.bucket_suffix.hex}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# --- Frontend bucket: static FE build, served by CloudFront ---

resource "aws_s3_bucket" "frontend" {
  bucket        = "minflix-prod-frontend-${random_id.bucket_suffix.hex}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
