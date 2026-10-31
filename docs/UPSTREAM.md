# RhinE asset provenance and Android adaptation

## Source snapshot

- Repository: https://github.com/ericzhang12111-cell/RhinE
- Commit: `52d5cf3c8466dd4dd13cbb33dac1a3be5b20f330`
- Retrieved: 2026-10-31
- Theme: **MARBLE**, light/archive appearance from the supplied reference video.
- License: see `THIRD_PARTY_NOTICES.md` and the notices packaged under `assets/rhine/`.

The Android implementation is independently built with Kotlin, Compose, and Media3.
RhinE's foobar2000, JSplitter, GDI, and Windows installation interfaces are not Android
dependencies. The original pre-rendered specimen case is used directly so that the
visual material stays faithful to the reference.

## How the shelf is rendered

`array-clear.png` is a 1200 × 1200 transparent image. The unchanged `case.json` uses a
600 × 600 logical coordinate space (`array_pixels = 2`). Its album-cover quadrilateral is:

| Corner | x | y |
| --- | ---: | ---: |
| Top left | 148.60 | 136.66 |
| Top right | 369.09 | 239.25 |
| Bottom right | 369.09 | 448.34 |
| Bottom left | 148.60 | 345.74 |

`ArchiveShelf.kt` loads that quadrilateral from the metadata, centre-crops each user
cover, and maps it through Android's `Matrix.setPolyToPoly`. The transparent case is
drawn above the cover. Nearby albums recede along a diagonal, selection lifts the active
case, and a spring transition moves between albums. Horizontal swipes, taps, visible
previous/next buttons, and accessibility actions all update the same selected album.

The native panorama mode reuses this sprite and cover mapping in a responsive multi-row
collection. It adds touch zoom/pan, animated focus, and an action card above the selected
case. The layout reflows with the available window size; these Android interactions do
not import or claim to reproduce additional upstream inspection frames.

The included metadata also describes upstream hero/inspection renders. Those image
sequences are not bundled in this first Android implementation; the code reads only
the `array` and `array_res` entries. The selection animation is native and does not
claim to include RhinE's entire 60-frame front-facing inspection sequence.

## Memory and file access

- The case image is decoded once for a mounted shelf.
- The close shelf requests nearby covers; the panorama requests covers around its visible
  viewport, so dragging through a large library does not decode the entire collection.
- Cover decoding is on `Dispatchers.IO`, with a maximum decoded dimension of 512 px.
- The in-memory cover cache is capped at 8 MiB.
- Cover paths must resolve inside the application's private files or cache directory.
- Empty or unavailable cover art uses an original geometric placeholder drawn by code.

## Updating assets

1. Select a new upstream commit explicitly, rather than downloading from a moving branch.
2. Read its license and any resource-specific notices before importing material.
3. Replace the image and metadata together; the cover quadrilateral must match the image.
4. Update `docs/UPSTREAM.md`, `THIRD_PARTY_NOTICES.md`, and
   `app/src/main/assets/rhine/upstream.json` together. Keep the source commit, original
   paths, Git blob SHAs, checksums, and license notices consistent across the records,
   and preserve the relevant license text in the APK.
5. Inspect the shelf on a phone and a wider display with square and non-square user
   cover art, and check selection and touch hit targets.

If an additional font, model, render, or audio asset is added, record its own source and
license. The current package imports only the MARBLE array sprite, case metadata, and
two unmodified Oxanium font files.
