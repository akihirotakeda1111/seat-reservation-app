import {
  execFileSync,
  execSync,
  spawn,
  type ChildProcess,
} from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { mkdirSync, openSync, closeSync } from 'node:fs';
import path from 'node:path';
import { startProvider } from '../e2e/oidc-provider.mjs';
import type { Server } from 'node:http';

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export default async function setup() {
  const container = `seat-frontend-test-${randomUUID()}`;
  let created = false;
  let backend: ChildProcess | undefined;
  let provider: Server | undefined;
  const cleanup = async () => {
    if (backend && backend.exitCode === null) {
      const exited = new Promise<void>((resolve) =>
        backend!.once('exit', () => resolve()),
      );
      backend.kill();
      await Promise.race([exited, delay(5000)]);
    }
    if (created)
      execFileSync('docker', ['rm', '-f', container], {
        stdio: 'ignore',
        windowsHide: true,
      });
    if (provider) {
      provider.closeAllConnections();
      await new Promise<void>((resolve) => provider!.close(() => resolve()));
    }
  };
  try {
    const probe = createServer();
    await new Promise<void>((resolve, reject) => {
      probe.once('error', reject);
      probe.listen(18081, '127.0.0.1', () => probe.close(() => resolve()));
    });
    execFileSync('docker', ['info'], { stdio: 'ignore', windowsHide: true });
    console.log('専用PostgreSQLと実APIを準備します');
    const password = randomUUID();
    execFileSync(
      'docker',
      [
        'run',
        '--detach',
        '--rm',
        '--name',
        container,
        '--label',
        'seat-reservation.frontend-test=true',
        '-e',
        'POSTGRES_USER=frontend_test',
        '-e',
        `POSTGRES_PASSWORD=${password}`,
        '-e',
        'POSTGRES_DB=frontend_test',
        '-p',
        '127.0.0.1::5432',
        'postgres:17',
      ],
      { stdio: 'pipe', windowsHide: true },
    );
    created = true;
    const binding = execFileSync('docker', ['port', container, '5432/tcp'], {
      encoding: 'utf8',
      windowsHide: true,
    }).trim();
    const port = binding.split(':').at(-1)!;
    let dbReady = false;
    for (let attempt = 0; attempt < 60; attempt++) {
      try {
        execFileSync(
          'docker',
          [
            'exec',
            container,
            'pg_isready',
            '-U',
            'frontend_test',
            '-d',
            'frontend_test',
          ],
          { stdio: 'ignore', windowsHide: true },
        );
        dbReady = true;
        break;
      } catch {
        await delay(500);
      }
    }
    if (!dbReady) throw new Error('テストDBが起動しませんでした');
    const backendDir = path.resolve('..', 'backend');
    provider = await startProvider();
    execSync(
      process.platform === 'win32'
        ? 'mvnw.cmd -q -DskipTests package'
        : './mvnw -q -DskipTests package',
      {
        cwd: backendDir,
        stdio: 'pipe',
        windowsHide: true,
        timeout: 180000,
      },
    );
    mkdirSync('test-results', { recursive: true });
    const log = openSync('test-results/integration-backend.log', 'w');
    backend = spawn(
      'java',
      [
        '-jar',
        path.join(
          backendDir,
          'target',
          'seat-reservation-api-0.0.1-SNAPSHOT.jar',
        ),
        '--spring.profiles.active=local',
        '--server.address=127.0.0.1',
        '--server.port=18081',
        '--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:18082',
        '--app.security.cognito-client-id=frontend-test',
        `--spring.datasource.url=jdbc:postgresql://127.0.0.1:${port}/frontend_test`,
        '--spring.datasource.username=frontend_test',
        `--spring.datasource.password=${password}`,
      ],
      { stdio: ['ignore', log, log], windowsHide: true },
    );
    closeSync(log);
    let startupError: Error | undefined;
    backend.on('error', (error) => {
      startupError = error;
    });
    let ready = false;
    for (let attempt = 0; attempt < 120; attempt++) {
      if (startupError) throw startupError;
      if (backend.exitCode !== null)
        throw new Error(
          'テストAPIが終了しました。integration-backend.logを確認してください。',
        );
      try {
        const response = await fetch(
          'http://127.0.0.1:18081/api/seats?date=2026-10-12',
          {
            signal: AbortSignal.timeout(1000),
          },
        );
        if (response.status === 401) {
          ready = true;
          break;
        }
      } catch {}
      await delay(500);
    }
    if (!ready)
      throw new Error(
        'テストAPIが起動しませんでした。integration-backend.logを確認してください。',
      );
    process.env.FRONTEND_TEST_CONTAINER = container;
    return cleanup;
  } catch (error) {
    await cleanup();
    throw error;
  }
}
