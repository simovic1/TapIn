#!/usr/bin/env node
// Generates a VAPID key pair for web push (P-256, RFC 8292) without extra packages.
// Output: VAPID_PUBLIC_KEY (uncompressed point) and VAPID_PRIVATE_KEY (scalar d), both base64url.
// Put them into the environment of the backend (.env locally, secret store in staging/prod). Never commit them.
// A new pair invalidates all existing browser subscriptions (members have to switch push on again).
import { generateKeyPairSync } from 'node:crypto';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const pub = publicKey.export({ format: 'jwk' });
const priv = privateKey.export({ format: 'jwk' });
const raw = Buffer.concat([Buffer.from([4]), Buffer.from(pub.x, 'base64url'), Buffer.from(pub.y, 'base64url')]);

console.log(`VAPID_PUBLIC_KEY=${raw.toString('base64url')}`);
console.log(`VAPID_PRIVATE_KEY=${priv.d}`);
