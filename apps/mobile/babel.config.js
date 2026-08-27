/**
 * Metro's transform. `babel-preset-expo` already carries the Reanimated plugin
 * (SDK 50 onwards), so adding it here a second time is what makes worklets
 * silently stop working -- it must be last, and listing it twice moves it.
 */
module.exports = function babelConfig(api) {
  api.cache(true);
  return { presets: [['babel-preset-expo', { jsxImportSource: 'react' }]] };
};
