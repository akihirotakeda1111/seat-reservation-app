"""Register and deploy a CI release using Terraform-managed runtime settings."""
import copy
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


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_run(run, repository):
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Release CI must have succeeded')
    require(run['path'] == '.github/workflows/ci.yml', 'Release must originate from ci.yml')
    require(run['head_branch'] == 'main' and run['event'] in ('push', 'workflow_dispatch'), 'Release must originate from main CI')
    require(run['head_repository']['full_name'] == repository, 'Unexpected source repository')
    require(re.fullmatch(r'[0-9a-f]{40}', run['head_sha']), 'Invalid release commit')


def download():
    run_id = os.environ['RELEASE_RUN_ID']
    require(re.fullmatch(r'[1-9][0-9]*', run_id), 'release_run_id must be numeric')
    repo = os.environ['GITHUB_REPOSITORY']
    run = json.loads(command('gh', 'api', f'repos/{repo}/actions/runs/{run_id}'))
    validate_run(run, repo)
    suffix = f"{run['head_sha']}-{run_id}-{run['run_attempt']}"
    for component in ('backend', 'frontend'):
        command('gh', 'run', 'download', run_id, '--repo', repo,
                '--name', f'{component}-release-{suffix}', '--dir', f'release/{component}')
    Path('release/source.json').write_text(json.dumps(run), encoding='utf-8')


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
    deadline = time.monotonic() + 1200
    while time.monotonic() < deadline:
        current = service(config)
        require(current['desiredCount'] == 1, 'Desired count changed; stop deployment')
        matching = [d for d in current['deployments'] if d['taskDefinition'] == arn
                    and (deployment_id is None or d['id'] == deployment_id)]
        require(not any(d.get('rolloutState') == 'FAILED' for d in matching), 'ECS deployment failed')
        if not matching and current['taskDefinition'] != arn:
            raise ValueError('ECS rolled back or another deployment replaced this release')
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
    raise TimeoutError('ECS did not reach the requested healthy release within 20 minutes')


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
    raise ValueError('Public verification failed: expected the released index and an API 401 response')


def deploy():
    config = json.loads(aws('ssm', 'get-parameter', '--name', os.environ['DEPLOYMENT_PARAMETER_NAME'])['Parameter']['Value'])
    source = json.loads(Path('release/source.json').read_text())
    backend = json.loads(Path('release/backend/backend.json').read_text())
    frontend = json.loads(Path('release/frontend/frontend.json').read_text())
    validate_release(backend, frontend, source['head_sha'], config)
    dist = Path('release/dist')
    extract_frontend(Path('release/frontend'), dist)
    # Complete preflight checks before modifying ECS or public files.
    aws('ecr', 'describe-images', '--repository-name', config['repository_url'].split('/', 1)[1],
        '--image-ids', 'imageDigest=' + backend['image_digest'])
    current = service(config)
    validate_service(current)
    baseline = aws('ecs', 'describe-task-definition', '--task-definition', config['baseline_task_definition'])['taskDefinition']
    require(baseline['taskDefinitionArn'] == config['baseline_task_definition'], 'Unexpected Terraform task definition ARN')
    definition = task_definition(baseline, backend['image_uri'])
    record = {'status': 'started', 'source_run_id': source['id'], 'commit': source['head_sha'],
              'previous_task_definition': current['taskDefinition'], 'image_digest': backend['image_digest'],
              'baseline_task_definition': config['baseline_task_definition']}
    save_record(record)
    try:
        Path('release/task-definition.json').write_text(json.dumps(definition), encoding='utf-8')
        arn = aws('ecs', 'register-task-definition', '--cli-input-json',
                  'file://release/task-definition.json')['taskDefinition']['taskDefinitionArn']
        record['task_definition'] = arn
        save_record(record)
        updated = aws('ecs', 'update-service', '--cluster', config['cluster'], '--service', config['service'],
                      '--task-definition', arn, '--desired-count', '1', '--force-new-deployment')
        deployment_id = next(d['id'] for d in updated['service']['deployments'] if d['status'] == 'PRIMARY')
        record['deployment_id'] = deployment_id
        save_record(record)
        wait_backend(config, arn, backend['image_digest'], deployment_id)
        record['status'] = 'backend_healthy'
        save_record(record)
        # Keep previous hashed assets for old browser sessions and manual rollback.
        subprocess.run(['aws', 's3', 'sync', str(dist), 's3://' + config['bucket'] + '/',
                        '--exclude', 'index.html', '--only-show-errors'], check=True, timeout=300)
        subprocess.run(['aws', 's3', 'cp', str(dist / 'index.html'), 's3://' + config['bucket'] + '/index.html',
                        '--cache-control', 'no-cache', '--content-type', 'text/html', '--only-show-errors'], check=True, timeout=120)
        invalidation = aws('cloudfront', 'create-invalidation', '--distribution-id', config['distribution'], '--paths', '/*')['Invalidation']['Id']
        record['invalidation_id'] = invalidation
        save_record(record)
        deadline = time.monotonic() + 600
        while aws('cloudfront', 'get-invalidation', '--distribution-id', config['distribution'], '--id', invalidation)['Invalidation']['Status'] != 'Completed':
            require(time.monotonic() < deadline, 'CloudFront invalidation timed out')
            time.sleep(10)
        verify_public(config, (dist / 'index.html').read_bytes())
        record['status'] = 'success'
    except Exception as error:
        record['status'] = 'failed'
        record['error'] = str(error)
        raise
    finally:
        save_record(record)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                summary.write('### Deployment result\n```json\n' + json.dumps(record, indent=2) + '\n```\n')


if __name__ == '__main__':
    {'download': download, 'deploy': deploy}[sys.argv[1]]()
