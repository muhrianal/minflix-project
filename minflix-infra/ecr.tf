resource "aws_ecr_repository" "app" {
  name         = "minflix-be"
  force_delete = true
}
