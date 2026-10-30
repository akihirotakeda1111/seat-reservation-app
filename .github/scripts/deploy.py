"""Register and deploy a CI release using Terraform-managed runtime settings."""
import copy
from functools import wraps
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def command(*args):
    return subprocess.check_output(args, text=True, timeout=120)


def aws(*args):
    result = command('aws', *args, '--output', 'json')
    return json.loads(result) if result.strip() else {}


class DeploymentError(ValueError):
    """A safe, application-defined message suitable for the deployment record."""


def require(condition, message):
    if not condition:
        raise DeploymentError(message)


def load_record():
    path = Path('deployment-record.json')
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}


def progress(record, stage, **details):
    record.update(stage=stage, **details)
    save_record(record)


def error_summary(error):
    # Never persist command arguments, stdout/stderr, credentials or arbitrary exception text.
    if isinstance(error, DeploymentError):
        return str(error)
    if isinstance(error, subprocess.CalledProcessError):
        return f'External command failed (exit {error.returncode}); see step logs'
    return f'{type(error).__name__}; see step logs'


def recorded(function):
    @wraps(function)
    def wrapper():
        record = {} if function.__name__ == 'download' else load_record()
        run_id = os.environ.get('RELEASE_RUN_ID', '')
        if re.fullmatch(r'[1-9][0-9]*', run_id):
            record['source_run_id'] = int(run_id)
        record.pop('error', None)
        progress(record, function.__name__, status='started')
        try:
            function(record)
        except Exception as error:
            record.update(status='failed', error=error_summary(error))
            raise
        finally:
            save_record(record)
    return wrapper


def finalize():
    record = load_record()
    if 'source_run_id' not in record:
        run_id = os.environ.get('RELEASE_RUN_ID', '')
        if re.fullmatch(r'[1-9][0-9]*', run_id):
            record['source_run_id'] = int(run_id)
    for name, stage in [('SETUP_OUTCOME', 'setup'), ('DOWNLOAD_OUTCOME', 'download'),
                        ('AUTH_OUTCOME', 'authentication'), ('DEPLOY_OUTCOME', 'deploy')]:
        if os.environ.get(name) == 'failure':
            if record.get('status') != 'failed':
                record.update(status='failed', stage=stage, error='Workflow step failed; see step logs')
            break
    if not record.get('status'):
        record.update(status='failed', stage='workflow', error='No deployment result; see step logs')
    save_record(record)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('### Deployment result\n```json\n' + json.dumps(record, indent=2) + '\n```\n')


def validate_run(run, repository):
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Release CI must have succeeded')
    require(run['path'] == '.github/workflows/ci.yml', 'Release must originate from ci.yml')
    require(run['head_branch'] == 'main' and run['event'] in ('push', 'workflow_dispatch'), 'Release must originate from main CI')
    require(run['head_repository']['full_name'] == repository, 'Unexpected source repository')
    require(re.fullmatch(r'[0-9a-f]{40}', run['head_sha']), 'Invalid release commit')


@recorded
def download(record):
    run_id = os.environ['RELEASE_RUN_ID']
    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'release_run_id must be numeric')
    repo = os.environ['GITHUB_REPOSITORY']
    run = json.loads(command('gh', 'api', f'repos/{repo}/actions/runs/{run_id}'))
    validate_run(run, repo)
    progress(record, 'download_artifacts', commit=run['head_sha'], source_run_id=run['id'])
    suffix = f"{run['head_sha']}-{run_id}-{run['run_attempt']}"
    for component in ('backend', 'frontend'):
        command('gh', 'run', 'download', run_id, '--repo', repo,
                '--name', f'{component}-release-{suffix}', '--dir', f'release/{component}')
    Path('release/source.json').write_text(json.dumps(run), encoding='utf-8')
    progress(record, 'authentication', status='prepared')


def validate_release(backend, frontend, commit, config):
    require(backend['commit'] == frontend['commit'] == commit, 'Release commits do not match')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', backend['image_digest']), 'Invalid image digest')
    require(backend['image_uri'] == config['repository_url'] + '@' + backend['image_digest'], 'Release belongs to another ECR repository')
    require(frontend['redirect_uri'] == config['application_url'] + '/auth/callback', 'Frontend was built for another public URL')


def extract_frontend(root, destination):
    archive = root / 'frontend.tar.gz'
    checksum = (root / 'SHA256SUMS').read_text().split()
    require(len(checksum) == 2 and checksum[1] == 'frontend.tar.gz', 'Unexpected checksum manifest')
    with archive.open('rb') as stream:
        require(hashlib.file_digest(stream, 'sha256').hexdigest() == checksum[0], 'Frontend checksum mismatch')
    with tarfile.open(archive, 'r:gz') as tar:
        members = tar.getmembers()
        require(len(members) <= 10000 and sum(m.size for m in members) <= 100 * 1024 * 1024, 'Frontend archive is too large')
        for member in members:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in member.name,
                    'Unsafe frontend archive path')
            require(member.isfile() or member.isdir(), 'Frontend archive cannot contain links or special files')
        tar.extractall(destination, members=members, filter='data')
    require((destination / 'index.html').is_file(), 'Frontend index.html missing')


def task_definition(baseline, image):
    require(baseline['status'] == 'ACTIVE', 'Terraform task definition is inactive')
    fields = ('family', 'taskRoleArn', 'executionRoleArn', 'networkMode',
              'containerDefinitions', 'volumes', 'placementConstraints',
              'requiresCompatibilities', 'cpu', 'memory', 'pidMode', 'ipcMode',
              'proxyConfiguration', 'inferenceAccelerators', 'ephemeralStorage', 'runtimePlatform')
    definition = {key: copy.deepcopy(baseline[key]) for key in fields if key in baseline}
    definition['family'] += '-release'
    apps = [c for c in definition['containerDefinitions'] if c['name'] == 'app']
    require(len(apps) == 1, 'Task definition must contain exactly one app container')
    environment = {entry['name']: entry['value'] for entry in apps[0].get('environment', [])}
    require(re.fullmatch(r'https://cognito-idp\.[^/]+/[^/]+', environment.get('COGNITO_ISSUER_URI', ''))
            and environment.get('COGNITO_CLIENT_ID', '').strip(),
            'Configure Cognito in the Terraform baseline before deploying')
    apps[0]['image'] = image
    return definition


def validate_service(current):
    require(current['status'] == 'ACTIVE', 'ECS service must be active')
    require(current['desiredCount'] in (0, 1), 'Only zero or one desired task is supported')
    require(current['pendingCount'] == 0 and
            not any(d.get('rolloutState') == 'IN_PROGRESS' for d in current['deployments']),
            'An ECS deployment is already active; wait for it to finish')
    require(current['deploymentConfiguration']['deploymentCircuitBreaker']['enable'],
            'Enable the ECS deployment circuit breaker')


def service(config):
    response = aws('ecs', 'describe-services', '--cluster', config['cluster'], '--services', config['service'])
    require(not response.get('failures') and len(response['services']) == 1, 'ECS service not found')
    return response['services'][0]


def wait_backend(config, arn, digest, deployment_id=None):
    started = time.monotonic()
    deadline = started + 1200
    observed = False
    while time.monotonic() < deadline:
        current = service(config)
        matching = [d for d in current['deployments'] if d['taskDefinition'] == arn
                    and (deployment_id is None or d['id'] == deployment_id)]
        require(not any(d.get('rolloutState') == 'FAILED' for d in matching), 'ECS deployment failed')
        if not observed and not matching:
            require(time.monotonic() < started + 60, 'ECS update was not observed within 60 seconds')
            time.sleep(10)
            continue
        observed = True
        require(current['desiredCount'] == 1, 'Desired count changed; stop deployment')
        require(bool(matching) and current['taskDefinition'] == arn,
                'ECS rolled back or another deployment replaced this release')
        if (current['taskDefinition'] == arn and current['runningCount'] == 1
                and current['pendingCount'] == 0 and len(current['deployments']) == 1
                and matching and matching[0].get('rolloutState') == 'COMPLETED'):
            arns = aws('ecs', 'list-tasks', '--cluster', config['cluster'],
                       '--service-name', config['service'], '--desired-status', 'RUNNING')['taskArns']
            if arns:
                tasks = aws('ecs', 'describe-tasks', '--cluster', config['cluster'], '--tasks', *arns)['tasks']
                targets = aws('elbv2', 'describe-target-health', '--target-group-arn', config['target_group'])['TargetHealthDescriptions']
                for task in tasks:
                    if task['taskDefinitionArn'] != arn:
                        continue
                    for container in task['containers']:
                        if container['name'] == 'app' and container.get('imageDigest') == digest:
                            ips = {n['privateIpv4Address'] for n in container.get('networkInterfaces', [])}
                            if any(t['Target']['Id'] in ips and t['TargetHealth']['State'] == 'healthy' for t in targets):
                                return
        time.sleep(10)
    raise DeploymentError('ECS did not reach the requested healthy release within 20 minutes')


def save_record(record):
    Path('deployment-record.json').write_text(json.dumps(record, indent=2), encoding='utf-8')


def fetch(url):
    try:
        with urlopen(Request(url, headers={'Cache-Control': 'no-cache'}), timeout=20) as response:
            return response.status, response.read()
    except HTTPError as error:
        return error.code, error.read()


def verify_public(config, index):
    for _ in range(12):
        try:
            status, body = fetch(config['application_url'] + '/')
            api_status, _ = fetch(config['application_url'] + '/api/seats?date=2026-01-01')
            if status == 200 and body == index and api_status == 401:
                return
        except (URLError, TimeoutError):
            pass
        time.sleep(10)
    raise DeploymentError('Public verification failed: expected the released index and an API 401 response')


@recorded
def deploy(record):
    progress(record, 'read_configuration')
    config = json.loads(aws('ssm', 'get-parameter', '--name', os.environ['DEPLOYMENT_PARAMETER_NAME'])['Parameter']['Value'])
    progress(record, 'validate_artifacts')
    source = json.loads(Path('release/source.json').read_text())
    backend = json.loads(Path('release/backend/backend.json').read_text())
    frontend = json.loads(Path('release/frontend/frontend.json').read_text())
    validate_release(backend, frontend, source['head_sha'], config)
    progress(record, 'extract_frontend', source_run_id=source['id'], commit=source['head_sha'], image_digest=backend['image_digest'])
    dist = Path('release/dist')
    extract_frontend(Path('release/frontend'), dist)
    # Complete preflight checks before modifying ECS or public files.
    progress(record, 'verify_image')
    aws('ecr', 'describe-images', '--repository-name', config['repository_url'].split('/', 1)[1],
        '--image-ids', 'imageDigest=' + backend['image_digest'])
    progress(record, 'validate_service')
    current = service(config)
    validate_service(current)
    progress(record, 'validate_baseline', previous_task_definition=current['taskDefinition'],
             baseline_task_definition=config['baseline_task_definition'])
    baseline = aws('ecs', 'describe-task-definition', '--task-definition', config['baseline_task_definition'])['taskDefinition']
    require(baseline['taskDefinitionArn'] == config['baseline_task_definition'], 'Unexpected Terraform task definition ARN')
    definition = task_definition(baseline, backend['image_uri'])
    progress(record, 'register_task_definition')
    Path('release/task-definition.json').write_text(json.dumps(definition), encoding='utf-8')
    arn = aws('ecs', 'register-task-definition', '--cli-input-json',
              'file://release/task-definition.json')['taskDefinition']['taskDefinitionArn']
    record['task_definition'] = arn
    save_record(record)
    progress(record, 'update_service')
    updated = aws('ecs', 'update-service', '--cluster', config['cluster'], '--service', config['service'],
                  '--task-definition', arn, '--desired-count', '1', '--force-new-deployment')
    deployment_id = next(d['id'] for d in updated['service']['deployments'] if d['status'] == 'PRIMARY')
    record['deployment_id'] = deployment_id
    save_record(record)
    progress(record, 'wait_backend')
    wait_backend(config, arn, backend['image_digest'], deployment_id)
    record['status'] = 'backend_healthy'
    save_record(record)
    progress(record, 'upload_frontend')
    # Keep previous hashed assets for old browser sessions and manual rollback.
    subprocess.run(['aws', 's3', 'sync', str(dist), 's3://' + config['bucket'] + '/',
                    '--exclude', 'index.html', '--only-show-errors'], check=True, timeout=300)
    subprocess.run(['aws', 's3', 'cp', str(dist / 'index.html'), 's3://' + config['bucket'] + '/index.html',
                    '--cache-control', 'no-cache', '--content-type', 'text/html', '--only-show-errors'], check=True, timeout=120)
    progress(record, 'invalidate_cloudfront')
    invalidation = aws('cloudfront', 'create-invalidation', '--distribution-id', config['distribution'], '--paths', '/*')['Invalidation']['Id']
    record['invalidation_id'] = invalidation
    save_record(record)
    deadline = time.monotonic() + 600
    while aws('cloudfront', 'get-invalidation', '--distribution-id', config['distribution'], '--id', invalidation)['Invalidation']['Status'] != 'Completed':
        require(time.monotonic() < deadline, 'CloudFront invalidation timed out')
        time.sleep(10)
    progress(record, 'verify_public')
    verify_public(config, (dist / 'index.html').read_bytes())
    progress(record, 'complete', status='success')


if __name__ == '__main__':
    {'download': download, 'deploy': deploy, 'finalize': finalize}[sys.argv[1]]()
