/**
 * Metro in a pnpm workspace.
 *
 * Two things are not defaults. `watchFolders` reaches the repository root
 * because `@ideanest/design-tokens` and `@ideanest/api-client` are symlinked
 * out of `apps/mobile`, and a bundler that does not watch the real directory
 * serves a stale copy of a package you just edited. `disableHierarchicalLookup`
 * stays off and `nodeModulesPaths` names both stores, because pnpm's layout is
 * not the flat `node_modules` Metro assumes and a transitive dependency
 * resolves from the store rather than from beside the importer.
 */
const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
];
config.resolver.unstable_enableSymlinks = true;

module.exports = config;
