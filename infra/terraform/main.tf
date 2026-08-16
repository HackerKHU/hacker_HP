terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  backend "s3" {
    bucket       = "hacker-tfstate-415368001031"
    key          = "dev/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-northeast-2"
  default_tags {
    tags = { Project = "hacker", Env = "dev", ManagedBy = "terraform" }
  }
}

data "aws_caller_identity" "current" {}

locals {
  name   = "hacker"
  region = "ap-northeast-2"
  azs    = ["ap-northeast-2a", "ap-northeast-2c"]
}
