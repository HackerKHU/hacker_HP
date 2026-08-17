output "alb_dns_name" { value = aws_lb.main.dns_name }
output "ecr_repository_url" { value = aws_ecr_repository.api.repository_url }
output "s3_bucket" { value = aws_s3_bucket.uploads.id }
output "ecs_cluster" { value = aws_ecs_cluster.main.name }
output "ecs_service" { value = aws_ecs_service.api.name }
output "gha_role_arn" { value = aws_iam_role.github_actions.arn }
output "rds_endpoint" { value = aws_db_instance.main.address }

# #156 — 이 CNAME을 khuhacker.com을 관리하는 DNS(등록기관 또는 그쪽 콘솔)에 넣어야
# api 인증서가 PENDING_VALIDATION에서 ISSUED로 바뀐다.
output "acm_validation_record" {
  value = {
    name  = tolist(aws_acm_certificate.api.domain_validation_options)[0].resource_record_name
    type  = tolist(aws_acm_certificate.api.domain_validation_options)[0].resource_record_type
    value = tolist(aws_acm_certificate.api.domain_validation_options)[0].resource_record_value
  }
}
