/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the backend (without /api). Embedded in the bundle at build time: never put secrets here. */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
