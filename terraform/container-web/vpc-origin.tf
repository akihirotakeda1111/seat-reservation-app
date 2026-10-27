resource "aws_cloudfront_vpc_origin" "api" {
  vpc_origin_endpoint_config {
    name                   = "${local.name_prefix}-api"
    arn                    = aws_lb.web.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "https-only"
    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
  depends_on = [aws_lb_listener.https, aws_internet_gateway.main]
}

data "aws_security_group" "cloudfront" {
  filter {
    name   = "vpc-id"
    values = [aws_vpc.main.id]
  }
  filter {
    name   = "group-name"
    values = ["CloudFront-VPCOrigins-Service-SG*"]
  }
  depends_on = [aws_cloudfront_vpc_origin.api]
}
output "vpc_origin_id" {
  value      = aws_cloudfront_vpc_origin.api.id
  depends_on = [aws_vpc_security_group_ingress_rule.alb_https]
}
