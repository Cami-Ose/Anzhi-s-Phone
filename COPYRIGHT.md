# Copyright and License Map

This file maps the main copyright and licensing boundaries in this public snapshot.
It is an index, not a replacement for the license text or third-party notices.

## Original Anzhi's Phone code

Original Anzhi's Phone code is covered by `LICENSE-CODE.md` only where this map explicitly identifies it as original code. Files with mixed or unresolved provenance are not automatically covered by that license.

Main original areas:

- `backend/`
- `device/`
- `default-permissions/`
- `local_manifests/`
- `sepolicy/`
- `vendor/anzhi/`
- `scripts/`
- `rom/`
- `app/src/main/java/com/anzhi/os/` files not listed below

The public snapshot is a curated copy and may contain files whose history is still being reviewed. When in doubt, treat the stricter notice as controlling and open an issue before reusing the file.

## OpenCyvis-derived code

The following files contain code explicitly identified in the private source tree as being derived from OpenCyvis. They remain subject to the Apache License 2.0 and the attribution requirements in `third-party/OpenCyvis-NOTICE.md`:

- `app/src/main/java/com/anzhi/os/action/AnzhiAction.kt`
- `app/src/main/java/com/anzhi/os/action/ActionExecutor.kt`
- `app/src/main/java/com/anzhi/os/action/AppLauncher.kt`
- `app/src/main/java/com/anzhi/os/action/StepResult.kt`

The current public snapshot does not include the old OpenCyvis `backend/`, `display/`, or `input/` directories. Those names appear in historical private build notes, but no such directories are present in this snapshot. The current `ActionExecutor.kt` uses the newer platform API implementation and should still be treated as mixed OpenCyvis-derived and Anzhi-modified code.

Anzhi-specific additions and modifications remain attributable to Cami-Ose, but the Apache 2.0 permissions for the OpenCyvis-derived portions must not be removed or narrowed.

## AOSP, Android, LineageOS, and library material

AOSP/Android/LineageOS material and bundled libraries retain their upstream licenses. This project does not relicense upstream material. Users are responsible for reviewing the relevant upstream notices when building a complete ROM.

The build system and public Android APIs are not claims of ownership over the upstream projects.

## UI, visual design, and assets

The following areas contain Anzhi's Phone visual design or visual assets by Cami-Ose and are **not** covered by `LICENSE-CODE.md`:

- `app/src/main/assets/chat.html`
- `app/src/main/assets/dashboard.html`
- `app/src/main/assets/fusion-pixel.otf`
- `app/src/main/java/com/anzhi/os/ui/`
- `app/src/main/java/com/anzhi/os/chat/`
- `app/src/main/java/com/anzhi/os/dashboard/`
- `app/src/main/java/com/anzhi/os/dream/`
- visual portions of `app/src/main/res/`

These materials are covered by `LICENSE-DESIGN.md`. Functional code embedded in a visual file remains subject to the applicable code/source notice, but the visual arrangement, styling, artwork, and assets remain reserved.

## Brand

The names `Anzhi's Phone`, `安知手机`, related logos, characters, and branding are not licensed for use as a way to imply endorsement or official compatibility. Separate permission is required for branding use.
