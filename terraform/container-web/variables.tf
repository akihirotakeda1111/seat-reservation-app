variable "project_name" {
  description = "Project name: 3-20 characters, starting with a lowercase letter and not ending with a hyphen."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,18}[a-z0-9]$", var.project_name))
    error_message = "project_name must match the public input format."
  }
}

variable "environment" {
  description = "Environment name supplied by the root module."
  type        = string
  nullable    = false

}

variable "aws_region" {
  description = "AWS Region supplied by the root module."
  type        = string
  nullable    = false

}

variable "certificate_arn" {
  description = "Existing regional ACM certificate for the ALB listener."
  type        = string
  nullable    = false
}

variable "application" {
  description = "Container image, authentication, and health endpoint settings."
  nullable    = false
  type = object({
    image_digest       = optional(string)
    cognito_issuer_uri = optional(string, "")
    cognito_client_id  = optional(string, "")
    container_port     = optional(number, 8080)
    health_check_path  = optional(string, "/actuator/health")
  })
  default = {}
  validation {
    condition = (var.application.cognito_issuer_uri == "" && var.application.cognito_client_id == "") || (
      can(regex("^https://cognito-idp\\.[^/]+/[^/]+$", var.application.cognito_issuer_uri)) && length(trimspace(var.application.cognito_client_id)) > 0
    )
    error_message = "Configure both Cognito issuer and client ID, or leave both empty until CD setup."
  }
  validation {
    condition     = var.application.image_digest == null ? true : can(regex("^sha256:[a-f0-9]{64}$", var.application.image_digest))
    error_message = "image_digest must be a sha256 digest."
  }
  validation {
    condition     = (var.deployment.desired_count == 0 && var.application.image_digest == null) || (var.application.image_digest != null && can(regex("^https://cognito-idp\\.[^/]+/[^/]+$", var.application.cognito_issuer_uri)) && length(trimspace(var.application.cognito_client_id)) > 0)
    error_message = "Preparing a real-image task definition requires existing Cognito issuer/client ID."
  }
  validation {
    condition     = var.application.container_port >= 1 && var.application.container_port <= 65535 && floor(var.application.container_port) == var.application.container_port && startswith(var.application.health_check_path, "/")
    error_message = "Use an integer container port from 1 to 65535 and an absolute health check path."
  }
}

variable "deployment" {
  description = "Task sizing and deployment behavior. Enable rollback only after a real-image deployment succeeds."
  nullable    = false
  type = object({
    desired_count                     = optional(number, 0)
    rollback_enabled                  = optional(bool, false)
    cpu                               = optional(number, 256)
    memory                            = optional(number, 1024)
    health_check_grace_period_seconds = optional(number, 300)
  })
  default = {}
  validation {
    condition     = var.deployment.desired_count >= 0 && floor(var.deployment.desired_count) == var.deployment.desired_count
    error_message = "desired_count must be a non-negative integer."
  }

  validation {
    condition = contains(lookup({
      "256"   = [512, 1024, 2048]
      "512"   = [1024, 2048, 3072, 4096]
      "1024"  = range(2048, 8193, 1024)
      "2048"  = range(4096, 16385, 1024)
      "4096"  = range(8192, 30721, 1024)
      "8192"  = range(16384, 61441, 4096)
      "16384" = range(32768, 122881, 8192)
    }, tostring(var.deployment.cpu), []), var.deployment.memory)
    error_message = "cpu and memory must be a supported Linux Fargate task size (256-16384 CPU units; memory in MiB)."
  }
  validation {
    condition     = var.deployment.health_check_grace_period_seconds >= 0 && var.deployment.health_check_grace_period_seconds <= 2147483647 && floor(var.deployment.health_check_grace_period_seconds) == var.deployment.health_check_grace_period_seconds
    error_message = "health_check_grace_period_seconds must be an integer from 0 to 2147483647."
  }
}
