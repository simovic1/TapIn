// Fails when the committed generated types differ from a fresh generation (contract changed without `npm run types`).
import { readFileSync, rmSync } from 'node:fs';

const [committed, fresh] = process.argv.slice(2);
const normalize = (path) => readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
const same = normalize(committed) === normalize(fresh);
rmSync(fresh, { force: true });

if (!same) {
  console.error(`${committed} is out of date with openapi.yaml. Run: npm --prefix api run types`);
  process.exit(1);
}
console.log('Generated API types are up to date.');
