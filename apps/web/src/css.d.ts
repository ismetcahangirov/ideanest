/**
 * Next declares `*.module.css` but not a plain stylesheet, and TypeScript
 * checks side-effect imports now — so `import './globals.css'` needs to be told
 * that the module exists and exports nothing.
 */
declare module '*.css';
