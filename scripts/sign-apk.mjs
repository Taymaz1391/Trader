#!/usr/bin/env node
/**
 * Sign an APK with v1+v2(+v3) signatures using the pure-JS apk_sign_ts library.
 * Usage: node sign-apk.mjs <in.apk> <out.apk> <key.pem> <cert.pem>
 * Env:   SIGN_LIB=<dir containing node_modules/apk_sign_ts>  (fallback when the
 *        bare specifier cannot be resolved, e.g. outside an npm project)
 */
import { readFileSync, writeFileSync } from 'node:fs';

const [, , inPath, outPath, keyPath, certPath] = process.argv;
if (!inPath || !outPath || !keyPath || !certPath) {
  console.error('usage: node sign-apk.mjs <in.apk> <out.apk> <key.pem> <cert.pem>');
  process.exit(2);
}

let mod;
try {
  mod = await import('apk_sign_ts');
} catch (e) {
  const libDir = process.env.SIGN_LIB;
  if (!libDir) throw e;
  const candidates = [
    `${libDir}/node_modules/apk_sign_ts/dist/index.js`,
    `${libDir}/node_modules/apk_sign_ts/package/dist/index.js`,
  ];
  let lastErr = e;
  for (const c of candidates) {
    try {
      mod = await import(new URL(`file://${c}`).href);
      lastErr = null;
      break;
    } catch (e2) {
      lastErr = e2;
    }
  }
  if (lastErr) throw lastErr;
}

const apk = new Uint8Array(readFileSync(inPath));
const key = readFileSync(keyPath, 'utf8');
const cert = readFileSync(certPath, 'utf8');

const signer = new mod.ApkSigner({ signingKey: mod.SigningKey.fromPEM(key, cert) });
const { signedApk } = await signer.sign(apk);
writeFileSync(outPath, Buffer.from(signedApk));
console.log(`signed ${outPath} (${signedApk.length} bytes)`);
