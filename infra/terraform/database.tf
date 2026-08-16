resource "random_password" "db" {
  length  = 32
  special = false # RDS가 일부 특수문자를 거부. 껐다가 삽질하지 말 것
}

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name}-dev"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 0 # 오토스케일 끔 (프리티어 20GB 초과 방지)
  storage_type          = "gp2"
  storage_encrypted     = true

  db_name  = "hacker"
  username = "hacker_admin"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period      = 7
  performance_insights_enabled = false
  monitoring_interval          = 0

  skip_final_snapshot = true  # dev 한정
  deletion_protection = false # dev 한정
  apply_immediately   = true
}
