# CI/CD設計

## 1. 目的・対象範囲

GitHub Actionsで検証・成果物準備を行い、成功したCI実行IDを指定して任意のタイミングでデプロイする。初回・通常更新ともに同じCDを使用し、リリース用タスク定義の登録、1タスク起動、Frontend配置まで自動化する。

インフラと基本タスク定義は手動Terraformで管理する。通常リリースではTerraformを実行せず、CD内での再ビルドも行わない。

AWS構成と管理境界は[AWSアーキテクチャ設計](aws-architecture.md)、意思決定の経緯は[ADR-007](../adr/adr-007.md)、設定・操作手順は[CI/CD設定](../../.github/README.md)を参照する。障害対応の方針と詳細手順の参照先は第9章に示す。

## 2. 配置・ワークフロー構成

CI/CDに関係する主なファイルは以下とする。

```text
doc/
├── system-design/
│   ├── aws-architecture.md
│   └── cicd-design.md
└── adr/
    └── adr-007.md

terraform/
├── bootstrap/
├── tests/
└── README.md

.github/
├── scripts/
│   ├── deploy.py
│   ├── test_deploy.py
│   ├── validate-frontend-env.mjs
│   └── validate-frontend-env.test.mjs
└── workflows/
    ├── ci.yml
    ├── build-backend.yml
    ├── build-frontend.yml
    └── deploy.yml
```

| ワークフロー | 責務 |
|---|---|
| `ci.yml` | Backend・Frontendのビルドとテスト、Terraform検証、成功後の成果物準備ワークフロー呼び出し |
| `build-backend.yml` | Backendイメージのビルド、ECRへのPush、digest出力 |
| `build-frontend.yml` | Reactの公開用ビルド、Artifactsへの成果物保存 |
| `deploy.yml` | 成果物の出所検証、リリース用タスク定義登録、ECS更新、S3配置、CloudFront無効化、正常性確認 |

## 3. 実行条件・AWS認証

### 3.1 実行条件

Pull RequestでCIを実行し、mainのCI成功後に公開用の成果物を準備する。CI失敗時はCDを実行しない。

成果物準備は`ci.yml`から再利用ワークフローとして呼び出し、検証した同じコミットを使用する。デプロイは独立した`deploy.yml`を`workflow_dispatch`で起動し、成功したCI実行IDを指定する。

| 項目 | 設計内容 |
|---|---|
| CIの実行トリガー詳細・対象ブランチ・対象パス | main向けPR、mainへのPush、手動起動。対象パスによる省略なし |
| Backend成果物準備の実行トリガー詳細 | mainのCI成功後、Repository Variableの`AWS_ENV_ENABLED=true`で実行 |
| Frontend成果物準備の実行トリガー詳細 | mainのCI成功後、Repository Variableの`FRONTEND_BUILD_ENABLED=true`で実行 |
| CI成功を実行条件にする具体方式 | 成果物準備は`needs`で全CIジョブの成功を要求し、PRでは実行しない。デプロイは指定CI実行の成功を検証 |
| 初回・通常デプロイの実行条件 | `DEPLOY_ENABLED=true`でmainから手動起動。成功したmain CIの実行IDを指定し、最新attemptの両成果物を使用。同一環境で直列化 |
| Terraform plan / applyの実行条件 | インフラ・基本設定の構築・変更・破棄時に手動実行 |
| Terraform applyの承認方式 | planを手動確認し、保存したplanファイルをapply |

### 3.2 AWS認証・秘密情報

- Backend成果物準備とデプロイのAWS認証はGitHub OIDC + 専用IAM Roleを使用する。手動Terraform・AWS CLI操作は対象AWSアカウントへの認証を事前に行う。
- 長期有効なAWSアクセスキーは利用しない。
- ECR Push用IAM Roleには必要最小限の権限を付与する。
- AWSアクセスキーやDBパスワードをリポジトリに保存しない。
- Terraform Stateをリポジトリにコミットしない。

| 項目 | 設計内容 |
|---|---|
| OIDCの信頼条件・IAM Role構成・ARN | 所有者・リポジトリの数値IDを含む新形式の`sub`で、対象リポジトリの`dev`環境とSTS audienceに限定。GitHub環境側でmainのみ許可。Role ARNはTerraform出力を登録 |
| 各ワークフローの具体的なIAM権限 | Push用Roleとデプロイ用Roleを分離。後者はSSM参照・リリース用familyへのタスク定義登録・対象ECRのイメージ存在確認・対象ECS更新・実行RoleのPassRole・対象S3配置・CloudFront無効化・正常性参照を許可。State権限なし |
| GitHub Actionsに渡す設定値と保管先 | 有効化フラグはRepository Variables、Role ARN・ECR URL・SSM Parameter名（`DEPLOYMENT_PARAMETER_NAME`）・Frontend公開設定は`dev`のEnvironment Variables |

設定値と登録手順は[CI/CD設定](../../.github/README.md)を参照する。

DB認証情報の管理方式とTerraform State管理方式は[AWSアーキテクチャ設計](aws-architecture.md)を参照する。

## 4. CI設計

CIはGitHub-hosted Runnerで実行する。

### 4.1 Backend

1. JDKをセットアップする。
2. Mavenでビルドする。
3. Unit Testを実行する。
4. TestcontainersによるIntegration Testを実行する。
5. Spring Securityの認証認可テストを実行する。

認証認可テストでは既存のMock JWTを使用し、CI内ではCognitoへの実ログインを行わない。

### 4.2 Frontend

1. Node.jsをセットアップする。
2. 依存関係をインストールする。
3. テストとビルドを実行する。

### 4.3 CI設定

| 設定項目 | 内容 |
|---|---|
| Runner | Ubuntu 24.04 |
| ツール | Temurin JDK 25、Node.js 24、Terraform 1.14.7、RunnerのPython 3 |
| Backend | `sh ./mvnw -B -ntp verify`成功後、`backend`で`docker build --tag seat-reservation-app:ci .`を実行。イメージのPush・起動は行わない |
| Frontend | `npm ci`、`npm run format:check`、`npm test`、`npm run build`、公開設定検証スクリプトのテスト |
| デプロイ処理テスト | Frontendジョブで`test_deploy.py`を実行。成果物検証、基本定義の保持、起動・更新、更新反映待ち、失敗判定・記録をモックで検証し、AWSへは接続しない |
| ブラウザテスト | Chromeをインストールし、`test:e2e`と`test:integration`を別Runnerで実行。各1 worker、`--forbid-only`指定 |
| Terraform | root・bootstrapで`fmt -check -recursive`、`init -backend=false -lockfile=readonly`、`validate`。rootで`test`（AWSモック） |
| 検証ジョブの権限 | `contents: read`。AWS認証・Stateへの接続なし。成果物準備・デプロイの権限は3.2を参照 |
| 実行上限 | Backend・ブラウザ各20分、Frontend・Terraform各10分 |
| 診断情報 | Surefireレポート、Playwrightの失敗時trace・結合テストAPIログをArtifactsに7日間保存 |

## 5. アプリケーションCD設計

### 5.1 Backend

```text
成果物準備：CI検証成功 → OIDC認証 → Docker Build → ECR Push → リリース情報保存

任意実行：CI実行IDを指定 → 成果物検証 → OIDC認証 → SSMから基本定義取得
    → イメージ差し替え・リリース用定義登録 → ECS Service更新（1タスク）
    → Backend正常性確認 → Frontend配置（5.2）
```

| 項目 | 設計内容 |
|---|---|
| コンテナイメージのタグ・バージョン管理方式 | SHA・実行ID・再実行番号で一意なタグを付与し、変更不可とする。デプロイはdigestで固定 |
| ECS Task Definitionの更新方法 | 基本定義はTerraform管理。CDはSSMのARNで基本定義を取得し、成果物のイメージへ差し替えて`-release` familyに登録。Serviceへ定義と1台を同時指定。`task_definition`・`desired_count`はignore_changes対象 |
| ECSへのアプリケーション設定の注入方法 | DB接続先・Cognitoは環境変数、DB認証情報はSecrets Managerから起動時に注入 |
| デプロイの稼働確認・失敗判定 | CDで指定revision・digest・ALB Healthyを確認。起動猶予は既定300秒。サーキットブレーカーで失敗を検出 |
| Actionsでの待機時間・失敗判定・再試行 | Backend成果物準備は上限25分。デプロイ全体は45分、ECS待機は20分（更新対象デプロイが参照結果に現れるまでの最大60秒を含む）。指定デプロイ・revision・digest・ALB Healthyを確認し、ロールバックや別デプロイへの置換は失敗扱い |

基本定義の正確なARNと配置先はTerraformからSSMへ保存する。リリース情報の保持期間は30日。デプロイにはPython 3.13を使用する。ECS更新には停止時間が発生する。

### 5.2 Frontend

```text
成果物準備：CI検証成功 → 公開設定検証 → React Build → Artifacts保存

任意実行：指定CIの成果物を検証・展開 → Backend正常性確認を待つ
    → S3配置（index.htmlは最後）→ CloudFrontキャッシュ無効化 → 公開URL確認
```

| 項目 | 設計内容 |
|---|---|
| 公開用ビルド時の環境設定 | `dev`のEnvironment VariablesにCognito Authority・Client ID・Domain、Redirect URI・Logout URIを登録し、検証後にビルド |
| S3配置・古いファイルの扱い | 旧アセットは削除せず、index.htmlを最後に配置。初回・通常更新ともにActions |
| CloudFrontキャッシュ無効化 | 配置後に`/*`を無効化し、最大10分間完了を待機 |
| Actionsへの上記手順の組み込み | 成果物準備とデプロイを分離。指定CIの成功・コミット一致・配置先・チェックサムを確認して保存済み成果物を配置 |
| ビルド成果物の保存・世代管理方法 | tar.gz・チェックサム・コミット情報をArtifactsに30日間保存 |

環境変数はビルド時に埋め込まれるため、変更後はActionsで成果物を再作成してからデプロイする。公開時はBackendと同じコミットの成果物を使用し、再ビルドしない。CDでの復旧はGitHub上に成果物が残っている期間に限る。期限超過後の手元保存分は現在のCDでは扱えず、別途手動復旧が必要。

## 6. インフラ更新・削除

インフラと基本タスク定義は手動Terraformで管理する。planを確認してapplyし、基本設定の変更はその後のCDで稼働タスクへ反映する。具体的なコマンドは[Terraform README](../../terraform/README.md)を参照する。

| 項目 | 設計内容 |
|---|---|
| 初回構築 | ECRを含む環境・基本設定を作成し、ECSは0タスク。CI成功後にCDで1タスク起動・Frontend配置 |
| 管理分担 | Terraformの`desired_count`は作成時の0を維持。稼働定義・タスク数はCDが更新し、通常リリースのTerraform実行は不要 |
| State | `terraform/bootstrap`で独立したS3を構築。GitHub Actionsからはアクセスしない |
| 同時実行の禁止 | Terraform変更前に`DEPLOY_ENABLED=false`とし、実行中・待機中のCDがないことを確認。破棄前は`AWS_ENV_ENABLED=false`にして成果物準備の終了も待つ |
| 破棄 | 手動でdestroy planを確認して適用。アプリ環境・Push／デプロイRole・SSM・新規OIDC Providerを削除 |
| 維持するリソース | 既存Cognito・State保存先・手動管理のRoute 53／ACM・既存共有OIDC Provider |
| タスク定義の整理 | 基本定義は`skip_destroy`、CDの`-release` familyはTerraform管理外のため残る。不要revisionは環境破棄後に手動登録解除 |

## 7. DBマイグレーション

- 初期構成ではSpring Boot起動時のFlywayマイグレーションを継続する。
- DBへのアクセスはECSから行う。
- GitHub ActionsからPrivate RDSへ直接接続しない。
- DBマイグレーションは原則として前方互換にし、アプリケーションのロールバック時にも動作するようにする。
- Build & ScrapによってRDSの予約データが失われることを許容する。

## 8. デプロイ後検証

| 検証対象 | 成功条件 | 実行方法 |
|---|---|---|
| ECS Service・タスク | 指定デプロイが完了し、指定revision・digestで1タスク稼働 | CDで自動確認 |
| ALB Target Group | 対象タスクがHealthy。HTTP:8080の`/actuator/health`が200 | ALBのヘルスチェック結果をCDで確認 |
| Frontend | CloudFront無効化完了後、公開ルートのHTMLが成果物と一致 | CDで自動確認 |
| API | 公開URLの未認証`/api/seats`が401を返す | CDで自動確認 |
| 認証・業務操作 | 公開URLでログイン・予約・キャンセルが成功 | 手動確認 |

ECS起動猶予は`health_check_grace_period_seconds`で指定し、既定300秒とする。Cognito実認証E2EのCI自動化はPhase 4の必須範囲に含めない。

## 9. 障害・失敗時の対応

- CI失敗時はデプロイせず、原因修正後に再実行する。
- BackendのロールバックはCD失敗として扱い、Frontend更新へ進まない。
- Frontend失敗時の全体自動ロールバックとDB変更の自動巻き戻しは行わない。

ログ・結果記録の確認、再試行・ロールバック、Terraform更新・破棄時の対応、および今後の検討事項は[障害調査・復旧手順](../../terraform/TROUBLESHOOTING.md)に集約する。
