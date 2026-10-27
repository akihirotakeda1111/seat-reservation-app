# AWS環境のデプロイ・更新・破棄

Linux / Bashで、リポジトリルートから実行する。Terraformの実行単位は`terraform/`とState保存先用の`terraform/bootstrap/`。各コマンドの成功とplanの内容を確認してから次へ進む。

設計は[AWSアーキテクチャ](../doc/system-design/aws-architecture.md)・[CI/CD設計](../doc/system-design/cicd-design.md)、調査・復旧と開発時の検証は[TROUBLESHOOTING.md](TROUBLESHOOTING.md)を参照。

## 1. 事前準備

- Terraform 1.14.7以上1.15.0未満、AWS CLI、Docker、Node.js 24 / npm、jqを用意し、Dockerを起動する。
- 対象AWSアカウントへ認証し、構築・デプロイに必要な権限を用意する。
- Route 53のパブリックホストゾーンとDNS委任を準備する。
- ACM証明書をDNS検証して発行する。CloudFront用は`us-east-1`、ALB用は`ap-northeast-1`。
- 既存CognitoのSPA用クライアント（シークレットなし・認可コードグラント・`openid`）とログインドメイン、許可するコールバック／サインアウトURLを準備する。

```bash
terraform version
aws sts get-caller-identity
```

## 2. 初回デプロイ

### 2.1 State保存先を作成

初回のみ実行する。既存の保存先がある場合は再利用する。

```bash
terraform -chdir=terraform/bootstrap init
terraform -chdir=terraform/bootstrap plan -out=bootstrap.tfplan
```

計画を確認して適用し、出力されたバケット名を控える。

```bash
terraform -chdir=terraform/bootstrap apply bootstrap.tfplan
terraform -chdir=terraform/bootstrap output -raw state_bucket
```

`terraform/bootstrap/terraform.tfstate`は安全な場所へバックアップし、Gitには保存しない。

### 2.2 設定ファイルを準備

設定ファイルがなければサンプルから作成する。

```bash
test -e terraform/backend.hcl || cp terraform/backend.hcl.example terraform/backend.hcl
test -e terraform/terraform.tfvars || cp terraform/terraform.tfvars.example terraform/terraform.tfvars
```

| ファイル | 設定内容 |
| --- | --- |
| `terraform/backend.hcl` | State用バケット名。暗号化・ロックの設定は維持 |
| `terraform/terraform.tfvars` | 公開／Origin用FQDN、発行済み証明書ARN |

初回は以下の値にする。その他の調整項目は[設定例](terraform.tfvars.example)を参照。

```hcl
desired_count    = 0
image_digest     = null
rollback_enabled = false
```

### 2.3 インフラを作成

```bash
terraform -chdir=terraform init -backend-config=backend.hcl
terraform -chdir=terraform validate
terraform -chdir=terraform plan -out=environment.tfplan
```

計画を確認して適用する。入力変更後はplanを作り直す。

```bash
terraform -chdir=terraform apply environment.tfplan
```

ECSは0台で、アプリはまだ利用できない。ALB・NAT・RDSなどはこの時点から稼働する。

### 2.4 DNSを設定

```bash
terraform -chdir=terraform output -json dns_records
```

出力の`public`・`origin`に従い、Route 53で公開用・Origin用レコードを設定する。

### 2.5 Backendを配置・起動

イメージをビルドしてECRへPushし、ダイジェストを取得する。

```bash
repository="$(terraform -chdir=terraform output -raw ecr_repository_url)"
registry="${repository%%/*}"
image_tag="$(git rev-parse --short HEAD)-$(date -u +%Y%m%d%H%M%S)"
aws ecr get-login-password --region ap-northeast-1 |
    docker login --username AWS --password-stdin "$registry"
docker build --platform linux/amd64 -t "${repository}:${image_tag}" backend
docker push "${repository}:${image_tag}"
aws ecr describe-images --region ap-northeast-1 \
    --repository-name seat-reservation-app \
    --image-ids "imageTag=${image_tag}" \
    --query 'imageDetails[0].imageDigest' --output text
```

`terraform/terraform.tfvars`の以下を編集する。初回起動ではロールバックを無効のままにする。

```hcl
desired_count      = 1
image_digest       = "sha256:＜取得したダイジェスト＞"
cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/＜ユーザープールID＞"
cognito_client_id  = "＜アプリクライアントID＞"
rollback_enabled  = false
```

```bash
terraform -chdir=terraform plan -out=environment.tfplan
```

計画を確認して適用する。

```bash
terraform -chdir=terraform apply environment.tfplan
```

ECSのデプロイが`COMPLETED`、指定イメージのタスクが1台稼働、ALBターゲットが`Healthy`となることを確認する。

### 2.6 Frontendを配置・動作確認

`frontend/.env.production.local`に実際の値を設定する。

```dotenv
VITE_COGNITO_AUTHORITY=https://cognito-idp.ap-northeast-1.amazonaws.com/＜ユーザープールID＞
VITE_COGNITO_CLIENT_ID=＜アプリクライアントID＞
VITE_COGNITO_DOMAIN=https://＜Cognitoドメイン＞.auth.ap-northeast-1.amazoncognito.com
VITE_AUTH_REDIRECT_URI=https://＜公開FQDN＞/auth/callback
VITE_AUTH_LOGOUT_URI=https://＜公開FQDN＞/login
```

ビルドして配置先を取得する。

```bash
npm --prefix frontend ci
npm --prefix frontend run build
frontend_outputs="$(terraform -chdir=terraform output -json frontend)"
bucket="$(printf '%s' "$frontend_outputs" | jq -er '.s3_bucket_id')"
distribution_id="$(printf '%s' "$frontend_outputs" | jq -er '.cloudfront_distribution_id')"
printf 'Bucket: %s\nDistribution: %s\n' "$bucket" "$distribution_id"
```

ビルド成功と配置先を確認してアップロードする。`--delete`は配置先だけにあるファイルを削除する。

```bash
aws s3 sync frontend/dist "s3://${bucket}/" --delete
invalidation_id="$(aws cloudfront create-invalidation \
    --distribution-id "$distribution_id" --paths '/*' \
    --query 'Invalidation.Id' --output text)"
aws cloudfront wait invalidation-completed \
    --distribution-id "$distribution_id" --id "$invalidation_id"
terraform -chdir=terraform output -raw application_url
printf '\n'
```

公開URLでログイン・予約・キャンセルを確認する。正常稼働確認後、`terraform.tfvars`を`rollback_enabled = true`に変更し、再度planを確認してapplyする。以後の更新ではtrueを維持する。

## 3. 更新デプロイ

| 更新対象 | 操作 |
| --- | --- |
| Backend | 2.5のビルド・Pushを実行し、`image_digest`を更新してplan・apply |
| Frontend | 2.6のビルド・S3配置・キャッシュ無効化を実行 |
| インフラ | Terraform設定を変更し、新しいplanを確認してapply |

Frontendの環境変数だけを変更した場合も、再ビルド・再配置が必要。Backend更新後は、意図したダイジェストで稼働していることを確認する。

## 4. 環境の破棄

**予約データ・S3成果物・ECRイメージを削除する。RDSの最終スナップショットは作成しない。** 必要なデータを保存し、構築時と同じアカウント・Backend設定で実行する。

```bash
aws sts get-caller-identity
terraform -chdir=terraform init -backend-config=backend.hcl
terraform -chdir=terraform plan -destroy -out=destroy.tfplan
```

削除対象を確認して適用し、Stateに管理対象が残っていないことを確認する。

```bash
terraform -chdir=terraform apply destroy.tfplan
terraform -chdir=terraform state list
```

- State保存先・Cognito・Hosted Zone・ACM証明書は残す。`terraform/bootstrap`ではdestroyしない。
- 公開／Origin用DNSレコードは手動で削除または更新し、ACM検証レコードは保持する。
- 再構築はState保存先を再利用し、2.2から実行する。初回設定に戻し、DNS更新と成果物の再配置を行う。
