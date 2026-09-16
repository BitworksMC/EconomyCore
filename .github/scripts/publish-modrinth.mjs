import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const repository = process.env.GITHUB_REPOSITORY || 'BitworksMC/EconomyCore';
const tag = process.env.RELEASE_TAG;
const dryRun = process.env.DRY_RUN !== 'false';
const token = process.env.MODRINTH_TOKEN;
const config = JSON.parse(await readFile(new URL('../modrinth.json', import.meta.url), 'utf8'));
const headers = { 'User-Agent': 'BitworksMC/EconomyCore release automation' };

async function modrinth(path, options = {}) {
  const response = await fetch(`https://api.modrinth.com/v2/${path}`, {
    ...options,
    headers: { ...headers, ...options.headers },
    signal: AbortSignal.timeout(120000),
  });
  if (!response.ok) {
    throw new Error(`Modrinth ${options.method || 'GET'} ${path}: HTTP ${response.status}`);
  }
  return response.json();
}

if (!tag || !/^[\w.-]+$/.test(tag)) {
  throw new Error('RELEASE_TAG must be an existing release tag containing letters, digits, dots, underscores or hyphens.');
}
if (!dryRun && !token) {
  throw new Error('MODRINTH_TOKEN is required for publishing.');
}

const release = JSON.parse(execFileSync('gh', [
  'api', `repos/${repository}/releases/tags/${encodeURIComponent(tag)}`,
], { encoding: 'utf8' }));
if (release.draft) {
  throw new Error('Publish the GitHub release before uploading to Modrinth.');
}
const version = tag.replace(/^v(?=\d)/, '');
const existing = await modrinth(`project/${config.project_id}/version`);
const temporary = await mkdtemp(join(tmpdir(), 'tne-modrinth-'));

try {
  const uploads = [];
  for (const variant of config.variants) {
    const filename = `TNE-${variant.artifact}-${version}.jar`;
    const asset = release.assets.find(candidate => candidate.name === filename);
    if (!asset || asset.state !== 'uploaded' || asset.size === 0) {
      throw new Error(`Missing or incomplete release asset: ${filename}. Attach all JARs before publishing the release.`);
    }
    execFileSync('gh', [
      'release', 'download', tag, '--repo', repository,
      '--pattern', filename, '--dir', temporary,
    ], { stdio: 'pipe' });
    const bytes = await readFile(join(temporary, filename));
    if (bytes.length !== asset.size || bytes.readUInt32LE(0) !== 0x04034b50) {
      throw new Error(`Invalid JAR download: ${filename}`);
    }
    const sha512 = createHash('sha512').update(bytes).digest('hex');
    const matching = existing.filter(candidate =>
      candidate.version_number === version &&
      [...candidate.loaders].sort().join(',') === [...variant.loaders].sort().join(','));
    if (matching.length) {
      if (matching.length !== 1 || !matching[0].files.some(file => file.filename === filename && file.hashes.sha512 === sha512)) {
        throw new Error(`Conflicting Modrinth release for ${filename}; refusing to overwrite it.`);
      }
      console.log(`Already published, identical file: ${filename}`);
      continue;
    }
    uploads.push({ filename, bytes, data: {
      project_id: config.project_id,
      name: `The New Economy ${version} (${variant.artifact})`,
      version_number: version,
      changelog: release.body || `GitHub release: ${release.html_url}`,
      version_type: release.prerelease ? 'beta' : 'release',
      loaders: variant.loaders,
      game_versions: variant.game_versions,
      dependencies: [],
      featured: false,
      status: 'listed',
      file_parts: ['file'],
      primary_file: 'file',
    } });
  }

  for (const upload of uploads) {
    if (dryRun) {
      console.log(`Would publish ${upload.filename}: ${JSON.stringify(upload.data)}`);
      continue;
    }
    const form = new FormData();
    form.append('data', JSON.stringify(upload.data));
    form.append('file', new Blob([upload.bytes], { type: 'application/java-archive' }), upload.filename);
    const published = await modrinth('version', {
      method: 'POST', headers: { Authorization: token }, body: form,
    });
    console.log(`Published ${upload.filename}: https://modrinth.com/plugin/${config.project_id}/version/${published.id}`);
  }
  console.log(dryRun ? 'Dry run complete; nothing published.' : 'Modrinth publishing complete.');
} finally {
  await rm(temporary, { recursive: true, force: true });
}
