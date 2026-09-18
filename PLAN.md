# OmniPDF — Full Work Log & Handover

**Purpose:** complete handover so another agent can pick this up cold.
Repo: `D:\apps\omnipdf` → GitHub `SanyamVerma123/pdf-master` (branch `main`)
Package: `com.aistudio.pdfconverter.omnix`
Stack: Kotlin + Jetpack Compose, AGP 9.4.0, Kotlin 2.2.20, KSP 2.3.12,
compileSdk 36, Gradle 9.7.1, JDK 21 (Temurin)
CI: GitHub Actions, `.github/workflows/build.yml`, ~3.5 min per build.
**Current green build: `96423aa` → run #43 → release `omnipdf-43` → v1.7 APK (23,722,206 B)**
Delivered artifact: `D:\hermes-workspace\OmniPDF-v1.7-debug.apk` — sent to Telegram, receipt confirmed.

**IN PROGRESS — v1.8 iteration (user's v1.7 test report). Plan approved by user
2026-09-16. Do NOT start from §7; work this list.**

## 0. v1.8 TODO (user-reported, ordered)

### P0 — BUGS
- [ ] 1. **Crop does nothing real.** `PageEditSheet` crops an in-memory bitmap
      copy (`pageBitmaps`) that export never reads. Fix: persist edited pages
      to temp files and swap the staged `Uri` list in ImageToPdfWorkbench,
      UniversalPdfWorkbench, ScanToPdfWorkbench. Preview AND export reflect it.
- [ ] 2. **JPG→PDF: corrupted/empty output + no camera.** `pageBitmaps` (edited
      copy) ≠ `selectedImages` (export source) → export renders the wrong pages.
      Fix: persist edits to the source Uri list (item 1), add zero-page guard
      (fail loudly, never emit an empty file), add a CAMERA button beside the
      photo picker in ImageToPdfWorkbench.
- [ ] 3. **Long-press drag reorder missing.** `ReorderablePdfList` has NO drag
      gesture — handle is a click that expands arrow controls. Fix: implement
      long-press drag (`detectDragGesturesAfterLongPress` on LazyColumn) with
      live reorder; keep arrows as fallback.
- [ ] 4. **Unlock fails: "credential not found".** `PdfCrypto.decrypt` only
      implements `/V 2 /R 3` RC4, key hardcoded to 5 bytes (40-bit). Modern
      AES-128/256 PDFs fail. Fix: parse `/V`/`/R`/`/Length`, support AES-128
      (`/V 4 /R 4`) and AES-256 (`/V 5 /R 5`) via `javax.crypto`; clear error
      for truly unsupported files. Verified against pypdf/pikepdf.
- [ ] 5. **Compress makes files bigger + blurry.** Rasterizes vector text pages
      to low-DPI JPEG — text becomes a blurry photo that's LARGER than the
      original. Fix: never ship a bigger file (compare output size to original;
      return the original untouched + "already optimized" note); higher re-render
      DPI so text stays sharp.

### P1 — FEATURES
- [ ] 6. **Edit PDF: real editing tools.** Currently draws text at a fixed
      offset and strokes as one polyline. Fix: tap-to-place TEXT (drag the text
      box to position), freehand PEN, SIGNATURE, ERASER (per-stroke, not
      random), per-page.
- [ ] 7. **Scan auto-crop: robust.** `cropToDocument()` uses a fixed Sobel
      threshold and bails silently on most real photos. Fix: adaptive
      threshold + fallback; verify it actually fires.
- [ ] 8. **Delete OCR completely.** Tool cards (ToolCatalog OCR entry,
      ToolSelectorStrip PhotoOCR), `PhotoOcrWorkbench.kt`, ML Kit code in
      `AdvancedPdfEngine` (`ocrPdf`, `ocrPdfLayoutPreserving`,
      `extractTextWithReadingOrder`, `reconstructReadingOrder`) and `PdfEngine`
      (`extractTextFromMultipleImages`, `convertPhotosToOcrPdf`,
      `buildSearchableOcrPdf`, `extractTextFromImageUri`), VM OCR state + funcs
      (`runOcrOnScans`, `scanOcr*`, `convertOcrToPdf`, `sendOcrToComposer`,
      `sendExtractedPageToOcr`), MainActivity wiring, `ImageToPdfWorkbench`
      OCR button, ToolWorkbenchScreen `PHOTO_OCR_TO_PDF` branch, `mlkit-text-recognition`
      dependency. **Keep `PHOTO_OCR_TO_PDF` enum value** (DB history safety) —
      no card, no screen, unreachable.

### P2 — SHIP
- [ ] 9. Push → CI green → fetch APK + build.log.
- [ ] 10. Subagent verification audit: every tool path + dex checks (OCR strings
       gone, new features present). Then send APK to user.

---

## 1. HOW TO BUILD / DELIVER (do not relearn this)

1. `git add -A && git commit -m "..."` then `git push origin main`.
   Author: `Sanyam Verma <sanyamverma123@users.noreply.github.com>`. No GPG signing.
2. CI takes ~3.5 min. Poll:
   `curl -s "https://api.github.com/repos/SanyamVerma123/pdf-master/actions/runs?per_page=1"`
3. **The release tag = the run number, NOT a version number.** Run #43 became
   release tag `omnipdf-43`. APK + build.log are attached to that release.
4. **Build log fetch (no auth needed):** the GitHub API artifact download and the
   job-log endpoint both 401/403 unauthenticated. But build.log is ALSO pushed to
   the *release*, which IS public:
   ```
   curl -sL -o b43.log \
     "https://github.com/SanyamVerma123/pdf-master/releases/download/omnipdf-43/build.log"
   ```
   Read `^e: ` lines for compiler errors.
   **Gotcha burned twice:** the tag is `omnipdf-<run_number>`, and multiple runs can
   exist per commit — always match the RUN NUMBER, not the sha, or you read a stale
   log from an earlier green build and waste a cycle.
5. **APK download:**
   `https://github.com/SanyamVerma123/pdf-master/releases/download/omnipdf-43/app-debug.apk`
6. **Telegram delivery — ONLY this form works for APKs:**
   ```
   hermes send --to telegram "<caption>" -f "D:/hermes-workspace/OmniPDF-v1.7-debug.apk"
   ```
   Background it for large files (`background=true`). Takes ~1 min to arrive.
   **The `telegram-send` SKILL.md documents a `MEDIA:<path>` prefix. For APKs that
   FAILS with "No deliverable text or media remained after processing MEDIA tags" —
   verified broken twice. Do not use `MEDIA:` for APKs.** (It may still work for
   smaller file types.) User confirmation of receipt is required before stopping.

---

## 2. THE V1.7 ITERATION (what this session shipped)

Green commit **`96423aa`**. Six commits this session:
`63f45eb` → `b8ef8d5` → `ff5fe87` → `7763693` → `5c27774` → `96423aa`.

### A. Crop in the page editor — the main new feature
The prior session's subagent built the crop **UI and math** in `PageEditSheet.kt`
but was interrupted before wiring it, so the crop button shipped doing **nothing**
(`onCropPage` had a default no-op and no caller passed it). This session wired it:

- **`PageEditSheet.kt`** — signature gained
  `onCropPage: (Int, RectF) -> Unit = { _, _ -> }` (normalized 0f..1f coords).
  Crop tool button (`Icons.Default.Crop`), `cropMode`, `cropRect`, `cropAnchor`,
  `cropPageKey`. Crop and annotation share one drag gesture — only one armed at a
  time. Selection cleared on page swipe (it was measured against a different
  bitmap). `cropRectToNormalized()` inverts graphicsLayer + the ContentScale.Fit
  letterbox, clamps to the page, returns null on a degenerate/missed selection.
  Overlay: dim the page, punch the selection out with `BlendMode.Clear`, draw a
  `CrimsonPrimary` stroke and four corner handles.
- **`ImageToPdfWorkbench.kt`** (~line 624) and **`UniversalPdfWorkbench.kt`**
  (~line 1174) — pass `onCropPage`, implemented with the existing
  `cropBitmapNormalized(bmp, crop)` helper. Crops the staged bitmap in place, so
  the preview grid and the exported PDF both pick it up.
- **`ScanToPdfWorkbench.kt`** (~line 393) — crop goes through the ENGINE, not the
  local bitmap list, so the compiled PDF and the thumbnail strip stay in sync:
  - `DocumentScanner.cropPage(context, page, crop)` — NEW; decodes the processed
    URI, clamps the selection on-page, writes a new `scan_crop_*.jpg`, returns
    `ScanPage.copy()`. Raw source frame preserved so filters can be re-applied.
  - `PdfConverterViewModel.cropScanPage(page, crop)` — NEW; mirrors `rotateScanPage`
    (same loading + error handling).
- Imports added: `android.graphics.RectF` (DocumentScanner, ViewModel),
  `android.graphics.Bitmap` (ImageToPdfWorkbench),
  `androidx.compose.ui.graphics.drawscope.Stroke` (PageEditSheet).

### B. OCR now emits TEXT-ONLY (was including the page image)
`ocrPdf()` used to rebuild each page as the **scanned image** + an invisible text
layer — that is why the user saw "it is including the image with the ocr".
Now `ocrPdf()` delegates straight to `ocrPdfLayoutPreserving()`, which draws
**pure text at the coordinates ML Kit found** (headings stay centred, body stays
left-aligned, each line keeps its place and size) and **drops the page image**.
Reading order from `extractTextWithLayout()` (blocks → lines).

### C. HTML→PDF fidelity (blank/clipped export fix)
`AdvancedPdfEngine.kt` — rewritten by the prior agent, kept but guarded:
- Old code re-loaded the page **per slice** and waited only on `onPageFinished`,
  which fires before layout settles → blank/clipped output.
- New: **single-pass capture**. `renderWebToBitmap()` replaces
  `renderWebToHeight()` + the old `renderWebToBitmap()`. Waits on
  `contentHeight`/`onPageSizeChanged` and `onScaleChanged`. `LAYER_TYPE_SOFTWARE`
  (hardware layers capture nothing into a software bitmap). `useWideViewPort` +
  `loadWithOverviewMode` so desktop layouts don't collapse to mobile.
  `SETTLE_DELAY_MS = 450` grace period for late images/fonts/JS growth, plus a
  hard `SETTLE_DELAY_MS * 2` backstop. Whole page captured once, then sliced into
  A4 pages in memory (no per-slice reload). The last slice is drawn into a
  page-sized white canvas so no page is partly blank. `destroyQuietly()` cleans up.
- **My guard added this session:** the last slice's height is now
  `(full.height - top).coerceAtLeast(0)` so a degenerate capture can never pass a
  negative height to the bitmap APIs.

### D. Protect-PDF corruption (previous session, still the key fix)
Commit `63f45eb`, **CI GREEN**. Root-caused with a Python reference port
(`C:\Users\Hermes\AppData\Local\Temp\pdfcrypto_ref.py`) against a real Android
`PdfDocument` file — Android emits xref **STREAMS**, not classic xref tables:
1. Rebuilt trailer had **no `/Root`** for xref-stream input → pypdf
   "Cannot find Root object".
2. The **xref-stream object itself was encrypted**, but readers parse it before
   they know the password → unreadable garbage.
3. The 50-round key derivation hashed the **full 16-byte digest**, not the first
   `n` bytes each round.
Fixes in `PdfCrypto.kt`: `findRootRef()` (resolves the `/N M R` after `/Root`
anywhere in the file instead of assuming object 1), `isXRefStream()` +
`xrefObjNums` (skip `/Type /XRef` objects), body truncation at the first
xref-stream object, correct first-`n` re-hash rounds.
**Verified:** pypdf AND pikepdf both accept the password, read all 3 pages, extract
real text ("OmniPDF Tool Verification Page 1 / The quick brown f…").

### E. v1.6 baseline (before this session)
Commit chain `a7645b5..03c3f95` (9 features, 9 files, +1692/-197 lines):
JPG→PDF 2-per-row grid + PageEditSheet (rotate/rearrange/zoom/annotate);
Scan→PDF fresh session + thumbnail strip + confirm feedback; OCR reading-order
reconstruction; layout-preserving OCR mode; tap-to-open fullscreen previews +
vault first-page thumbnails; dashboard pinned/quick-access row; success-bar
**View** button; HTML null-URL fix. Delivered as v1.6 (`omnipdf-37`).

---

## 3. BUILD LOG (this session — 4 failures before green)

| Run | Commit | Result | Root cause |
|---|---|---|---|
| #37 | `03c3c95` | ✅ | v1.6 baseline |
| #38 | `63f45eb` | ✅ | protect fix |
| #39 | `b8ef8d5` | ❌ | duplicate `Rect`/`Size` imports; `Stroke`/`StrokeStyle` wrong package, `page.width()` as a function call |
| #40 | `ff5fe87` | ❌ | `Stroke` needs `androidx.compose.ui.graphics.drawscope`; empty-lambda typing |
| #41 | `7763693` | ❌ | `RectF.width`/`.height` are **methods**, not properties |
| #42 | `5c27774` | ❌ | `return@applyCrop` — an assigned lambda has no label to return to |
| #43 | `96423aa` | ✅ **GREEN** | all fixed; BUILD SUCCESSFUL in 3m 41s; 23,722,206 B |

**The 4 failures were all Kotlin/Compose mechanics, not logic.** If the next build
fails on PageEditSheet, check these three things FIRST:
1. `android.graphics.RectF` — `.width()` and `.height()` are **methods**. Call them.
2. `Stroke` for `drawRect(style = ...)` comes from `androidx.compose.ui.graphics.drawscope`.
   There is no `StrokeStyle` in Compose — drop it; `Stroke(width)` is enough.
3. A lambda assigned to a var (`applyCrop = { ... }`) has **no label**, so
   `return@applyCrop` does not compile. Restructure with `?.takeIf {}?.let {}`.

**Standing lesson:** an interrupted subagent's uncommitted code is UNVERIFIED and
should be treated as broken until CI proves otherwise. The crop feature looked
complete for hours and carried errors across four builds.

---

## 4. USER'S REPORTED BUGS vs STATUS (their v1.6 test feedback)

| User said | Status |
|---|---|
| Protect PDF sets password but output "cannot be opened, maybe it is corrupted" | ✅ FIXED (`63f45eb`, green) |
| HTML→PDF "working but not rendering the page correctly" | ✅ Rewritten this session (`96423aa`) — **NOT YET USER-TESTED** |
| Scan→PDF "working but not pro version type" | Partially addressed (fresh session, thumbnail strip). Scan pipeline quality still open |
| OCR "doing something but not correctly… including the image with the ocr" | ✅ Changed to text-only layout-preserving (`96423aa`) — **NOT YET USER-TESTED** |
| Crop in the page editor (requested) | ✅ Added and wired this session — **NOT YET USER-TESTED** |

**The three bolded items are the ones the user has the v1.7 APK for and has not
yet reported back on. That feedback is the single most valuable next input —
everything else is speculation until it lands.**

---

## 5. KNOWN STUBS / TECHNICAL DEBT (unstarted)

- **Compare tool** — hard-coded 92.5% similarity. Not a real diff. Highest-value stub.
- **Repair tool** — boilerplate, does nothing real.
- **PDF/A stamp** — stub.
- **Forms flatten ("flat picture")** — stub.
- **`pageCount` is hard-coded to 1** in vault records. Renders vault page counts
  wrong. Small, contained fix.
- **`pageBitmap` OOM risk** on very large PDFs. The HTML capture now allocates a
  full-page bitmap; very tall pages could OOM. There is an OOM guard inside
  `renderWebToBitmap`, but the slice loop does not guard.
- **Deprecation warnings** (build passes, but noisy): `fallbackToDestructiveMigration`;
  several `Icons.Filled.*` should be `Icons.AutoMirrored.*` (CallSplit,
  BrandingWatermark, RotateRight, OpenInNew, Sort); `LocalLifecycleOwner` →
  `androidx.lifecycle.compose`; `LocalClipboardManager` → `LocalClipboard`.
  Warnings, not errors — do not churn them without a reason.
- **Verification audit never ran.** User asked: "after doing every upgradation
  deploy your agent to check if everything." Run this after the next green build
  (delegate it — see §7).

---

## 6. USER PREFERENCES THAT MATTER HERE

- **Destructive actions need explicit approval** — deletes, overwrites, uninstalls,
  anything with side effects. Confirm scope first.
- **Impatient with slow iteration** — says "why are you taking too much time" when
  builds stack up. Wants incremental push-verify-report cycles: commit and push
  partial work so CI starts sooner rather than long perfection runs. Say plainly
  when something is slow or stuck rather than going quiet.
- **Delegate side work, stay on the main deliverable.** Secondary/supporting work
  (deletions, cleanup, mechanical file ops, independent verification) goes to a
  subagent. Never delegate the task the user assigned directly.
- **APK delivery:** user wants the file attached to Telegram; a direct download
  link is also welcome. Do not re-send after they confirm receipt.
- **Earth Radio is deleted.** Do not recreate it; the user moved on to OmniPDF.
- **A markdown tracker (this file) is expected** — the user asked for it explicitly
  and reads it to hand off to another agent.

---

## 7. SUGGESTED NEXT STEPS (priority order)

1. **Collect user feedback on crop / OCR / HTML→PDF** from the v1.7 APK.
2. **Verification audit** — DELEGATE it. Exercise every tool path: split, merge,
   protect→unlock round trip, OCR, HTML→PDF, JPG→PDF including the new crop,
   scan→PDF. The user explicitly asked for this after upgrades.
3. Fix the `pageCount` hardcode in vault records (small, safe).
4. Implement a real Compare tool (replace the 92.5% stub).
5. Add the OOM guard to the HTML slice loop.
6. Repair / PDF-A / forms-flatten stubs.

---

## 8. FILES TOUCHED THIS SESSION

| File | Change |
|---|---|
| `app/src/main/java/com/example/ui/components/PageEditSheet.kt` | crop UI + math (prior agent), then 4 build fixes by me |
| `app/src/main/java/com/example/ui/components/ImageToPdfWorkbench.kt` | wired `onCropPage` |
| `app/src/main/java/com/example/ui/components/UniversalPdfWorkbench.kt` | wired `onCropPage` |
| `app/src/main/java/com/example/ui/components/ScanToPdfWorkbench.kt` | wired `onCropPage` |
| `app/src/main/java/com/example/engine/DocumentScanner.kt` | NEW `cropPage()` + `RectF` import |
| `app/src/main/java/com/example/ui/viewmodel/PdfConverterViewModel.kt` | NEW `cropScanPage()` + `RectF` import |
| `app/src/main/java/com/example/engine/AdvancedPdfEngine.kt` | OCR→text-only delegation, negative-height guard |
| `PLAN.md` | this file |

---

## 9. PARKED / NON-CODE CONTEXT

- **Earth Radio — DELETED** at the user's request. Both `D:\hermes-workspace\earth-radio`
  and the leftover `globe-radio` copy are gone; port 8471 is free. Do not recreate.
- **Digital products** (Class 12 accountancy notes under `D:\hermes-workspace\class12`)
  were discussed as a money-making idea — inventory audited and genuinely sellable
  (real TS Grewal/DK Goel worked examples, CBSE examiner-report sourcing), ~5 of 11
  chapters missing. Not started; lower priority than the app. The user may bring
  this back up.
- Reference crypto corpus: `C:\Users\Hermes\AppData\Local\Temp\pdfcrypto_ref.py`,
  `sample_android.pdf`, `sample_real.pdf`, `pa2.pdf`, `app_enc*.pdf`.
- Older delivered APKs: `D:\hermes-workspace\OmniPDF-v1.6-debug.apk` (23,705,822 B)
  and v1.1–v1.5; `build19.log`–`build42.log`.
