// Flat-config for ESLint 9. Uses only the plugins already in package.json:
//   - @typescript-eslint
//   - eslint-plugin-react-hooks
//   - eslint-plugin-react-refresh
// Run with: npm run lint  (which is "eslint .")
import js from "@eslint/js";
import tseslint from "@typescript-eslint/eslint-plugin";
import tsparser from "@typescript-eslint/parser";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";

export default [
  {
    ignores: [
      "dist/**",
      "build/**",
      "node_modules/**",
      "playwright-report/**",
      "playwright-report-e2e/**",
      "test-results/**",
      "test-results-e2e/**",
      "coverage/**",
      "vite.config.ts",
    ],
  },
  js.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      parser: tsparser,
      parserOptions: {
        ecmaVersion: "latest",
        sourceType: "module",
        ecmaFeatures: { jsx: true },
      },
      globals: {
        window: "readonly",
        document: "readonly",
        console: "readonly",
        fetch: "readonly",
        WebSocket: "readonly",
        URLSearchParams: "readonly",
        URL: "readonly",
        Headers: "readonly",
        RequestInit: "readonly",
        HTMLInputElement: "readonly",
        HTMLTextAreaElement: "readonly",
        setTimeout: "readonly",
        clearTimeout: "readonly",
        setInterval: "readonly",
        clearInterval: "readonly",
        process: "readonly",
        React: "readonly",
      },
    },
    plugins: {
      "@typescript-eslint": tseslint,
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...tseslint.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "no-empty": ["warn", { allowEmptyCatch: true }],
    },
  },
  {
    files: ["e2e/**/*.{ts,tsx,mjs,js}", "playwright*.config.ts"],
    languageOptions: {
      globals: {
        process: "readonly",
        console: "readonly",
        URL: "readonly",
        fetch: "readonly",
        setTimeout: "readonly",
        clearTimeout: "readonly",
        crypto: "readonly",
        TextEncoder: "readonly",
        TextDecoder: "readonly",
      },
    },
  },
  {
    files: ["*.config.{js,cjs,mjs}", "tailwind.config.{js,cjs}", "postcss.config.{js,cjs}"],
    languageOptions: {
      sourceType: "module",
      globals: {
        require: "readonly",
        module: "readonly",
        __dirname: "readonly",
        __filename: "readonly",
        process: "readonly",
      },
    },
  },
];
