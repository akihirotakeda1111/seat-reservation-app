variable "project_name" {
  description = "Project name used in AWS resource names."
  nullable    = false
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,18}[a-z0-9]$", var.project_name))
    error_message = "project_name must be 3-20 lowercase letters, digits or hyphens, starting with a letter and ending with a letter or digit."
  }
  type    = string
  default = "seat-reservation-app"
}
variable "cloudfront_certificate_arn" {
  description = "Manually created, ISSUED ACM certificate covering public_hostname in us-east-1."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^arn:aws:acm:us-east-1:[0-9]{12}:certificate/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.cloudfront_certificate_arn))
    error_message = "CloudFront requires an ACM certificate ARN in us-east-1."
  }
}
variable "alb_certificate_arn" {
  description = "Manually created, ISSUED ACM certificate covering origin_hostname in aws_region."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^arn:aws:acm:[a-z0-9-]+:[0-9]{12}:certificate/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.alb_certificate_arn)) && startswith(var.alb_certificate_arn, "arn:aws:acm:${var.aws_region}:")
    error_message = "ALB requires an ACM certificate ARN in the main AWS region."
  }
}
variable "environment" {
  description = "Deployment environment. This root configuration supports dev only."
  nullable    = false
  validation {
    condition     = var.environment == "dev"
    error_message = "environment must be dev."
  }
  type    = string
  default = "dev"
}
variable "aws_region" {
  description = "Main AWS Region. This root configuration supports ap-northeast-1 only."
  nullable    = false
  validation {
    condition     = var.aws_region == "ap-northeast-1"
    error_message = "aws_region must be ap-northeast-1."
  }
  type    = string
  default = "ap-northeast-1"
}
variable "public_hostname" {
  description = "Public application FQDN managed outside Terraform."
  type        = string
  nullable    = false
  validation {
    condition     = length(var.public_hostname) <= 253 && can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$", var.public_hostname))
    error_message = "public_hostname must be a lowercase FQDN without a scheme, path, wildcard or trailing dot."
  }
}
variable "origin_hostname" {
  description = "ALB origin FQDN managed outside Terraform."
  type        = string
  nullable    = false
  validation {
    condition     = length(var.origin_hostname) <= 253 && can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$", var.origin_hostname))
    error_message = "origin_hostname must be a lowercase FQDN without a scheme, path, wildcard or trailing dot."
  }
  validation {
    condition     = var.origin_hostname != var.public_hostname
    error_message = "Public and origin hostnames must differ."
  }
}
variable "desired_count" {
  nullable = false
  validation {
    condition     = contains([0, 1], var.desired_count)
    error_message = "desired_count must be 0 (stopped/bootstrap) or 1 (running)."
  }
  description = "Creation-time task count. Keep 0 for CD-managed startup; later changes are ignored."
  type        = number
  default     = 0
}
variable "image_digest" {
  description = "Optional baseline image digest. Leave null for CD-managed releases; CD selects the release image."
  type        = string
  nullable    = true
  default     = null
}
variable "cognito_issuer_uri" {
  description = "Existing Cognito user pool issuer URL; required when starting tasks."
  nullable    = false
  type        = string
  default     = ""
}
variable "cognito_client_id" {
  description = "Existing public Cognito app client ID; required when starting tasks."
  nullable    = false
  type        = string
  default     = ""
}

variable "rollback_enabled" {
  description = "Enable only after a real-image deployment has completed successfully. Terraform does not verify deployment history."
  type        = bool
  nullable    = false
  default     = false
}
variable "task_cpu" {
  description = "Fargate task CPU units. Valid CPU/memory combinations are checked by the backend module."
  type        = number
  nullable    = false
  default     = 256
}
variable "task_memory" {
  description = "Fargate task memory in MiB."
  type        = number
  nullable    = false
  default     = 1024
}
variable "health_check_grace_period_seconds" {
  description = "Startup grace period before ECS acts on failed load balancer health checks."
  type        = number
  nullable    = false
  default     = 300
}