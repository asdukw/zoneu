import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { requireTag, verifyRemoteTag } from './release-source.mjs';
import { readIdentity } from './release-identity.mjs';

/**
 * 补丁资产（可选）。白名单而不是「目录里除 apk 之外都发」：artifact 目录里还有
 * `release-identity.json` 这类内部元数据，它们不该出现在 release 页面上。
 *
 * 没有补丁也能发版（首个带补丁能力的版本、老 tag 补发、补丁生成失败都属于没有补丁）。
 */
const PATCH_ASSET = /^(?:zoneu-patches\.json|zoneu-[0-9][^/]*-to-[0-9][^/]*-arm64-v8a\.patch(?:\.sha256)?)$/;

function patchAssets(artifactDirectory) {
  return readdirSync(artifactDirectory)
    .filter(name => PATCH_ASSET.test(name))
    .sort()
    .map(name => {
      const file = join(artifactDirectory, name);
      const stat = statSync(file);
      // 0 字节的补丁发出去只会让设备多下一次再回退全量 —— 宁可不发。
      if (!stat.isFile() || stat.size === 0) throw new Error(`补丁资产为空：${name}`);
      return file;
    });
}

function runCli(args) {
  return spawnSync('gh', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
}

export function publish({ sourceDirectory, artifactDirectory, env = process.env, runGh = runCli, verifyTag = verifyRemoteTag }) {
  requireTag(env.TAG);
  if (typeof env.GH_REPO !== 'string' || env.GH_REPO !== env.GH_REPO.trim() || !/^[\w.-]+\/[\w.-]+$/.test(env.GH_REPO)) throw new Error('缺少明确的发布仓库 GH_REPO');
  const { asset } = readIdentity(artifactDirectory, env.TAG, env.SOURCE_SHA);
  if (env.ASSET !== asset) throw new Error('release 资产名与 build 输出不匹配');
  const apk = join(artifactDirectory, asset);
  const checksum = `${apk}.sha256`;
  const bytes = readFileSync(apk);
  const digest = createHash('sha256').update(bytes).digest('hex');
  if (!bytes.length || readFileSync(checksum, 'utf8').trim() !== `${digest}  ${asset}`) throw new Error('release 资产 SHA-256 不匹配');
  const gh = args => {
    const result = runGh(args);
    if (result.error || result.status !== 0) throw new Error(`gh ${args.slice(0, 2).join(' ')} 失败，未继续公开发布`);
    return String(result.stdout);
  };
  const patches = patchAssets(artifactDirectory);
  verifyTag(sourceDirectory, env.TAG, env.SOURCE_SHA);
  const root = `repos/${env.GH_REPO}`;
  // Only an accessible repository plus a complete authenticated list can prove
  // absence. GET releases/tags can omit drafts; any API failure stays closed.
  const repositoryResponse = gh(['api', '--include', root]);
  const match = /^HTTP\/\S+\s+200[^\r\n]*\r?\n[\s\S]*?\r?\n\r?\n([\s\S]*)$/.exec(repositoryResponse);
  if (!match || JSON.parse(match[1]).full_name?.toLowerCase() !== env.GH_REPO.toLowerCase()) throw new Error('无法确认发布仓库仍可访问');
  const pages = JSON.parse(gh(['api', '--paginate', '--slurp', `${root}/releases?per_page=100`]));
  if (!Array.isArray(pages) || pages.some(page => !Array.isArray(page))) throw new Error('release 分页结果无效');
  const releases = pages.flat();
  if (releases.some(value => !value || typeof value.tag_name !== 'string' || !Number.isSafeInteger(value.id) || value.id < 1 || typeof value.draft !== 'boolean')) throw new Error('release 查询结果不符合合同');
  const existing = releases.filter(value => value.tag_name === env.TAG);
  if (existing.length > 1) throw new Error('目标 tag 有多个 release，拒绝歧义发布');
  const notes = join(sourceDirectory, 'docs/releases', `${env.TAG}.md`);
  if (existing.length) {
    gh(['release', 'upload', env.TAG, '--repo', env.GH_REPO, '--clobber', apk, checksum, ...patches]);
  } else {
    const noteArgs = existsSync(notes) ? ['--notes-file', notes] : ['--generate-notes'];
    gh(['release', 'create', env.TAG, '--repo', env.GH_REPO, '--verify-tag', '--draft', '--title', env.TAG, ...noteArgs, apk, checksum, ...patches]);
  }
  // Recheck after uploads, immediately before promoting the draft. Never create tags.
  verifyTag(sourceDirectory, env.TAG, env.SOURCE_SHA);
  const noteArgs = existsSync(notes) ? ['--notes-file', notes] : [];
  // Do not depend on newer gh edit --verify-tag. Remote tag checks above provide
  // that guard; preserve existing/generated notes without a tag-local notes file.
  gh(['release', 'edit', env.TAG, '--repo', env.GH_REPO, '--draft=false', ...noteArgs]);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    publish({ sourceDirectory: process.argv[2], artifactDirectory: process.argv[3] });
  } catch (error) { console.error(`::error::${error.message}`); process.exitCode = 1; }
}
