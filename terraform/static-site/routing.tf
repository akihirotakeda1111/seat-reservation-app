data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}
data "aws_cloudfront_origin_request_policy" "api" {
  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_function" "spa" {
  name    = "${local.name_prefix}-spa"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/spa.js")
}
output "distribution" {
  value = {
    domain_name = aws_cloudfront_distribution.site.domain_name
    zone_id     = aws_cloudfront_distribution.site.hosted_zone_id
  }
}
