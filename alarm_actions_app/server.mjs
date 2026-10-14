// Alarm Actions App add-on: serves the signed APK on the internal Supervisor network only
// (TR-APP-01..04). No dependencies; the dashboard add-on proxies /apk to logged-in users.
import { createHash } from 'node:crypto';
import { createReadStream, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';

const PORT = Number(process.env.PORT || 8000);
const APK = process.env.APK_PATH || '/app/alarm_actions.apk';
const VERSION = process.env.APP_VERSION || '0.0.0';

/** versionCode as built by android/app/build.gradle.kts: major*1e6 + minor*1e3 + patch. */
function versionCode(version) {
  const m = /^(\d+)\.(\d+)\.(\d+)/.exec(version);
  return m ? Number(m[1]) * 1_000_000 + Number(m[2]) * 1_000 + Number(m[3]) : null;
}

function loadApk() {
  try {
    const size = statSync(APK).size;
    const sha256 = createHash('sha256').update(readFileSync(APK)).digest('hex');
    return { size, sha256 };
  } catch {
    return null;
  }
}

const apk = loadApk();
const info = apk && { version: VERSION, version_code: versionCode(VERSION), sha256: apk.sha256, size: apk.size };
console.log(apk ? `Serving APK ${VERSION} (${apk.size} bytes, sha256 ${apk.sha256})` : `No APK at ${APK}`);

const server = createServer((req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' }).end();
    return;
  }
  const path = (req.url || '/').split('?')[0];
  if (path === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok');
    return;
  }
  if (!info) {
    res.writeHead(503, { 'Content-Type': 'application/json' }).end('{"error":"no_apk"}');
    return;
  }
  if (path === '/info') {
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(info));
    return;
  }
  if (path === '/apk') {
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(info.size),
      'Content-Disposition': `attachment; filename="alarm-actions-${VERSION}.apk"`,
    });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    createReadStream(APK)
      .on('error', () => res.destroy())
      .pipe(res);
    return;
  }
  res.writeHead(404, { 'Content-Type': 'application/json' }).end('{"error":"not_found"}');
});

server.listen(PORT, '0.0.0.0', () => console.log(`Listening on port ${PORT}`));
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
