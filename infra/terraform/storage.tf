resource "aws_s3_bucket" "uploads" {
  bucket = "${local.name}-uploads-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket                  = aws_s3_bucket.uploads.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_cors_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT", "POST", "HEAD"]
    allowed_origins = [
      "http://localhost:5173",
      var.vercel_origin
    ]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload { days_after_initiation = 7 }
  }

  # 올리기만 하고 등록하지 않은 자료 파일을 걷어간다 (#53).
  #
  # 브라우저가 presigned URL로 파일만 올리고 POST /notes를 부르지 않는 일은 흔하다 —
  # 창을 닫거나, 네트워크가 끊기거나, 그냥 마음을 바꾼다. 그 파일은 DB에 행이 없어
  # 아무도 찾을 수 없고, 규칙이 없으면 영원히 남는다.
  #
  # 등록된 파일은 notes/ 바로 아래로 복사되므로 이 규칙에 걸리지 않는다.
  rule {
    id     = "expire-unclaimed-note-uploads"
    status = "Enabled"
    filter { prefix = "notes/uploads/" }
    expiration { days = 1 }
  }
}

# 버킷은 완전 비공개, presigned URL로만 접근한다 (docs/ops/infra.md).
# 키 네이밍: notes/uploads/{userId}/{uuid}.{ext} (임시, 하루), notes/{uuid}.{ext} (등록 후),
#           photos/{photoId}/{uuid}.jpg, photos/{photoId}/thumb/{uuid}.jpg

resource "aws_ecr_repository" "api" {
  name                 = "${local.name}-api"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 5 }
      action       = { type = "expire" }
    }]
  })
}
