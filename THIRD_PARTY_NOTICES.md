# Third-party notices

Rhine Android is an independent Android application. It is not an official release of
foobar2000, RhinE, or Arknights.

## RhinE / Audio Archive

- Upstream: https://github.com/ericzhang12111-cell/RhinE
- Pinned source commit: `52d5cf3c8466dd4dd13cbb33dac1a3be5b20f330`
- Copyright: **Copyright (c) 2026 the Audio Archive authors**
- License: MIT; the complete upstream notice is included in
  `app/src/main/assets/rhine/LICENSE-RhinE.txt` and therefore in the APK.

The following upstream material is included unchanged:

| Local file | Upstream file |
| --- | --- |
| `app/src/main/assets/rhine/marble/array-clear.png` | `theme/assets/render/case/marble/array-clear.png` |
| `app/src/main/assets/rhine/marble/case.json` | `theme/assets/render/case/marble/case.json` |

The native `ArchiveShelf.kt` implementation follows the sprite-and-cover composition
described by RhinE's `theme/js/archive.js`. The source commit and cover coordinates are
recorded so that future asset updates can be reviewed as an explicit change.

The original project credits Blender for the rendered art and notes that some renders
use third-party material libraries under their respective licenses. This import contains
the rendered MARBLE image distributed in the MIT-licensed upstream repository, not the
underlying third-party material libraries. Original franchise names, characters, music,
and other third-party intellectual property are not licensed by this project's MIT
license. Music and cover art are supplied by the user; this repository contains no music
from the reference video.

## Oxanium typeface

- Project: https://github.com/sevmeyer/oxanium
- Copyright: **Copyright 2019 The Oxanium Project Authors**
- License: SIL Open Font License 1.1.
- Full license: `app/src/main/assets/rhine/OFL-Oxanium.txt`, included in the APK.
- Imported unchanged from the same pinned RhinE commit:
  `Oxanium-Regular.ttf` → `app/src/main/res/font/oxanium_regular.ttf`, and
  `Oxanium-SemiBold.ttf` → `app/src/main/res/font/oxanium_semibold.ttf`.

Renaming the resource filenames for Android does not alter the fonts or their internal
family names. No other upstream font is bundled.

## Android libraries

AndroidX (including Compose, Lifecycle, and Media3) is distributed under the Apache
License 2.0. Kotlin and kotlinx.coroutines are distributed under the Apache License 2.0.
Their dependency coordinates and versions are declared in the Gradle build files.

## Integrity

`app/src/main/assets/rhine/upstream.json` records the upstream paths, Git blob SHAs,
and SHA-256 checksums of the imported files. See `docs/UPSTREAM.md` for the update process.
