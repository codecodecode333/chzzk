/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 영상 파일 size limit shown and enforced by the form; keep equal to the backend's VOD_MAX_FILE_SIZE. Default 1GB. */
  readonly VITE_VOD_MAX_FILE_SIZE?: string
}
