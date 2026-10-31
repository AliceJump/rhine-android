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

`ShelfPainter.kt` loads that quadrilateral from the metadata, centre-crops each user
cover, and maps it through Android's `Matrix.setPolyToPoly`. The transparent case is
drawn above the cover. Nearby albums recede along a diagonal, selection lifts the active
case, and separate spring transitions move the selected case and the row. Horizontal swipes, taps, visible
previous/next buttons, and accessibility actions all update the same selected album.

The native panorama uses the metadata's `axes` and `origin` to place overlapping cases
in dense rack lanes, with eight depth slots per lane. The lane bases, dividers and front
lips are drawn by Android code; no additional upstream rack texture is bundled. World
lane/slot addresses stay fixed when the window rotates or changes size. A wider window
reveals more across the rack; a taller window reveals more depth.

One persistent canvas and one pose per album connect the close row and the rack:
each case moves and changes size during expansion and collapse, including an interrupted
or reversed transition. Pinching drives expansion directly. The ordinary header and
inspector remain measured so that their fading does not change the motion endpoints.
Selection weights drive lift, neighbouring-case spacing and frosting in both arrangements;
camera motion and action-card visibility do not clear selection. An empty selection remains
empty, while a separate browse anchor determines the return position. Touch hit testing uses
the actual draw order and exposed polygons, including the top spine of an overlapping case.

Only an active zoom-in gesture can hand off from camera motion to collapse: the selected
case must be near the viewport centre and reach 90% of its close-shelf size. Remaining log
zoom from that same event controls collapse, with reversal and a rearming dead band.
Programmatic focus, rotation and unselected camera zoom cannot trigger the handoff.

The close-shelf motion takes its lift, neighbouring-case wave, and asymmetric spacing cues
from `theme/js/archive.js` (`caseTargets` and `archiveUpdate`) and the critically damped
motion in `theme/js/lib/motion.js` at the source commit above. The Android amplitudes are
adapted to the available touch viewport; the case's lift responds before the slower row.
These interactions do not import or claim to reproduce additional upstream inspection frames.

The included metadata also describes upstream hero/inspection renders. Those image
sequences are not bundled in this first Android implementation; rendering reads the
`array`, `array_res`, `axes` and array-origin entries. The selection animation is native and does not
claim to include RhinE's entire 60-frame front-facing inspection sequence.

## Memory and file access

- The case image is decoded once for a mounted shelf.
- The close shelf requests nearby covers; the panorama requests covers around its visible
  viewport, so dragging through a large library does not decode the entire collection.
- Cover decoding is on `Dispatchers.IO`. The close shelf uses a maximum decoded dimension
  of 512 px; the panorama uses 256, 192, or 128 px, depending on the visible cover count.
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
