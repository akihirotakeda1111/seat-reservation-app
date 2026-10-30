# CI/CDの設定と成果物

CIで検証・成果物作成を行い、任意のタイミングで`Deploy release`を起動して公開する。初回・通常更新ともに同じCDを使用し、通常リリースのTerraform実行は不要。

インフラ構築・設定変更・破棄は[Terraform README](../terraform/README.md)、設計詳細は[CI/CD設計](../doc/system-design/cicd-design.md)を参照。

## 初回設定

1. Settings → Environmentsで`dev`を作成し、Deployment branches and tagsで**mainブランチのみ許可**する（タグは許可しない）。
2. `terraform.tfvars`に`github_repository`・`github_owner_id`・`github_repository_id`を設定する。数値IDは以下で取得する。既存OIDC Providerがあれば`github_oidc_provider_arn`にARNを指定し、なければnullとする。
3. TerraformでIAM・SSM・Cognito設定を含む基本タスク定義を反映する。初期値は`desired_count=0`・`image_digest=null`・`rollback_enabled=false`。

```bash
gh api repos/OWNER/REPOSITORY --jq '{owner_id: .owner.id, repository_id: .id}'
```

**Environment Variables**（Settings → Environments → dev → Variables）に登録する。AWSアクセスキーは使用せず、OIDCで認証する。

| Variable | 設定値・取得方法 |
| --- | --- |
| `AWS_ECR_PUSH_ROLE_ARN` | `terraform -chdir=terraform output -raw github_ecr_push_role_arn` |
| `ECR_REPOSITORY_URL` | `terraform -chdir=terraform output -raw ecr_repository_url` |
| `AWS_DEPLOY_ROLE_ARN` | `terraform -chdir=terraform output -json github_deployment`の同名の値 |
| `DEPLOYMENT_PARAMETER_NAME` | 同上の同名の値 |
| `VITE_COGNITO_AUTHORITY` | Cognito User PoolのIssuer URL |
| `VITE_COGNITO_CLIENT_ID` | SPA用クライアントID |
| `VITE_COGNITO_DOMAIN` | CognitoログインドメインのHTTPS URL |
| `VITE_AUTH_REDIRECT_URI` | `https://公開FQDN/auth/callback` |
| `VITE_AUTH_LOGOUT_URI` | `https://公開FQDN/login` |

**Repository Variables**（Settings → Secrets and variables → Actions → Variables）に登録する。実行条件に使うため、Environment VariablesやSecretsには置かない。

| Variable | `true`で有効になる処理 |
| --- | --- |
| `AWS_ENV_ENABLED` | BackendのECR Push |
| `FRONTEND_BUILD_ENABLED` | Frontendの公開用成果物作成 |
| `DEPLOY_ENABLED` | 手動起動したCDによるデプロイ |

未設定・`false`なら対象処理をスキップする。CIの検証は継続する。

## 実行と受け渡し

- CIはmain向けPR・mainへのPush・手動起動で実行する。手動実行はActions → **CI** → Run workflowでmainを選ぶ。
- mainのCI検証成功後、有効化したBackend・Frontendの成果物を作成する。PR・開発ブランチでは検証のみ行う。
- BackendはECRへPushし、`backend-release-*`に`backend.json`を保存。Frontendは`frontend-release-*`に圧縮成果物・チェックサム・コミット情報を保存する。この時点では公開されない。
- Artifactsの保持期間は**30日**。CDにはGitHub上の両Artifactsが必要で、期限切れ後の手元保存分は利用できない。

## デプロイ（初回・通常更新共通）

1. 両成果物が揃った成功済みmain CIの実行IDを控える。URLの`/actions/runs/`直後の数字を使う。
2. `DEPLOY_ENABLED=true`を確認する。
3. Actions → **Deploy release** → Run workflowで**main**を選び、`release_run_id`へCI実行IDを入力する。

CDは最新attemptの成果物を検証し、リリース用タスク定義登録 → ECSを1台で起動・更新 → Backend正常性確認 → S3配置 → CloudFrontキャッシュ削除 → 公開URL確認を行う。再ビルド・Terraform実行は行わない。同一環境のデプロイは直列実行し、上限は45分。

- **初回成功後**：ログイン・予約・キャンセルを確認し、Terraformで`rollback_enabled=true`へ変更する。初回には正常稼働していたアプリの復旧先がない。
- **失敗時**：ステップログを確認する。タスク定義登録以降は`deployment-*` Artifact・Summaryにも結果が残る。Backendのロールバックは失敗扱いとし、Frontend更新へ進まない。Frontend失敗時の全体自動ロールバックは行わない。
- **復旧時**：原因修正後に再実行するか、保持期間内の以前の成功CI実行IDを指定する。現在の基本設定と以前のアプリを組み合わせるため、設定・DBとの互換性を確認する。DBは自動で戻らない。期限切れ成果物は別途手動復旧が必要。
- **設定変更時**：CPU・メモリ・Backend環境変数はTerraformで基本定義を更新してからCDを実行する。Frontend環境変数は成果物を再作成する。

## 破棄・再構築

Terraform変更前は`DEPLOY_ENABLED=false`にし、実行中・待機中のデプロイがないことを確認する。破棄前は`AWS_ENV_ENABLED=false`にして成果物準備の終了も待つ。TerraformとCDはロックを共有しない。

破棄範囲・残るタスク定義revisionの整理は[Terraform README](../terraform/README.md#4-環境の破棄)に従う。再構築後はRole ARN・ECR URL・SSM Parameter名と公開設定を確認し、成果物準備・デプロイを再度有効化する。
