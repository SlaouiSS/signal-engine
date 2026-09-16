import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // src/api/generated is produced by openapi-typescript (see src/api/README.md) and is never
  // hand-edited or hand-formatted.
  { ignores: ['dist', 'coverage', 'src/api/generated'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
  },
  {
    files: ['**/*.{js,ts}'],
    ignores: ['src/**'],
    languageOptions: {
      globals: globals.node,
    },
  },
);
