# Main Scanner — locked behavioural reference

Derived by frame-by-frame inspection of `main cam.mp4` (52.7 s, 392×848, 30 fps), extracted at
1 fps end-to-end plus 6 fps across the two critical transitions. This file records **observed
behaviour only**. It is the acceptance reference for every Main Scanner slice.

Parity target is *behavioural*. No branding, wording, iconography, colour values, artwork or
layout is copied from the reference app; every visual element in this project is our own design
expressing the same functional behaviour.

## Stage 1 — Dashboard

A single prominent capture affordance opens the scanner directly. No intermediate chooser.

## Stage 2 — Camera capture surface

Observed at t≈4–16 s and t≈24–28 s.

- **Clean, full-bleed preview.** No quadrilateral, no corner brackets, no guide frame, no HUD
  text, no confidence readout. The preview is unobstructed at all times.
- Top chrome (dark, over the preview): close affordance (left), flash toggle, a quality badge,
  overflow (right).
- Bottom chrome: a horizontally scrollable mode row with the active mode marked by an accent
  label **and** an accent underline; `Single` is the active mode throughout.
- Primary control: a large circular manual shutter, light fill inside an accent ring, centred.
  Flanked by secondary import affordances (images, files) and a features grid.
- **Manual shutter is the only capture trigger observed.** Edge detection, if running, is silent —
  it never draws.

## Stage 3 — Shutter → Crop transition

Observed at 6 fps, t≈26.0–29.0 s. This is the transition the implementation must reproduce exactly.

1. Shutter is tapped. The live preview **remains visible and streaming** — it is not blanked,
   frozen white, or torn down.
2. The Crop surface **slides in horizontally from the trailing edge**, and it is **already
   showing the captured frame** as it slides. At no point in the 18 sampled frames is there a
   blank, white, tiny, or stale image.
3. A small centred card with an animated indeterminate indicator and the label `Processing…`
   sits over the retained image while corner detection runs.
4. The card disappears; the detected polygon appears. Detection latency observed ≈ 1.5–2 s.

**Hard requirement:** the captured image is on screen *before* detection completes. Detection
never gates image visibility.

## Stage 4 — Crop surface

Observed at t≈18–20 s and t≈29–31 s.

- Top chrome: back affordance + title `Crop`.
- The captured frame is letterboxed on a dark background — full frame, not pre-cropped.
- **Automatically detected four-corner polygon**, a true quadrilateral (observed with a visibly
  slanted top edge tracking the document, not an axis-aligned rectangle).
- Eight handles: four filled circles at the corners, four capsules at the edge midpoints
  (horizontal capsules top/bottom, vertical capsules left/right). All draggable.
- The region outside the polygon is dimmed; inside renders at full brightness.
- Bottom action bar, four equal actions with icon + label: rotate-left, rotate-right,
  apply-to-all, and advance.
- **Back shows a confirmation dialog**: a titled note asking whether to discard the image, with a
  neutral cancel action and an accent-filled destructive confirm action. While the dialog is up,
  the bottom action bar is visibly disabled.
- Confirming discard returns to the **camera surface**, ready to reshoot — not to the dashboard.
  (The reference briefly shows a black preview during camera rebind here; our implementation must
  not — the preview must be retained or re-shown without a blank frame.)

## Stage 5 — Advance → enhancement

Observed at 6 fps, t≈31.0–33.5 s.

1. On advance, the **cropped, perspective-corrected page is displayed immediately** in its
   un-enhanced state. The image is never removed from screen.
2. Chrome reduces to a back affordance only — no title yet.
3. A **thin determinate progress bar** runs along the bottom above a dark bar with the centred
   label `Enhancing Image…`. Progress was observed advancing from ≈2 % to full.
4. As the bar completes, the **enhanced pixels replace the un-enhanced ones in place** — brighter
   whites, higher saturation. Same geometry, same position, no reflow, no flash.

## Stage 6 — Enhanced page review

Observed at t≈33 s onward.

- Top chrome: back affordance, the auto-generated document title rendered as an **editable**
  field (dashed underline), grid affordance.
- A compare affordance is overlaid on the image's top-trailing corner.
- Bottom toolbar: rotate-left, markup, extract-text (with an unread badge), sign — then a wide,
  accent-filled **confirm button carrying a check glyph**, visually dominant and clearly the
  primary action.

## Stage 7 — Confirm → saved document

- Confirm produces **exactly one saved document** and navigates **directly into that document's
  viewer**. No intermediate "document ready" screen, no processing screen, no list bounce.
- Viewer chrome: back, truncated title with a rename affordance, tag affordance, grid, overflow.
- A page-position indicator (`1/1`) overlays the image's leading corner.
- Bottom toolbar: add page, edit, share, convert, sign.

## Behavioural invariants extracted from the reference

| # | Invariant | Enforced from slice |
|---|---|---|
| 1 | Preview is clean — no permanent live polygon or guide overlay | 1 |
| 2 | Manual shutter is first-class and always available | 1 |
| 3 | Live preview keeps streaming through capture; never blanked by the shutter | 1 |
| 4 | The captured frame is visible on the next surface before detection finishes | 1 |
| 5 | No blank, white, tiny, or stale frame at any transition | 1 |
| 6 | Capture routes to the dedicated crop workflow, never a generic result screen | 1 |
| 7 | No document row is written during capture | 1 |
| 8 | Back/discard removes app-owned temporaries and nothing else | 1 |
| 9 | Detection may run silently to seed the crop polygon | 1 |
| 10 | Crop polygon is auto-seeded, a true quadrilateral, with 8 draggable handles | 4 |
| 11 | Discard is confirmed by an explicit dialog | 1 (dialog), 4 (crop surface) |
| 12 | Enhancement retains the image and shows determinate progress | 5 |
| 13 | Enhanced pixels replace preview pixels in place — saved == displayed | 5 |
| 14 | One confirm equals exactly one saved document | 7 |
| 15 | Confirm navigates straight into the saved-document viewer | 7 |
| 16 | Processing failure never falls back to raw, unprocessed pixels | 3, 5 |
