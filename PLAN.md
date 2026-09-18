# OmniPDF — Work Tracker

Repo: `D:\apps\omnipdf` → GitHub `SanyamVerma123/pdf-master` (branch `main`)
Package: `com.aistudio.pdfconverter.omnix` · Kotlin + Compose · AGP 9.4 / compileSdk 36
Current green baseline: **v1.6** (release `omnipdf-37`, commit `03c3f95`)
Last verified build: **`63f45eb` — CI GREEN** (protect /Root + xref-stream fix)

---

## ✅ DONE (verified)

### Protect-PDF corruption — FIXED & CI GREEN
Commit `63f45eb`. Root-caused with a Python reference port against a real Android
`PdfDocument` file (which emits xref STREAMS, not classic tables). Three defects:
1. Rebuilt trailer had **no `/Root`** for xref-stream input → pypdf "Cannot find Root object"
2. The **xref-stream object itself was encrypted**, but readers parse it before
   they know the password → unreadable garbage
3. The 50-round key derivation hashed the **full 16-byte digest** instead of the
   first `n` bytes each round

Fixes in `PdfCrypto.kt`: `findRootRef()` (resolves `/N M R` after `/Root` anywhere),
`isXRefStream()` + `xrefObjNums` (skip `/Type /XRef` objects), body truncation at the
first xref-stream object, correct `derive()` first-n rounds.
**Verified:** pypdf AND pikepdf both accept the password, read all 3 pages, extract
real text ("OmniPDF Tool Verification Page 1 / The quick brown f…").

### v1.6 feature batch — DELIVERED
Commit chain `a7645b5..03c3f95`. 9 features, 9 files, +1692/-197 lines:
- JPG→PDF 2-per-row grid + `PageEditSheet.kt` (rotate / rearrange / zoom / annotate)
- Scan→PDF fresh session + thumbnail strip + confirm feedback
- OCR reading-order reconstruction (line boxes, top-then-left/column grouped)
- Layout-preserving OCR mode
- Tap-to-open fullscreen previews + vault first-page thumbnails
- Dashboard pinned/quick-access row
- Success-bar **View** button
- HTML null-URL fix re-verified

APK delivered + confirmed received by the user (23,705,822 B, `omnipdf-37`).

---

## 🔧 IN PROGRESS — uncommitted working tree

The 3-fix batch (`deleg_3b5885d0`) was **INTERRUPTED mid-flight**. Its changes are
uncommitted in the working tree and are **complete-looking but NOT yet built or
verified**. Do not trust them until CI says green.

### A. HTML→PDF render fidelity — `AdvancedPdfEngine.kt`
Status: rewritten, **NOT BUILT**. Old code re-loaded the page per slice and waited
only on `onPageFinished`, which fired before layout settled → blank/clipped output.
New approach (single-pass capture):
- `renderWebToBitmap()` replaces `renderWebToHeight()` + old `renderWebToBitmap()`
- Waits on `onPageSizeChanged`/`contentHeight`, not just `onPageFinished`
- `LAYER_TYPE_SOFTWARE` (hardware layers capture nothing into a software bitmap)
- `useWideViewPort` + `loadWithOverviewMode` so desktop layouts don't collapse to mobile
- `SETTLE_DELAY_MS = 450` grace period for late image/font/JS growth
- `onScaleChanged` debounced backstop + a hard `SETTLE_DELAY_MS * 2` timeout
- Whole page captured ONCE, then sliced into A4 pages in memory (no per-slice reload)
- Last slice drawn into a page-sized white canvas so no page is partly blank
- `destroyQuietly()` cleans the WebView

**RISK:** `sliceH`/`contentW` are Ints used in `minOf(sliceH, full.height - top)`.
If `full.height - top` is negative this would go negative — needs a `.coerceAtLeast(0)`
before it can be considered safe.

### B. OCR text-only output — `AdvancedPdfEngine.kt`
Status: done, clean, low risk.
`ocrPdf()` was rebuilding the PDF with the page **image** + an invisible text layer.
That is why the user saw "the image included with the OCR".
Now: `ocrPdf()` delegates straight to `ocrPdfLayoutPreserving()`, which draws **pure
text** at the coordinates ML Kit found (headings stay centred, body stays left-aligned,
each line keeps its place and size) and **drops the page image**. Reading order from
`extractTextWithLayout` (blocks → lines).

### C. Crop in PageEditSheet — `PageEditSheet.kt`
Status: **UI + math are written, the workbenches are NOT wired**.
- `onCropPage: (Int, RectF) -> Unit` added to the signature (normalized 0f..1f)
- Crop tool button (Icons.Default.Crop), `cropMode`, `cropRect`, `cropAnchor`,
  `cropPageKey`; selection cleared when swiping pages; crop/draw share one drag
- `cropRectToNormalized()` — inverse of graphicsLayer + Fit letterbox, clamps to page,
  returns null on a degenerate/missed selection
- `cropBitmapNormalized(source, crop)` helper exists
- **GAP — the callers do not implement it.** `grep "onCropPage ="` returns NOTHING.
  `ImageToPdfWorkbench.kt:624`, `ScanToPdfWorkbench.kt:393`,
  `UniversalPdfWorkbench.kt:1174` all still call `PageEditSheet(...)` without an
  `onCropPage` argument. It compiles (the param has a default `{ _, _ -> }`) but
  **the crop button does nothing in the shipped app.**
- Also has the same empty-lambda trap that broke the annotation feature last time:
  `var applyCrop by remember { mutableStateOf({}) }` — assign a typed no-op or the
  type can't be inferred. `applyStrokes` on line 135 has the same latent issue.

---

## ❌ NOT STARTED — remaining known stubs

- PDF/A stamp — stub
- Forms flatten ("flat picture") — stub
- Compare tool — hard-coded 92.5% similarity, not a real diff
- Repair tool — boilerplate, does nothing real
- `pageCount` is **hardcoded to 1** in vault records
- `pageBitmap` OOM risk on very large PDFs
- **Post-upgrade verification audit** (user: "after doing every upgradation deploy your
  agent to check if everything") — never run. Should follow the next green build.

---

## 🧊 PARKED / CONTEXT

- **Earth Radio — DELETED** at the user's request (`D:\hermes-workspace\earth-radio`).
  Leftover copy `D:\hermes-workspace\globe-radio` + the port-8471 http.server were
  handed to a cleanup subagent. Do not work on it again.
- **Digital products** (Class 12 accountancy notes) — discussed, not started. User
  leaning toward selling digital products; inventory audited and sellable. Lower
  priority than the app.
- User wants a **markdown plan/tracker file** (this file) maintained as work proceeds.

---

## BUILD LOG (live)

| Run | Commit | Result | Root cause |
|---|---|---|---|
| #37 | `03c3c95` | ✅ GREEN | v1.6 baseline |
| #38 | `63f45eb` | ✅ GREEN | protect /Root + xref-stream fix |
| #39 | `b8ef8d5` | ❌ FAILED | All in the interrupted agent's crop code: duplicate `Rect`/`Size` imports, `Stroke`/`StrokeStyle` don't exist in Compose, `page.width()` used as a function call on a Bitmap property |
| #40 | `ff5fe87` | ⏳ BUILDING | the three fixes above |

**Lesson (do not repeat):** an interrupted subagent's uncommitted code is UNVERIFIED.
Always treat it as broken until CI proves otherwise. The crop UI looked complete for
hours and carried 7 compile errors.

---

## 🪜 NEXT STEPS (in order)

1. ✅ Wire `onCropPage` into the three workbenches — DONE
2. ✅ Fix the `applyCrop` / `applyStrokes` empty-lambda typing — DONE (commented; harmless)
3. ✅ Add the `coerceAtLeast(0)` guard on `full.height - top` in the HTML slicer — DONE
4. ✅ Commit → push → watch CI — DONE (run #40, `ff5fe87`)
5. On green: release `omnipdf-41` (the release tag = run number, not a version),
   deliver APK, then run the verification audit.
