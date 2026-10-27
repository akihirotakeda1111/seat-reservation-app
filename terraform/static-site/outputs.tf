output "project_name" {
  description = "Project name"
  value       = var.project_name
}

output "environment" {
  description = "Environment name"
  value       = var.environment
}

output "aws_region" {
  description = "AWS Region"
  value       = var.aws_region
}

output "resource_identifiers" {
  description = "Key resource IDs and ARNs. Contains no secret values."
  value = {
    s3_bucket_id                = aws_s3_bucket.site.id
    s3_bucket_arn               = aws_s3_bucket.site.arn
    cloudfront_distribution_id  = aws_cloudfront_distribution.site.id
    cloudfront_distribution_arn = aws_cloudfront_distribution.site.arn
    origin_access_control_id    = aws_cloudfront_origin_access_control.site.id
  }
}

output "endpoints" {
  description = "URL using the default CloudFront domain. Deploy content before accessing the site."
  value = {
    site_url = "https://${aws_cloudfront_distribution.site.domain_name}"
  }
}
