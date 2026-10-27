data "aws_caller_identity" "current" {}

locals {
  name_prefix = "${var.project_name}-${var.environment}"
  template_id = "static-site"
  bucket_name = "${local.name_prefix}-${data.aws_caller_identity.current.account_id}-${substr(sha256("${local.template_id}-${var.aws_region}"), 0, 8)}"
  # AWS managed CachingOptimized cache policy.
  # https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-cache-policies.html
  caching_optimized_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
}
