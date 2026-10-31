# AWS環境のデプロイ・更新・破棄

Linux / Bashで、リポジトリルートから実行する。Terraformの実行単位は`terraform/`とState保存先用の`terraform/bootstrap/`。各コマンドの成功とplanの内容を確認してから次へ進む。

設計は[AWSアーキテクチャ](../doc/system-design/aws-architecture.md)・[CI/CD設計](../doc/system-design/cicd-design.md)、障害調査・復旧は[TROUBLESHOOTING.md](TROUBLESHOOTING.md)を参照。

## 1. 事前準備

- Terraform 1.14.7以上1.15.0未満、AWS CLIを用意する。ビルドはGitHub Actionsで行う。
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

CDを使用するため、[CI/CD設定](../.github/CICD.md)に従い`github_repository`と既存OIDC Providerの有無も設定する。インフラ作成後、GitHubの`dev`環境とRepository Variablesを登録する。

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

### 2.5 CDの基本設定を準備

Cognito設定をまだ反映していない場合は`terraform/terraform.tfvars`へ設定し、planを確認してapplyする。イメージはCDで指定するため`image_digest = null`のままでよい。この準備は初回または設定変更時のみ行う。

```hcl
desired_count      = 0
image_digest       = null
cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/＜ユーザープールID＞"
cognito_client_id  = "＜アプリクライアントID＞"
rollback_enabled  = false
```

Terraform変更前は`DEPLOY_ENABLED=false`にして進行中・待機中のデプロイがないことを確認する。

```bash
terraform -chdir=terraform plan -out=environment.tfplan
# Review the plan before applying it.
terraform -chdir=terraform apply environment.tfplan
```

TerraformはCPU・メモリ・環境変数・Secret参照を含む基本タスク定義を登録し、ARNをSSMへ保存する。CDはこの定義を基に実イメージのリリース用定義を登録する。Serviceの`task_definition`・`desired_count`は`ignore_changes`対象で、TerraformはCDによる更新を戻さない。

### 2.6 CDで配置・起動・動作確認

[CI/CD設定](../.github/CICD.md)に従い、`github_deployment`出力の2変数をGitHubの`dev`環境へ登録する。`AWS_ENV_ENABLED=true`・`FRONTEND_BUILD_ENABLED=true`でmainのCIを成功させる。両成果物を含むCI実行IDを控える。

`DEPLOY_ENABLED=true`にし、Actions → **Deploy release**でmainとCI実行IDを指定する。CDが成果物を検証してリリース用タスク定義を登録し、Serviceへその定義とタスク数1を同時反映する。Backend正常性確認後、Frontend配置・CloudFrontキャッシュ削除・公開URL確認まで実行する。初回専用モードや手動のS3配置は不要。

公開URLでログイン・予約・キャンセルを確認する。初回には正常稼働していたアプリの復旧先がない。初回成功後、CDを無効にして`rollback_enabled = true`をTerraformで反映し、以後維持する。基本定義の`image_digest`はnullのままでよい。

## 3. 更新デプロイ

| 更新対象 | 操作 |
| --- | --- |
| Backend・Frontend | CI成功 → 任意のタイミングで同じCI実行IDを指定して`Deploy release` |
| CPU・メモリ・環境変数 | Terraformで基本定義を更新 → 対応する成果物のCI実行IDで`Deploy release` |
| その他のインフラ | Terraform設定を変更し、新しいplanを確認してapply |

通常のアプリ更新ではdigestの設定変更やTerraform applyは不要。初回も更新もCDが1台を指定し、Terraformの`desired_count`は0のまま維持する。既存環境へ導入する際は今回のIAM・基本設定を一度applyし、サービスの停止・再作成がないことをplanで確認する。

Backend起動失敗はECSのCircuit Breakerが検出し、有効化済みなら直近の正常完了デプロイへ戻す。CDはロールバックを失敗扱いにし、Frontendを更新しない。Frontend配置・公開URL確認の失敗では全体の自動ロールバックは行わず、部分更新となる場合がある。

全体を以前のアプリへ戻す場合は、GitHub上に両Artifactsが残る以前の成功CI実行IDでCDを実行する。保持期間は30日で、手元に保存した成果物は現在のCDでは使用できず、期限超過後は別途手動復旧が必要。手動Terraformは不要だが、現在の基本設定と以前のイメージを組み合わせるため、設定・DBとの互換性を確認する。DBマイグレーションは自動で戻さない。初回失敗を含め、進行中のデプロイ終了後に原因修正して再実行する。

Frontendの環境変数変更時は成果物を再作成する。一時停止はCDを無効にし、AWS CLI等で0台に変更する。次のCDで1台に復帰する。インフラ変更・破棄前もCDを無効にし、進行中・待機中の実行がないことを確認する。

## 4. 環境の破棄

**予約データ・S3成果物・ECRイメージを削除する。RDSの最終スナップショットは作成しない。** 必要なデータを保存し、構築時と同じアカウント・Backend設定で実行する。

先にRepository Variablesの`AWS_ENV_ENABLED=false`・`DEPLOY_ENABLED=false`を設定し、成果物準備・デプロイの進行中／待機中の実行がないことを確認する。新規作成したGitHub OIDC Provider、Push／デプロイ用Role、SSM参照も削除対象になる（参照した既存Providerは保持）。

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
- タスク定義は稼働中・復旧用revisionを保護するため`skip_destroy=true`とし、destroy後もrevisionが残る。CDが登録する`-release` familyはTerraform管理外で、同様に残る。環境破棄後、対象familyの不要なrevisionをECSコンソールで登録解除する。タスク・Serviceは通常どおり破棄する。
- 公開／Origin用DNSレコードは手動で削除または更新し、ACM検証レコードは保持する。
- 再構築はState保存先を再利用し、2.2から実行する。初回設定に戻し、DNS更新と成果物の再配置を行う。
