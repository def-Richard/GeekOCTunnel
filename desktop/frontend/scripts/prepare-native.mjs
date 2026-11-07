import { copyFile, mkdir, readdir, chmod } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const project = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = process.argv[2];
if (!source) throw new Error('Usage: npm run prepare:native -- <CMake build directory> [Qt bin directory]');
const target = join(project, 'src-tauri/resources/native');
await mkdir(target, { recursive: true });
const windows = process.platform === 'win32';
const name = 'geekoctunnel-bridge' + (windows ? '.exe' : '');
await copyFile(join(resolve(source), name), join(target, name));
if (windows) {
  const qt = process.argv[3];
  if (!qt) throw new Error('Windows requires the Qt bin directory (Qt6Core/Network only).');
  for (const dll of ['Qt6Core.dll', 'Qt6Network.dll']) await copyFile(join(qt, dll), join(target, dll));
  for (const exe of ['geekoctunnel-core.exe', 'geekoctunnel-helper.exe']) await copyFile(join(resolve(source), exe), join(target, exe));
  const runtime = process.argv[4];
  const wintun = process.argv[5];
  if (!runtime || !wintun) throw new Error('Windows sessions require the patched OpenConnect runtime directory and verified wintun.dll path.');
  for (const dll of (await readdir(runtime)).filter(name => name.endsWith('.dll'))) await copyFile(join(runtime, dll), join(target, dll));
  const { createHash } = await import('node:crypto');
  const { readFile } = await import('node:fs/promises');
  if (createHash('sha256').update(await readFile(wintun)).digest('hex') !== 'e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce') throw new Error('Wintun hash mismatch');
  await copyFile(wintun, join(target, 'wintun.dll'));
  // The VC runtime must be installed on the test machine or supplied by the final installer.
} else {
  await chmod(join(target, name), 0o755);
  for (const executable of ['geekoctunnel-core', 'geekoctunnel-helper']) {
    await copyFile(join(resolve(source), executable), join(target, executable));
    await chmod(join(target, executable), 0o755);
  }
  const library = process.argv[3];
  if (!library) throw new Error('Linux sessions require the patched libopenconnect.so.5 path.');
  await copyFile(library, join(target, 'libopenconnect.so.5'));
}
console.log(`Prepared ${name}; ${ (await readdir(target)).length } native files.`);
