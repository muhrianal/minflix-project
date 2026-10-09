resource "aws_docdb_subnet_group" "main" {
  name       = "minflix-prod-docdb"
  subnet_ids = aws_subnet.private[*].id
}

# TLS stays on (default cluster parameter group). The backend trusts the AWS RDS CA bundle.
resource "aws_docdb_cluster" "main" {
  cluster_identifier      = "minflix-prod-docdb"
  engine                  = "docdb"
  engine_version          = "5.0.0"
  master_username         = "minflix"
  master_password         = random_password.docdb.result
  db_subnet_group_name    = aws_docdb_subnet_group.main.name
  vpc_security_group_ids  = [aws_security_group.docdb.id]
  storage_encrypted       = true
  backup_retention_period = 1
  skip_final_snapshot     = true
}

resource "aws_docdb_cluster_instance" "main" {
  identifier         = "minflix-prod-docdb-1"
  cluster_identifier = aws_docdb_cluster.main.id
  instance_class     = var.docdb_instance_class
}
