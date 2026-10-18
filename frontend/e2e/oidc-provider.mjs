// 自動テスト専用のOIDC発行元。本番コードからは読み込まない。
import { createServer } from 'node:http';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export function startProvider(port = 18082) {
  const issuer = `http://127.0.0.1:${port}`;
  const clientId = 'frontend-test';
  const { publicKey, privateKey } = generateKeyPairSync('rsa', {
    modulusLength: 2048,
  });
  const jwk = {
    ...publicKey.export({ format: 'jwk' }),
    kid: 'test-key',
    use: 'sig',
    alg: 'RS256',
  };
  const codes = new Map();
  const encode = (value) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');
  const jwt = (claims) => {
    const body = `${encode({ alg: 'RS256', kid: 'test-key', typ: 'JWT' })}.${encode(claims)}`;
    return `${body}.${sign('RSA-SHA256', Buffer.from(body), privateKey).toString('base64url')}`;
  };
  const allowed = (value, path) =>
    ['5173', '5174'].some((p) => value === `http://127.0.0.1:${p}${path}`);
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, issuer);
    if (
      ['http://127.0.0.1:5173', 'http://127.0.0.1:5174'].includes(
        request.headers.origin,
      )
    ) {
      response.setHeader('Access-Control-Allow-Origin', request.headers.origin);
      response.setHeader('Access-Control-Allow-Headers', 'content-type');
    }
    if (request.method === 'OPTIONS') {
      response.writeHead(204).end();
      return;
    }
    const json = (data, status = 200) => {
      response.writeHead(status, {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      });
      response.end(JSON.stringify(data));
    };
    if (url.pathname === '/.well-known/openid-configuration')
      return json({
        issuer,
        authorization_endpoint: `${issuer}/authorize`,
        token_endpoint: `${issuer}/token`,
        jwks_uri: `${issuer}/jwks`,
        response_types_supported: ['code'],
        subject_types_supported: ['public'],
        id_token_signing_alg_values_supported: ['RS256'],
        code_challenge_methods_supported: ['S256'],
        token_endpoint_auth_methods_supported: ['none'],
      });
    if (url.pathname === '/jwks') return json({ keys: [jwk] });
    if (url.pathname === '/logout') {
      const destination = url.searchParams.get('logout_uri');
      if (!allowed(destination, '/login'))
        return json({ error: 'invalid_request' }, 400);
      response.writeHead(302, { Location: destination }).end();
      return;
    }
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = new URLSearchParams(Buffer.concat(chunks).toString());
    if (url.pathname === '/authorize') {
      const params = url.searchParams;
      if (
        params.get('client_id') !== clientId ||
        params.get('response_type') !== 'code' ||
        params.get('code_challenge_method') !== 'S256' ||
        !params.get('code_challenge') ||
        !allowed(params.get('redirect_uri'), '/auth/callback')
      )
        return json({ error: 'invalid_request' }, 400);
      if (request.method === 'GET') {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        response.end(
          '<!doctype html><html lang="ja"><title>テスト用ログイン</title><form method="post"><label>テストユーザー<select name="user"><option>demo-user-1</option><option>demo-user-2</option><option>admin-user</option></select></label><button>認証する</button></form></html>',
        );
        return;
      }
      const user = body.get('user');
      if (!['demo-user-1', 'demo-user-2', 'admin-user'].includes(user))
        return json({ error: 'invalid_request' }, 400);
      const code = randomUUID();
      codes.set(code, {
        user,
        redirect: params.get('redirect_uri'),
        challenge: params.get('code_challenge'),
        nonce: params.get('nonce'),
        time: Date.now(),
      });
      const destination = new URL(params.get('redirect_uri'));
      destination.searchParams.set('code', code);
      destination.searchParams.set('state', params.get('state'));
      response.writeHead(302, { Location: destination.href }).end();
      return;
    }
    if (url.pathname === '/token' && request.method === 'POST') {
      const code = body.get('code');
      const record = codes.get(code);
      codes.delete(code);
      const challenge = createHash('sha256')
        .update(body.get('code_verifier') ?? '')
        .digest('base64url');
      if (
        !record ||
        Date.now() - record.time > 60000 ||
        body.get('grant_type') !== 'authorization_code' ||
        body.get('client_id') !== clientId ||
        body.get('redirect_uri') !== record.redirect ||
        record.challenge !== challenge
      )
        return json({ error: 'invalid_grant' }, 400);
      const now = Math.floor(Date.now() / 1000);
      const common = {
        iss: issuer,
        sub: record.user,
        iat: now,
        exp: now + 600,
      };
      return json({
        token_type: 'Bearer',
        expires_in: 600,
        scope: 'openid',
        access_token: jwt({
          ...common,
          token_use: 'access',
          client_id: clientId,
          ...(record.user === 'admin-user'
            ? { 'cognito:groups': ['ADMINS'] }
            : {}),
        }),
        id_token: jwt({
          ...common,
          aud: clientId,
          ...(record.nonce ? { nonce: record.nonce } : {}),
        }),
      });
    }
    json({ error: 'not_found' }, 404);
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await startProvider();
  console.log('Test OIDC issuer: http://127.0.0.1:18082');
}
