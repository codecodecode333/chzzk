/**
 * The 데스크톱 앱 version the download page shows. Keep equal to `desktop/package.json`'s version (a test checks it):
 * a `v<version>` tag builds the installers into that GitHub Release.
 */
export const DESKTOP_VERSION = '0.1.0'

/**
 * Where the release workflow (`.github/workflows/desktop-release.yml`) publishes the installers. File names are fixed,
 * so `releases/latest/download/<name>` always serves the newest Release's file.
 */
const LATEST_DOWNLOAD = 'https://github.com/codecodecode333/chzzk/releases/latest/download'

export const WINDOWS_INSTALLER_URL = `${LATEST_DOWNLOAD}/chzzk-desktop-setup-x64.exe`
export const MAC_DMG_URL = `${LATEST_DOWNLOAD}/chzzk-desktop-mac-universal.dmg`
