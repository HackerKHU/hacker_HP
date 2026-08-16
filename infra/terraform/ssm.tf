resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "db_url" {
  name  = "/hacker/dev/DB_URL"
  type  = "SecureString"
  value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${aws_db_instance.main.db_name}"
}

resource "aws_ssm_parameter" "db_username" {
  name  = "/hacker/dev/DB_USERNAME"
  type  = "SecureString"
  value = aws_db_instance.main.username
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/hacker/dev/DB_PASSWORD"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/hacker/dev/JWT_SECRET"
  type  = "SecureString"
  value = random_password.jwt.result
}

resource "random_password" "admin_bootstrap_token" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "admin_bootstrap_email" {
  name  = "/hacker/dev/ADMIN_BOOTSTRAP_EMAIL"
  type  = "SecureString"
  value = var.admin_bootstrap_email
}

resource "aws_ssm_parameter" "admin_bootstrap_token" {
  name  = "/hacker/dev/ADMIN_BOOTSTRAP_TOKEN"
  type  = "SecureString"
  value = random_password.admin_bootstrap_token.result
}

# 구글 OAuth 자격 — Terraform이 만들 수 없는 값이라 random_password를 쓰지 않는다.
# Google Cloud Console에서 발급받아 terraform.tfvars(커밋하지 않음)로 채운다 (#82).
resource "aws_ssm_parameter" "google_client_id" {
  name  = "/hacker/dev/GOOGLE_CLIENT_ID"
  type  = "SecureString"
  value = var.google_client_id
}

resource "aws_ssm_parameter" "google_client_secret" {
  name  = "/hacker/dev/GOOGLE_CLIENT_SECRET"
  type  = "SecureString"
  value = var.google_client_secret
}
