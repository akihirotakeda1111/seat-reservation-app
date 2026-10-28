import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import deploy


class DeploymentTests(unittest.TestCase):
    def test_initial_and_failed_services_can_deploy_but_active_deployment_cannot(self):
        service = {'status': 'ACTIVE', 'desiredCount': 0, 'runningCount': 0, 'pendingCount': 0,
                   'deployments': [{'rolloutState': 'COMPLETED'}],
                   'deploymentConfiguration': {'deploymentCircuitBreaker': {'enable': True, 'rollback': False}}}
        deploy.validate_service(service)
        deploy.validate_service({**service, 'desiredCount': 1, 'deployments': [{'rolloutState': 'FAILED'}]})
        for changed in [{'pendingCount': 1}, {'desiredCount': 2}, {'status': 'DRAINING'},
                        {'deployments': [{'rolloutState': 'IN_PROGRESS'}]}]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                deploy.validate_service({**service, **changed})

    def test_deployment_registers_release_and_starts_one_task(self):
        for count, failure in ((0, False), (1, False), (1, True)):
            with self.subTest(count=count, failure=failure), tempfile.TemporaryDirectory() as temp:
                previous = Path.cwd()
                try:
                    os.chdir(temp)
                    Path('release/backend').mkdir(parents=True)
                    Path('release/frontend').mkdir()
                    digest = 'sha256:' + '1' * 64
                    image = 'registry/app@' + digest
                    Path('release/source.json').write_text(json.dumps({'id': 123, 'head_sha': 'a' * 40}))
                    Path('release/backend/backend.json').write_text(json.dumps({
                        'commit': 'a' * 40, 'image_digest': digest, 'image_uri': image}))
                    Path('release/frontend/frontend.json').write_text(json.dumps({
                        'commit': 'a' * 40, 'redirect_uri': 'https://app.example.com/auth/callback'}))
                    self.make_archive(Path('release/frontend'))
                    config = {'repository_url': 'registry/app', 'application_url': 'https://app.example.com',
                              'baseline_task_definition': 'arn:prepared:3', 'cluster': 'cluster',
                              'service': 'service', 'bucket': 'bucket', 'distribution': 'distribution'}
                    current = {'status': 'ACTIVE', 'desiredCount': count, 'runningCount': count, 'pendingCount': 0,
                               'taskDefinition': 'arn:old:1', 'deployments': [{'rolloutState': 'COMPLETED'}],
                               'deploymentConfiguration': {'deploymentCircuitBreaker': {'enable': True, 'rollback': False}}}
                    definition = self.baseline()
                    definition['taskDefinitionArn'] = 'arn:prepared:3'
                    with patch.dict(os.environ, {'DEPLOYMENT_PARAMETER_NAME': '/deployment', 'GITHUB_STEP_SUMMARY': ''}), \
                         patch('deploy.aws', side_effect=[{'Parameter': {'Value': json.dumps(config)}}, {},
                               {'taskDefinition': definition},
                               {'taskDefinition': {'taskDefinitionArn': 'arn:release:4'}},
                               {'service': {'deployments': [{'id': 'deployment-new', 'status': 'PRIMARY'}]}},
                               {'Invalidation': {'Id': 'invalidation'}},
                               {'Invalidation': {'Status': 'Completed'}}]) as aws, \
                         patch('deploy.service', return_value=current), patch('deploy.wait_backend') as wait, \
                         patch('deploy.subprocess.run') as upload, patch('deploy.verify_public'):
                        if failure:
                            wait.side_effect = ValueError('ECS rolled back')
                            with self.assertRaisesRegex(ValueError, 'rolled back'):
                                deploy.deploy()
                            upload.assert_not_called()
                            record = json.loads(Path('deployment-record.json').read_text())
                            self.assertEqual(record['status'], 'failed')
                            self.assertEqual(record['previous_task_definition'], 'arn:old:1')
                            continue
                        deploy.deploy()
                        calls = [call.args for call in aws.call_args_list]
                        self.assertIn(('ecs', 'update-service', '--cluster', 'cluster', '--service', 'service',
                                       '--task-definition', 'arn:release:4', '--desired-count', '1', '--force-new-deployment'), calls)
                        self.assertTrue(any('register-task-definition' in call for call in calls))
                        registered = json.loads(Path('release/task-definition.json').read_text())
                        self.assertEqual(registered['family'], 'app-release')
                        self.assertEqual(registered['containerDefinitions'][0]['image'], image)
                        wait.assert_called_once_with(config, 'arn:release:4', digest, 'deployment-new')
                        self.assertEqual(json.loads(Path('deployment-record.json').read_text())['status'], 'success')
                finally:
                    os.chdir(previous)

    def test_run_provenance(self):
        run = {'status': 'completed', 'conclusion': 'success', 'path': '.github/workflows/ci.yml',
               'head_branch': 'main', 'event': 'push', 'head_repository': {'full_name': 'owner/repo'},
               'head_sha': 'a' * 40}
        deploy.validate_run(run, 'owner/repo')
        for key, value in [('conclusion', 'failure'), ('event', 'pull_request'),
                           ('head_branch', 'feature'), ('path', '.github/workflows/other.yml'),
                           ('head_repository', {'full_name': 'fork/repo'})]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                deploy.validate_run({**run, key: value}, 'owner/repo')

    def test_release_must_match_environment_and_commit(self):
        config = {'repository_url': 'registry/app', 'application_url': 'https://app.example.com'}
        digest = 'sha256:' + '1' * 64
        backend = {'commit': 'a' * 40, 'image_digest': digest, 'image_uri': 'registry/app@' + digest}
        frontend = {'commit': 'a' * 40, 'redirect_uri': 'https://app.example.com/auth/callback'}
        deploy.validate_release(backend, frontend, 'a' * 40, config)
        for changed in [{**frontend, 'commit': 'b' * 40}, {**frontend, 'redirect_uri': 'https://other.example.com/auth/callback'}]:
            with self.assertRaises(ValueError):
                deploy.validate_release(backend, changed, 'a' * 40, config)
        with self.assertRaises(ValueError):
            deploy.validate_release({**backend, 'image_uri': 'other/app@' + digest}, frontend, 'a' * 40, config)

    def baseline(self):
        return {'family': 'app', 'taskDefinitionArn': 'arn:prepared:2', 'status': 'ACTIVE',
                'cpu': '256', 'memory': '1024', 'executionRoleArn': 'exec-role', 'revision': 2,
                'runtimePlatform': {'cpuArchitecture': 'X86_64', 'operatingSystemFamily': 'LINUX'},
                'containerDefinitions': [{'name': 'app', 'image': 'registry/app:bootstrap',
                    'environment': [{'name': 'COGNITO_ISSUER_URI', 'value': 'https://cognito-idp.ap-northeast-1.amazonaws.com/pool'},
                                    {'name': 'COGNITO_CLIENT_ID', 'value': 'client'}],
                    'secrets': [{'name': 'PASSWORD', 'valueFrom': 'secret-arn'}]}]}

    def test_baseline_settings_preserved_and_only_release_image_replaced(self):
        baseline = self.baseline()
        original = copy.deepcopy(baseline)
        result = deploy.task_definition(baseline, 'registry/app@sha256:new')
        self.assertEqual(baseline, original)
        self.assertEqual(result['family'], 'app-release')
        self.assertEqual(result['containerDefinitions'][0]['image'], 'registry/app@sha256:new')
        for field in ('cpu', 'memory', 'runtimePlatform', 'executionRoleArn'):
            self.assertEqual(result[field], baseline[field])
        for field in ('environment', 'secrets'):
            self.assertEqual(result['containerDefinitions'][0][field], baseline['containerDefinitions'][0][field])
        self.assertNotIn('taskDefinitionArn', result)
        self.assertNotIn('revision', result)
        self.assertNotIn('status', result)

    def test_reject_inactive_or_unconfigured_baseline(self):
        baseline = self.baseline()
        with self.assertRaises(ValueError):
            deploy.task_definition({**baseline, 'status': 'INACTIVE'}, 'image')
        baseline['containerDefinitions'][0]['environment'] = []
        with self.assertRaisesRegex(ValueError, 'Configure Cognito'):
            deploy.task_definition(baseline, 'image')

    def make_archive(self, root, name='index.html', kind=None):
        archive = root / 'frontend.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            info = tarfile.TarInfo(name)
            if kind:
                info.type = kind
                info.linkname = '/etc/passwd'
                tar.addfile(info)
            else:
                info.size = 5
                tar.addfile(info, io.BytesIO(b'hello'))
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        (root / 'SHA256SUMS').write_text(digest + '  frontend.tar.gz\n')

    def test_extract_and_reject_tampered_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_archive(root)
            deploy.extract_frontend(root, root / 'dist')
            self.assertEqual((root / 'dist/index.html').read_text(), 'hello')
            (root / 'SHA256SUMS').write_text('0' * 64 + '  frontend.tar.gz\n')
            with self.assertRaises(ValueError):
                deploy.extract_frontend(root, root / 'other')

    def test_reject_unsafe_tar_members(self):
        for name, kind in [('../outside', None), ('/absolute', None), ('link', tarfile.SYMTYPE)]:
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.make_archive(root, name, kind)
                with self.assertRaises(ValueError):
                    deploy.extract_frontend(root, root / 'dist')

    @patch('deploy.service')
    def test_rollback_is_not_success(self, service):
        service.return_value = {'desiredCount': 1, 'taskDefinition': 'old',
                                'deployments': [{'taskDefinition': 'old', 'rolloutState': 'COMPLETED'}]}
        with self.assertRaisesRegex(ValueError, 'rolled back'):
            deploy.wait_backend({}, 'new', 'digest')

    @patch('deploy.time.sleep')
    @patch('deploy.time.monotonic', side_effect=[0, 1, 1300])
    @patch('deploy.service')
    def test_previous_attempt_of_same_revision_is_not_success(self, service, clock, sleep):
        service.return_value = {'desiredCount': 1, 'runningCount': 1, 'pendingCount': 0,
                                'taskDefinition': 'same', 'deployments': [
                                    {'id': 'old-attempt', 'taskDefinition': 'same', 'rolloutState': 'COMPLETED'}]}
        with self.assertRaises(TimeoutError):
            deploy.wait_backend({}, 'same', 'digest', 'new-attempt')

    @patch('deploy.aws')
    @patch('deploy.service')
    def test_exact_revision_digest_and_target_health(self, service, aws):
        service.return_value = {'desiredCount': 1, 'runningCount': 1, 'pendingCount': 0, 'taskDefinition': 'new',
                                'deployments': [{'taskDefinition': 'new', 'rolloutState': 'COMPLETED'}]}
        aws.side_effect = [
            {'taskArns': ['task']},
            {'tasks': [{'taskDefinitionArn': 'new', 'containers': [{'name': 'app', 'imageDigest': 'digest',
             'networkInterfaces': [{'privateIpv4Address': '10.0.0.1'}]}]}]},
            {'TargetHealthDescriptions': [{'Target': {'Id': '10.0.0.1'}, 'TargetHealth': {'State': 'healthy'}}]},
        ]
        deploy.wait_backend({'cluster': 'cluster', 'service': 'service', 'target_group': 'tg'}, 'new', 'digest')

    @patch('deploy.time.sleep')
    @patch('deploy.time.monotonic', side_effect=[0, 1, 1300])
    @patch('deploy.aws')
    @patch('deploy.service')
    def test_wrong_digest_is_not_success(self, service, aws, clock, sleep):
        service.return_value = {'desiredCount': 1, 'runningCount': 1, 'pendingCount': 0, 'taskDefinition': 'new',
                                'deployments': [{'taskDefinition': 'new', 'rolloutState': 'COMPLETED'}]}
        aws.side_effect = [{'taskArns': ['task']},
                           {'tasks': [{'taskDefinitionArn': 'new', 'containers': [{'name': 'app', 'imageDigest': 'wrong'}]}]},
                           {'TargetHealthDescriptions': []}]
        with self.assertRaises(TimeoutError):
            deploy.wait_backend({'cluster': 'cluster', 'service': 'service', 'target_group': 'tg'}, 'new', 'digest')


if __name__ == '__main__':
    unittest.main()
