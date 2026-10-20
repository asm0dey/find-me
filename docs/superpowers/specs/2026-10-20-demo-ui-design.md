# find-me demo UI: design

## Intent

A small desktop window that shows find-me working, for a YouTube Short / TikTok. In the vertical video the window takes up
one half of the 9:16 frame (about 1080×960, an aspect of 9:8). The author is in the other half, talking about Java and the
project, so the window has to read on a phone, with no sound, without anyone explaining it.

It runs the real pipeline: the same models, the same averaged face print, the same 0.40 threshold. Everything it shows was
computed by the program. The only staged part is that the scan is slowed down, and the window says so.

## Goals

- A viewer understands "it learned my face, it went through the album, it found me" without audio.
- Shows the "movie face-match" look (the CSI / Bourne trope), built only from real data: real face boxes, the 5 real
  landmarks, real similarity scores.
- Shows that Python (gallery-dl) runs inside the JVM, as an on-screen phase the author can talk over.
- One run is one take: start it, record, cut in editing. Phases come in a fixed order so they are easy to cut.

## Non-goals

- Not a product UI. No file pickers, settings, buttons, or menus; arguments come from the command line.
- No change to how the CLI (`findme.FindMe`) behaves, including its native image.

## Anti-goals (a failure even if it works)

- Fake numbers or fake visuals: an invented percentage, a 3D wireframe sweep, a "confidence" that isn't the score.
- A similarity shown in a way that reads as a probability without saying what it is.
- Hiding the slowdown: the scan is artificially slowed and the screen must say so.
- Anything unreadable at phone size: small text, boxes too thin to see, too many photos at once.

## Constraints

- UI library: [swing-tree](https://codeberg.org/globaltcad/swing-tree) (the author's choice).
- One executable, jar and native image alike: `find-me --demo <ref-dir> <album-or-dir>...` starts the demo; without
  `--demo` the CLI is unchanged. Both modes work from the native image (author's requirement), sharing one
  reachability-metadata set per platform; Liberica NIK supports Swing, so add metadata only where a run shows it's needed.
- Lives in this repo, reuses `Faces` and the listing/scan code in `FindMe`. Java 25, Maven, same platforms as the CLI
  (Linux CUDA, macOS CoreML, CPU fallback).
- The demo writes no `seen.txt` / `matches.txt`, so takes can be repeated with the same result.
- Input is the same as the CLI: a reference folder and one or more album links (Flickr was chosen for the recording; local
  folders should keep working because the CLI supports them).

## What the author decided

- **Layout "C1":** left third shows the author, right two thirds show the photo being scanned, header on top, similarity
  bar at the bottom.
- **Reference shown as a mosaic** of the author's aligned face crops, not an "average face" image and not a learning
  intro animation that merges them.
- **Movie flavor, real data only:** landmark dots, a lock-on on matches, a stamp.
- **Stamp text:** "MATCH" large, "similarity 71%" smaller beneath it. Face boxes show the short form ("71%").
  Percent is the cosine similarity × 100, rounded. The threshold shows as 40%.
- **Flickr albums** as the input for the recording.
- **Show the album listing** as its own phase, with a live counter (cutting is easier than adding).
- **Slow the scan down on purpose** and label it on screen.

## The screen

Window content is 9:8 (design at 1080×960; the author scales the capture in editing). Dark theme:

| Token | Value | Use |
|---|---|---|
| background | `#0F172A` | window |
| card | `#1B2336` | panels |
| muted text | `#94A3B8` | labels |
| match | `#22C55E` | match box, bar fill, stamp |
| no match | `#EF4444` | miss box, ✗ |
| scanning | `#38BDF8` | box while a face is still being scored, if shown |
| landmarks | `#FDE047` | the 5 dots |
| text | `#F8FAFC` | numbers |

Font: Pragmasevka Nerd Font, Regular and Bold (the author's choice; installed on the recording machine at
`/usr/share/fonts/TTF/pragmasevka-nf-*.ttf`). Bundle the two TTFs in the jar and load them with `Font.createFont`, so
the look doesn't depend on what is installed; it is OFL-licensed like Iosevka, so bundling is allowed. Its glyphs are
narrower than most monospace fonts, which leaves more room at the minimum sizes below. Palette from the ui-ux-pro-max
design-system search ("developer tool, dark").

### Phone readability (hard requirement from the author)

Everything on screen must be readable in the Short on a phone. The 1080 px-wide half-frame shows at about 390 pt wide
on a phone, so it is scaled down to roughly 0.36×. In design pixels at 1080×960:

- No text under 32 px. Labels ("YOU", "THIS PHOTO", "threshold 40%") 32–36 px, the header title ("FOUND 2") 64 px or more, header progress
  numbers ("37 / 215") 44 px or more, the stamp's "MATCH" 120 px or more.
- Face box strokes at least 6 px, ✗ at least 160 px.
- Landmarks are not text and must never hide the face (author feedback): small hollow rings sized to the face (≈6% of
  the box width, 4–10 px), joined by thin lines (eyes, eyes–nose, nose–mouth corners, mouth) for a movie-style mesh.
- If something can't meet these sizes it gets dropped, not shrunk. For example, on a crowded group photo the score labels
  of small faces are left out and only the best face's score is drawn.
- Fewer words: no "THIS PHOTO" label, "YOU · 30" instead of "YOU · 30 photos", the header tag reads just "slowed down",
  the mosaic is 3 columns wide. `all-screens-phone.html` shows the result at phone scale.
- Check: export a frame, scale it to 390 px wide, and read every element on a real phone.

Regions:

- **Header:** "FOUND n" large on the left; on the right, progress "37 / 215" ("37 / 412+" while albums are still
  being listed) plus a small "slowed down" tag, shown only while the display is behind the pipeline.
- **Left third, "YOU · N photos":** a grid of the author's aligned face crops, "+K more" when they don't fit.
  Crops are warped from the original reference photo with the ArcFace template scaled ×4 (448×448), so they stay sharp
  when shown large; the model still gets its own 112×112 input.
- **Right two thirds, "THIS PHOTO":** the current photo, fit to the panel. On it: a box per detected face, colored match /
  no match by that face's own score against 0.40, the face's score above the box ("9%", "71%"), the 5 landmark dots.
  When the photo is done: a match gets the MATCH stamp, a miss a large ✗ in the corner.
- **Bottom:** "similarity 71%" / "threshold 40%" labels over a bar filled to the best face's score, with a white tick
  at 40%.

Mockups from the session are in `.superpowers/brainstorm/*/content/`. `all-screens-phone.html` is the final one, drawn at 1080×960 with the real minimum sizes and shown at phone scale; older files
(`c1-movie.html`) predate the slowdown decision and the percent format.

## The sequence

Listing starts at launch, on its own thread, in parallel with learning; each album feeds the scan as soon as it is
listed. The display is paced to a deadline 110 s after launch.

1. **Learning your face.** Each reference photo is processed; its crop appears in the mosaic as it is embedded.
   Photos that are skipped (zero or several faces) don't appear. When the crops overflow the mosaic, it shows the newest
   (the face just learned stays in view) and a "+K" tile counts the rest. The bottom line shows the parallel listing's
   progress, "Python inside the JVM · album k/n · N photos".
2. **Listing.** If learning finishes before the first photo is scanned, the header reads "LISTING", album k/n and an
   elapsed timer since launch; the right panel a counter of photos found that ticks up live (a callback from
   gallery-dl's `DataJob` into Java per listed photo) and "Python inside the JVM" (gallery-dl on GraalPy).
3. **Scan.** The real pipeline runs at full speed, as in the CLI (parallel downloads, the same inference), and puts
   finished photos on a queue in completion order. Every album joins the scan as soon as it is listed, so the total
   grows while listing goes on ("37 / 412+") and loses the "+" once the last album is listed. The display takes photos
   off that queue at a pace that fits the rest into the time left before the deadline: misses 30–150 ms, matches
   1.0–1.5 s. While the total still grows, misses go at the 30 ms floor and the pipeline sets the pace. The display
   never runs ahead of the real scan: with nothing queued it waits for the next photo. The pipeline is never delayed,
   only the display.
   - Miss: the photo shows with red boxes and an ✗.
   - Match: the green box glows and tightens onto the face (lock-on, ~300 ms), the "MATCH / similarity 71%" stamp
     lands, the bar fills past the 40% tick; it holds, then continues.
   - Photos with no faces show briefly with an ✗ and an empty bar.
   - Photos that fail to download or decode are skipped without being shown (my call).
   - The header counter counts displayed photos. The "slowed down" tag shows only while the display is behind the
     pipeline (the queue is not empty or the pipeline has finished). If the pipeline is slower than the display pace,
     the display simply waits and the tag hides, so it never claims a slowdown that isn't happening.
   - The queue holds the downloaded bytes plus detections, not decoded images, so a few hundred photos stay in tens of MB.
4. **End: gallery of found photos.** The whole window below the header becomes a grid of the matched photos, each with
   the green box on the matching face and its score ("71%"). The header reads "FOUND 3" on the left and "of 215" /
   "real 72.4 s" on the right. Held until the window is closed.
   - Grid size follows the match count so each photo stays phone-readable: 1 fills the area, 2 side by side, 3–4 as 2×2,
     5–6 as 3×2. More than 6: a 3×2 grid of the 5 highest scores, with "+N more" as the sixth tile (N = matches − 5).
   - No matches: "NOT FOUND" in place of the grid.
   - Matched photos are kept (their bytes and detections) during the scan for this.
   - "real" is the real pipeline's wall time from the first photo scanned to the last result; listing overlaps it and
     is included. Learning before the first photo is not. Because the display never slows the pipeline, this is
     directly measured, not estimated.

## Behavior the builder needs to know

- Today `Faces.embeddings` returns only embeddings; detections (box, score, 5 landmarks) are private. The demo needs them
  per face, plus the aligned crop for reference photos. Exposing that without changing the CLI's output or embeddings is
  the core code change.
- The CLI scans 64 photos in flight on virtual threads; the demo keeps that and only paces the display (see Scan).
  Display order is completion order, not album order.
- Pacing numbers (30–150 ms, 1.0–1.5 s, 300 ms, the 110 s deadline) are values to tune while recording.

## Left to the builder

- Swing-tree version and how the frame is structured in it; drawing overlays (custom painting on the photo panel is fine).
- How the UI thread and the pipeline thread talk.
- How `FindMe` code is shared (extracting methods vs. calling them) as long as the CLI behaves the same.
- Exact animation curves, font sizes above the minimums.
- Idiomatic swing-tree: read its agent guide `docs/agent-skills/SKILL.md` in the swing-tree repo
  (codeberg.org/globaltcad/swing-tree) first. Maven Central: `io.github.globaltcad:swing-tree:1.1.1`.
- Render at exactly 1080×960 device pixels on the author's HiDPI Linux desktop (e.g. `sun.java2d.uiScale=1`).
- Tests: a check for anything non-trivial added to `Faces` (e.g. detections still produce the same embeddings).

## Calls made (not discussed with the author)

- Demo mode is a `--demo` first argument of the same executable (author's call), same other arguments as the CLI.
- Failed photos are skipped silently in the demo.
- 448×448 crops for the mosaic.
- End screen stays until the window is closed.
- The slowdown is done by pacing the display off a queue, not by delaying the pipeline, so the real time is measurable.
