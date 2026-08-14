output "alb_dns_name" { value = aws_lb.main.dns_name }
output "ecr_repository_url" { value = aws_ecr_repository.api.repository_url }
output "s3_bucket" { value = aws_s3_bucket.uploads.id }
output "ecs_cluster" { value = aws_ecs_cluster.main.name }
output "ecs_service" { value = aws_ecs_service.api.name }
output "gha_role_arn" { value = aws_iam_role.github_actions.arn }
output "rds_endpoint" { value = aws_db_instance.main.address }
