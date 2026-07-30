# Main Scanner real-scene edge fixtures

Three frames of one physical scene: a package lying on a bright, diagonally tiled floor.

## Why these exist

Every synthetic Main Scanner detector fixture uses a clean object on a uniform background. A tiled
floor does not behave that way — it supplies a dozen long, strong, perfectly straight grout lines at
several angles, and those compete with an object's own edges on exactly the evidence a detector
ranks. A detector can pass every synthetic case and still fail here.

These frames exist so that future detector work is measured against a real scene rather than against
shapes invented to match the implementation.

## What they are

The exact bounded `LumaFrame` the analyzer consumes — written straight out of the analysis path on a
real device, not a screenshot and not a re-processed photo. 240x180, 8-bit grayscale, downscaled from
the 640x480 4:3 analysis buffer with `rotationDegrees = 90`.

Offline tests should therefore run the **whole** production pipeline over them. A simplified
test-only detector would prove nothing about the code that actually ships.

## Privacy

Grayscale, 240px, and pointed at a floor. No screen, no document, no person, no location, no
identifiers. An earlier capture attempt caught a laptop displaying a document and was discarded
rather than committed; if a future capture includes a screen, check that no text is legible at this
resolution before adding it, and prefer a scene that has none.

`MainScanRealSceneFixtureIntegrityTest` asserts that filenames match a fixed scene-only pattern and
that the metadata contains no addresses, URLs, local paths or device identifiers.

## Ground truth

`package_bright_screen_expected.json` carries the annotated package corners, the tolerance, the input
metadata needed to prove analyzer parity, and the regions the polygon must not latch onto. Corners
were read off a 5x magnified grid render, so they are accurate to roughly ±5 source pixels — which is
what the tolerance allows for.

**Ground truth is evaluation-only.** It may grade a detector's output after the fact. It must never
reach production, influence candidate generation, or select or rank a result — a detector that can
see the answer is not being measured.
`MainScanRealSceneFixtureIntegrityTest.productionSourceNeverReferencesFixtures` enforces this by
scanning `src/main` for any reference to the fixtures or their loader.

Do not tighten the recorded tolerance to match one implementation's output. The fixture describes the
package, not the detector.

## Scope of this branch

This branch contains the fixture pack, its loader and integrity tests only. It deliberately contains
**no** detector change and **no** assertion about detector accuracy: the current detector does not
select the package on this scene, and encoding that as either an expectation or a failure here would
make the suite red or misleading. The strict accuracy expectations belong with the detector
correction that satisfies them.
