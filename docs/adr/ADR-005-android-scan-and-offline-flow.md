# ADR-005: Scan with the system camera and photo picker, then parse in the background

* **Status:** Accepted
* **Date:** 2026-11-07

## Context

The app needs a photo of a receipt, its text, and a parsed list of items. Shoppers scan in stores and kitchens
with patchy connections, and receipts are private.

## Decision

* **Capture:** `ActivityResultContracts.TakePicture` (the system camera app writes into our private cache through
  a `FileProvider`) and the photo picker. Neither needs the CAMERA or storage permission.
* **Text:** ML Kit text recognition with the bundled model, on the device. The photo never leaves the phone.
* **Rows:** ML Kit returns names and prices as separate columns on many receipts, so `OcrRowAssembler`
  (`core:domain`) rebuilds rows by vertical position before anything else sees them.
* **Offline first:** the rows are saved in Room right away, sanitized on the device, and parsing runs in a
  `WorkManager` job that waits for a connection and retries with exponential backoff (network errors, 429, 5xx).
  The UI only ever reads Room, so it shows "reading items" and updates by itself when the job finishes.
* **Events** use a transactional outbox: the card's new status and its event are written in one Room transaction;
  a worker uploads events and deletes them after the server confirms (see ADR-004 for the server side).

## Consequences

### Positive
* No runtime permission prompts at all, and much less camera code to get wrong.
* Scanning works offline; nothing is lost if the app is killed mid-upload.
* All receipt rules (rows, prices, sanitizing) are plain Kotlin with fast JVM tests.

### Negative
* No live preview with a text overlay or automatic edge detection; the shopper frames the photo themselves.
* The system camera app differs between devices, so the capture experience is not fully under our control.

## Alternatives considered

### CameraX with live text recognition
Better experience (frame guides, auto-capture) but needs the CAMERA permission, a lifecycle-aware preview and
much more code. A good next step once the core loop is validated.

### Parse synchronously while the shopper waits
Simpler, but fails whenever the connection is poor and blocks the screen for several seconds.
