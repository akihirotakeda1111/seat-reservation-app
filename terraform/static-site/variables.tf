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

variable "routing" {
  description = "Public hostname, certificate, and private API origin routing."
  nullable    = false
  type = object({
    hostname        = string
    certificate_arn = string
    api_hostname    = string
    vpc_origin_id   = string
  })
}
