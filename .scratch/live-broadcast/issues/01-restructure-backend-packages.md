# 01: 백엔드 패키지 재구성 (prefactor)

**What to build:** The backend's root package is named after VOD, but 방송 is about to become a sibling feature. Restructure it so that a neutral application root holds VOD as one feature module, with room for a 방송 module next to it. The shared Problem Details error handling (and anything else not VOD-specific) lives outside either feature. This ticket changes no behaviour; it only makes the next tickets easy. See the spec "Prefactor" decision (`.scratch/live-broadcast/spec.md`).

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] The application root package is feature-neutral, with VOD code in its own feature module beneath it
- [x] The shared Problem Details handler and generic web concerns are outside the VOD module and usable by future features
- [x] No API, schema, or UI behaviour changes; all existing backend and frontend tests pass unchanged (except package/import lines)
- [x] The README's conventions section reflects the new layout
