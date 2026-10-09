# special = false: these values end up in a MongoDB URI and an Amazon MQ password
# (MQ rejects , : =), so plain alphanumerics avoid any escaping.

resource "random_password" "docdb" {
  length  = 32
  special = false
}

resource "random_password" "mq" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "jwt_secret" {
  name                    = "minflix-prod/jwt-secret"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "jwt_secret" {
  secret_id     = aws_secretsmanager_secret.jwt_secret.id
  secret_string = random_password.jwt.result
}

resource "aws_secretsmanager_secret" "rabbitmq_password" {
  name                    = "minflix-prod/rabbitmq-password"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "rabbitmq_password" {
  secret_id     = aws_secretsmanager_secret.rabbitmq_password.id
  secret_string = random_password.mq.result
}

resource "aws_secretsmanager_secret" "mongodb_uri" {
  name                    = "minflix-prod/mongodb-uri"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "mongodb_uri" {
  secret_id     = aws_secretsmanager_secret.mongodb_uri.id
  secret_string = "mongodb://${aws_docdb_cluster.main.master_username}:${random_password.docdb.result}@${aws_docdb_cluster.main.endpoint}:27017/minflix?authSource=admin&tls=true&replicaSet=rs0&retryWrites=false"
}
