# Terraform이 만들어낼 수 없는 값들이다. 구글 클라이언트 자격은 Google Cloud Console에서
# 발급받고(#82), redirect URI는 Vercel 도메인이 정해져야 알 수 있다.

variable "google_client_id" {
  description = "Google Cloud Console OAuth 클라이언트 ID"
  type        = string
  sensitive   = true
}

variable "google_client_secret" {
  description = "Google Cloud Console OAuth 클라이언트 시크릿"
  type        = string
  sensitive   = true
}

variable "oauth_redirect_uri" {
  description = "구글에 등록한 승인 redirect URI. 프론트엔드 오리진 기준이다"
  type        = string
  # 예: https://hacker-hp.vercel.app/api/v1/login/oauth2/code/google
}

variable "allowed_email_domain" {
  description = "가입을 허용할 학교 이메일 도메인"
  type        = string
  default     = "khu.ac.kr"
}

variable "vercel_origin" {
  description = "S3 CORS허용 오리진(Vercel 배포 도메인). presigned 업로드가 이 값을 쓴다"
  type        = string
  # 예: https://hacker-hp.vercel.app
}

variable "admin_bootstrap_email" {
  description = "최초 관리자로 승격할 계정의 이메일 (spec 3-3 결정 11)"
  type        = string
  sensitive   = true
}
