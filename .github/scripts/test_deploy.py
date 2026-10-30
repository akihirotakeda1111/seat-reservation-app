from contextlib import contextmanager
import copy
import hashlib
import io
import json
import os
import subprocess
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

    def ecs_state(self, arn='new', deployment_id='new-id', state='IN_PROGRESS', count=1):
        return {'desiredCount': count, 'runningCount': 1, 'pendingCount': 0, 'taskDefinition': arn,
                'deployments': [{'id': deployment_id, 'taskDefinition': arn, 'rolloutState': state}]}

    @patch('deploy.time.sleep')
    @patch('deploy.service')
    def test_rollback_is_not_success(self, service, sleep):
        service.side_effect = [self.ecs_state(), self.ecs_state('old', 'old-id', 'COMPLETED')]
        with self.assertRaisesRegex(deploy.DeploymentError, 'rolled back'):
            deploy.wait_backend({}, 'new', 'digest', 'new-id')

    @patch('deploy.time.sleep')
    @patch('deploy.time.monotonic', side_effect=[0, 1, 1, 60, 60])
    @patch('deploy.service')
    def test_previous_attempt_of_same_revision_is_not_success(self, service, clock, sleep):
        service.return_value = self.ecs_state('new', 'old-attempt', 'COMPLETED')
        with self.assertRaisesRegex(deploy.DeploymentError, 'not observed within 60 seconds'):
            deploy.wait_backend({}, 'new', 'digest', 'new-id')
        self.assertEqual(sleep.call_count, 1)

    @patch('deploy.time.sleep')
    @patch('deploy.aws')
    @patch('deploy.service')
    def test_stale_snapshot_then_requested_release_completes(self, service, aws, sleep):
        for count in (0, 1):
            with self.subTest(count=count):
                service.side_effect = [self.ecs_state('old', 'old-id', 'COMPLETED', count),
                                       self.ecs_state(), self.ecs_state(state='COMPLETED')]
                aws.side_effect = [{'taskArns': ['task']},
                    {'tasks': [{'taskDefinitionArn': 'new', 'containers': [{'name': 'app', 'imageDigest': 'digest',
                      'networkInterfaces': [{'privateIpv4Address': '10.0.0.1'}]}]}]},
                    {'TargetHealthDescriptions': [{'Target': {'Id': '10.0.0.1'}, 'TargetHealth': {'State': 'healthy'}}]}]
                deploy.wait_backend({'cluster': 'cluster', 'service': 'service', 'target_group': 'tg'},
                                    'new', 'digest', 'new-id')

    @patch('deploy.time.sleep')
    @patch('deploy.service')
    def test_failed_or_rescaled_observed_deployment_stops(self, service, sleep):
        for state, count, message in [('FAILED', 1, 'deployment failed'), ('IN_PROGRESS', 0, 'Desired count changed')]:
            service.side_effect = [self.ecs_state(), self.ecs_state(state=state, count=count)]
            with self.subTest(state=state), self.assertRaisesRegex(deploy.DeploymentError, message):
                deploy.wait_backend({}, 'new', 'digest', 'new-id')

    @contextmanager
    def workspace(self):
        with tempfile.TemporaryDirectory() as temp:
            previous = Path.cwd()
            try:
                os.chdir(temp)
                with patch.dict(os.environ, {'RELEASE_RUN_ID': '123', 'GITHUB_REPOSITORY': 'owner/repo',
                                'DEPLOYMENT_PARAMETER_NAME': '/deployment', 'GITHUB_STEP_SUMMARY': 'summary.md'}):
                    yield
            finally:
                os.chdir(previous)

    def test_download_failure_record_excludes_external_command_details(self):
        with self.workspace(), patch('deploy.command') as command, patch('deploy.aws') as aws:
            command.return_value = json.dumps({'id': 123, 'head_sha': 'a' * 40, 'status': 'completed',
                'conclusion': 'success', 'path': '.github/workflows/ci.yml', 'head_branch': 'main',
                'event': 'push', 'head_repository': {'full_name': 'owner/repo'}, 'run_attempt': 1})
            command.side_effect = [command.return_value,
                subprocess.CalledProcessError(1, ['gh', 'secret-token'], output='secret-output', stderr='secret-error')]
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.download()
            deploy.finalize()
            record = deploy.load_record()
            self.assertEqual(record['stage'], 'download_artifacts')
            self.assertEqual(record['status'], 'failed')
            self.assertEqual(record['source_run_id'], 123)
            self.assertNotIn('secret-', Path('deployment-record.json').read_text() + Path('summary.md').read_text())
            aws.assert_not_called()

    def test_ssm_failure_record(self):
        with self.workspace(), patch('deploy.aws', side_effect=subprocess.CalledProcessError(1, ['aws'])) as aws:
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.deploy()
            deploy.finalize()
            self.assertEqual(deploy.load_record()['stage'], 'read_configuration')
            self.assertEqual(deploy.load_record()['status'], 'failed')
            self.assertEqual(aws.call_count, 1)
            self.assertTrue(Path('summary.md').exists())

    def test_cognito_failure_is_recorded_before_any_mutation(self):
        with self.workspace():
            Path('release/backend').mkdir(parents=True)
            Path('release/frontend').mkdir()
            digest = 'sha256:' + '1' * 64
            Path('release/source.json').write_text(json.dumps({'id': 123, 'head_sha': 'a' * 40}))
            Path('release/backend/backend.json').write_text(json.dumps({'commit': 'a' * 40,
                'image_digest': digest, 'image_uri': 'registry/app@' + digest}))
            Path('release/frontend/frontend.json').write_text(json.dumps({'commit': 'a' * 40,
                'redirect_uri': 'https://app.example.com/auth/callback'}))
            self.make_archive(Path('release/frontend'))
            config = {'repository_url': 'registry/app', 'application_url': 'https://app.example.com',
                      'baseline_task_definition': 'arn:prepared:2'}
            baseline = self.baseline()
            baseline['containerDefinitions'][0]['environment'] = []
            current = self.ecs_state(state='COMPLETED')
            current.update(status='ACTIVE', deploymentConfiguration={'deploymentCircuitBreaker': {'enable': True}})
            with patch('deploy.aws', side_effect=[{'Parameter': {'Value': json.dumps(config)}}, {},
                                                 {'taskDefinition': baseline}]) as aws, \
                 patch('deploy.service', return_value=current), patch('deploy.subprocess.run') as upload:
                with self.assertRaisesRegex(deploy.DeploymentError, 'Configure Cognito'):
                    deploy.deploy()
                deploy.finalize()
                self.assertEqual(deploy.load_record()['stage'], 'validate_baseline')
                self.assertEqual(deploy.load_record()['status'], 'failed')
                self.assertFalse(any('update-service' in c.args or 'register-task-definition' in c.args
                                     for c in aws.call_args_list))
                upload.assert_not_called()

    def test_auth_failure_is_finalized_and_success_is_preserved(self):
        with self.workspace():
            deploy.save_record({'status': 'prepared', 'stage': 'authentication', 'source_run_id': 123})
            with patch.dict(os.environ, {'AUTH_OUTCOME': 'failure'}):
                deploy.finalize()
            self.assertEqual(deploy.load_record()['status'], 'failed')
            self.assertEqual(deploy.load_record()['stage'], 'authentication')
            self.assertIn('authentication', Path('summary.md').read_text())
            deploy.save_record({'status': 'success', 'stage': 'complete'})
            with patch.dict(os.environ, {'AUTH_OUTCOME': 'success', 'DEPLOY_OUTCOME': 'success'}):
                deploy.finalize()
            self.assertEqual(deploy.load_record()['status'], 'success')

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
        with self.assertRaisesRegex(deploy.DeploymentError, 'within 20 minutes'):
            deploy.wait_backend({'cluster': 'cluster', 'service': 'service', 'target_group': 'tg'}, 'new', 'digest')


if __name__ == '__main__':
    unittest.main()
