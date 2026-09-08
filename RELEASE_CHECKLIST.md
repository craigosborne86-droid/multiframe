# Play Store release checklist

Requirements researched **6 September 2026** against Play Console Help, the
Play policy pages and developer.android.com. Play's technical gates change
roughly annually — re-check the dated items in [Deadlines](#deadlines) if this
document is more than a few months old when you read it.

Project state was checked against a real `:app:bundleRelease` run, and against
the full test suite on the phone, on 6–7 September 2026 — not read off the
Gradle files. Where a box below is ticked, the evidence is named.

One test was changed in the course of this: `hoistingTheGridOutOfTheLoop`, whose
round-count assertion had been calibrated on the older phone. BASELINE.md
carried a standing instruction not to relax it and two sanctioned alternatives;
the second was taken. The reasoning and the measurements are in BASELINE.md
under *The hoist has outrun its own test* and in the test's own comment. Worth
reading before trusting the green tree above.

**`[x]`** — verified in this session, evidence given.
**`[ ]`** — outstanding.
**`Craig: manual step`** — needs a person: an account, a payment, a judgement
about the product, or a form only the developer of record can sign.

---

## A note on CLAUDE.md

**There is no CLAUDE.md in this repository.** It is not in the working tree, it
is not in `.gitignore`, and `git log --all -- CLAUDE.md` returns nothing, so it
has never been committed. Three documents nonetheless cite it as authoritative:

- [HANDOVER.md:17](HANDOVER.md:17) — "the same objective Project Indigo has, per CLAUDE.md"
- [HANDOVER.md:806](HANDOVER.md:806) — CLAUDE.md as the source of a different app name
- [SESSION-LOG.md:3562](SESSION-LOG.md:3562) — "CLAUDE.md says *Aperture*"

So the cross-reference you asked for was made against the documents that do
exist — [README.md](README.md), [BUILD.md](BUILD.md),
[store/STORE_LISTING.md](store/STORE_LISTING.md),
[store/PRIVACY.md](store/PRIVACY.md) and the build itself. If CLAUDE.md exists
somewhere outside the repo, it is worth committing, because at least one naming
decision is recorded only there and the last two references disagree with
`app_name`.

- [ ] **Craig: manual step** — say whether CLAUDE.md exists outside the repo, and if so commit it. Two documents claim it names the app something other than *Multiframe*, and the name cannot change after first publication.

---

## 1. Verified against the built bundle

These were checked by building `:app:bundleRelease` and inspecting the output,
not by reading the Gradle config.

- [x] **Android App Bundle, not APK.** Mandatory for new apps since August 2021. `./gradlew :app:bundleRelease` succeeds and produces `app-release.aab` (4.6 MB).
- [x] **Target API level 36.** From 31 August 2026 new apps must target Android 16 (API 36) or higher. The merged release manifest declares `android:targetSdkVersion="36"`. `compileSdk = 37` is unrelated to this gate and does not affect it.
- [x] **64-bit native code.** The bundle contains `base/lib/arm64-v8a/` and no 32-bit ABI directory. Play's rule bans 32-bit-only; 64-bit-only is compliant.
- [x] **16 KB page size support.** In force for new apps and updates targeting Android 15+ since 1 November 2025; this app targets 36, so it binds. All four arm64 shared objects report `align 2**14` on every LOAD segment — `libmultiframe.so` plus the three CameraX ships (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`, `libandroidx.graphics.path.so`). Verified with `llvm-objdump -p` from NDK 28.2.13676358. NDK r28 and AGP 9.3.2 are both above the versions that align by default.
- [x] **Native libraries uncompressed.** Merged release manifest carries `android:extractNativeLibs="false"`, which is what lets the loader map the `.so` files at 16 KB alignment rather than unpacking them.
- [x] **Download size under the 200 MB per-device limit.** The whole bundle is 4.6 MB.
- [x] **No debuggable flag in the release manifest.**
- [x] **Permissions are minimal and match the privacy claim.** The merged release manifest declares exactly one user-facing permission, `android.permission.CAMERA`. No `INTERNET`, no `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, no `AD_ID`. (It also carries `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, `protectionLevel="signature"`, injected by AndroidX — internal, not shown to users, needs no declaration.)
- [x] **`lintVitalRelease` passes.** It runs as part of `bundleRelease` and the build completed clean.
- [x] **R8 is on and a mapping file is produced.** `isMinifyEnabled = true`, `mapping.txt` written to `app/outputs/mapping/release/`.
- [x] **Privacy policy reachable inside the app.** Required because the app uses a sensitive permission. [AboutSheet.kt](app/src/main/java/dev/multiframe/camera/ui/AboutSheet.kt) renders the policy text from `strings.xml`, reached from the `i` control on the camera screen. The text is embedded rather than linked, which is correct here — the app has no `INTERNET` permission and could not open a URL.
- [x] **Feature graphic meets spec.** `store/assets/play_feature_1024x500.png` is 1024×500, 24-bit RGB, no alpha, 28 KB. Spec is 1024×500 JPEG or 24-bit PNG without alpha.
- [x] **Short description is within the 80-character limit.** The draft in [store/STORE_LISTING.md](store/STORE_LISTING.md) is 75 characters.
- [x] **The tree is green, on the artefact being shipped.** Not a Play gate, but the thing a release should not be cut without. Measured 7 September 2026: **360 unit tests, 0 failures**; **128 instrumentation tests on the Pixel 11 Pro, 0 failures, 0 errors and 0 skipped**; **lint 25 warnings, 0 errors**, with `lintVitalRelease` passing inside `bundleRelease`. Getting the device suite to 0 skipped needs `adb shell svc power stayon true` before the run — without it the screen idles, the fourteen Compose tests skip behind the keyguard, and wireless adb tends to drop mid-suite. One test was fixed to get here; see the note below the table.

---

## 2. Outstanding, and I can do them

- [x] **Store icon converted to 32-bit RGBA.** It was 512×512 8-bit RGB with no alpha, against a documented spec of 32-bit PNG with alpha. Now `PNG image data, 512 x 512, 8-bit/color RGBA` per `file`, `hasAlpha: yes` / `samplesPerPixel: 4` per `sips`, 33 KB against a 1 MB ceiling. The alpha channel is fully opaque and the colour data is byte-for-byte what it was — verified by comparing the RGB bytes before and after. That is deliberate: Play applies its own mask to the store icon, so it wants a full-bleed square, not a pre-shaped one with transparent corners. The feature graphic was left as 24-bit RGB with no alpha, which is what *its* spec asks for.
- [x] **First-release version settled — `versionCode = 1`, `versionName = "0.1.0"`, both left as they are.** `versionCode 1` is simply correct for a first upload. `0.1.0` is the honest number to put beside it: [HANDOVER.md](HANDOVER.md) records three real-device faults, one of them fixed in code but not yet confirmed on an actual photograph, and the first upload is going to a closed test rather than to the world. `1.0.0` would claim a completeness this does not have. One line in [app/build.gradle.kts](app/build.gradle.kts:24) changes it if you disagree.
- [x] **Checked the forced portrait orientation against Android 16's behaviour change — it does reach this app, and it does not block submission.** Run on an API 36 emulator resized to 1920×1600 at 240dpi (smallest width 1066dp), Android says it in its own words:

      I ActivityTaskManager: Ignoring manifest-declared fixed orientation
      SCREEN_ORIENTATION_PORTRAIT of dev.multiframe.camera/.MainActivity
      since target sdk 36

  The activity is handed `topActivityAppBounds=Rect(0, 0 - 1920, 1600)` with `topActivityBoundsLetterboxed=false` — a full landscape window, not a letterboxed portrait one. So `android:screenOrientation="portrait"` in [AndroidManifest.xml](app/src/main/AndroidManifest.xml) is now decorative on any display of sw ≥ 600dp. Lint says the same thing independently, as `LockedOrientationActivity` and `DiscouragedApi`. **None of this gates the upload** — it is a behaviour change, not a policy requirement, and the app is not distributed to tablets any differently because of it.

  What the emulator could **not** establish is how the viewfinder *looks* in that window: the app ANRs on this emulator before it finishes starting (`failed to complete startup`, at 0.6% CPU), and the control run proves that is the emulator rather than the window — it ANRs identically at the original 320×640 portrait size. [BUILD.md](BUILD.md) already records that the camera path cannot run there. See the follow-up in section 8, which is a judgement about a picture and therefore yours.
- [x] **Release signing is wired correctly and proven end to end.** [app/build.gradle.kts](app/build.gradle.kts:52) reads `keystore.properties` and degrades to an unsigned release rather than failing when the file is absent, so CI and fresh clones still compile. The bundle built this session carries a real signature block (`META-INF/MULTIFRA.RSA`), which proves the path works. What signed it is a throwaway — `release-test.jks`, labelled as such in `keystore.properties`. Swapping in the real key is a path change in that one file and needs no code change. The remaining half of this is in section 3, because only Craig can make the key.

---

## 3. Outstanding, blocked on something only Craig has

I can finish each of these the moment the input arrives; the input itself is a
manual step.

- [ ] **Craig: manual step** — supply the effective date, developer/company name of record, and support email for [store/PRIVACY.md](store/PRIVACY.md). It has three bracketed placeholders. I fill them in.
- [ ] **Craig: manual step** — host the finished privacy policy at a **public HTTPS URL**. Play does not accept a PDF; it must be a live web page reachable without login.
- [ ] **Craig: manual step** — create the release keystore, and back it up somewhere that survives this machine. Play App Signing means the upload key can be reset if lost, but only through a Google support process; losing it before enrolment means the listing can never be updated.
- [ ] **Craig: manual step** — once that key exists, point `keystore.properties` at it. Then say so, and I will re-run `./gradlew :app:bundleRelease` and repeat the section 1 verification — ABI list, 16 KB alignment, merged manifest, signature — against the genuinely signed bundle. The config half of this is already done and verified (section 2); only the key itself is blocking.

---

## 4. Craig: manual steps — account and identity

- [ ] **Craig: manual step** — Google Play developer account, one-off $25 registration fee.
- [ ] **Craig: manual step** — complete developer identity verification (name, address, email, phone; possibly a government ID). Separate from the account itself.
- [ ] **Craig: manual step** — decide personal vs organization account. **This decision changes the amount of work below.** An organization account needs a D-U-N-S number, free from Dun & Bradstreet but taking up to 28 days. A personal account created after 13 November 2023 must clear the closed-testing requirement in the next item.
- [ ] **Craig: manual step** — if the account is personal and was created after 13 November 2023: run a closed test with **at least 12 testers opted in continuously for 14 days**, then apply for production access. Twelve distinct Google accounts on real devices; emulators and duplicates do not count. This is the single longest lead time on this list and nothing else can shorten it.
- [ ] **Craig: manual step** — complete Android developer verification before **30 September 2026**, when it starts being enforced for new installs in Brazil, Indonesia, Singapore and Thailand. Reporting suggests Google auto-registered most *existing* Play apps earlier in 2026; I could not confirm that from Google's own documentation, and in any case a brand-new account would not be covered by it.
- [ ] **Craig: manual step** — enrol in Play App Signing. Required for new apps, and required to upload an AAB at all.

---

## 5. Craig: manual steps — App content declarations

Play Console blocks the rollout until every declaration in this section is
answered. Most are a single "no". The two that carry any weight — Data safety,
and the camera permission justification a reviewer may ask for — are already
drafted in [store/STORE_LISTING.md](store/STORE_LISTING.md), and both are
consistent with the manifest as verified in section 1.

- [ ] **Craig: manual step** — Privacy policy URL, entered in App content.
- [ ] **Craig: manual step** — **Data safety form.** Answer: no data collected, no data shared, nothing transmitted. The draft table in [store/STORE_LISTING.md](store/STORE_LISTING.md) matches the manifest — no `INTERNET` permission means the "nothing leaves the device" answer is enforceable rather than a promise. Data safety answers that contradict the privacy policy are a common rejection cause, so the two must be read side by side before submitting.
- [ ] **Craig: manual step** — **Content rating questionnaire (IARC).** An unrated app can be removed from Play.
- [ ] **Craig: manual step** — **Ads declaration.** Answer: contains no ads. Must be answered before the target audience section unlocks.
- [ ] **Craig: manual step** — **Target audience and content.** Select adult age groups. Do not select a children's age band; doing so pulls the app into Families policy and a second review track it does not need.
- [ ] **Craig: manual step** — **App access.** Declare that all functionality is available without login. There are no accounts in this app, so no test credentials are needed.
- [ ] **Craig: manual step** — **Government apps declaration.** Answer: no. Always required, of every app.
- [ ] **Craig: manual step** — **Financial features declaration.** Answer: none. Always required, of every app.
- [ ] **Craig: manual step** — **Health apps declaration.** Answer: no. Required since the April 2026 policy update.
- [ ] **Craig: manual step** — **News apps declaration.** Answer: no.

---

## 6. Craig: manual steps — store listing

- [ ] **Craig: manual step** — app title (30 characters), short description (80), full description (4000). Drafts for all three are in [store/STORE_LISTING.md](store/STORE_LISTING.md); the short description is 75 characters and fits. They need approving, not writing.
- [ ] **Craig: manual step** — **at least 2 phone screenshots**, and 4 or more at 1080×1920 or better to be eligible for Play's promotional surfaces. None exist yet — `store/assets/` holds only the icon and the feature graphic. Format is JPEG or 24-bit PNG with no alpha; each side between 320 and 3840 px. Note these are screenshots of the app's own interface — the viewfinder, the controls, the About sheet — not the photographs it produces. The captures in `proof/` are output, so they cannot be used directly; they would be material for a feature graphic or for a frame shown inside a screenshot, not screenshots themselves. Choosing what the listing leads with is a judgement about the product, not a build step.
- [ ] **Craig: manual step** — upload the 512×512 icon and the feature graphic. Both files in `store/assets/` now match Play's stated specs (section 1 and section 2); they need uploading, not editing.
- [ ] **Craig: manual step** — app category, tags, contact email, and the countries and regions to publish in.
- [ ] **Craig: manual step** — confirm the app is free with no in-app purchases.
- [ ] **Craig: manual step** — trademark search on the final name. [store/STORE_LISTING.md](store/STORE_LISTING.md) records a Play search done 24 August 2026 finding no conflict, and notes that *Multiframe* is descriptive and therefore weak as a mark. Worth settling before any brand spend — and see the CLAUDE.md note at the top, which suggests the name may not be settled at all.

---

## 7. Not applicable — recorded so nobody chases them

Each of these is a real Play gate that does not bind here. The reason is given
so that a later change to the app can reopen the question.

- **Photo and Video Permissions declaration.** Applies to apps declaring `READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO`. This app declares neither; it writes only its own files through `MediaStore`, which needs no permission at API 29+. Reopens if the app ever gains a gallery browser.
- **Permissions Declaration Form for high-risk permissions.** Covers SMS, Call Log, all-files access and similar. `CAMERA` is a runtime permission but not on that list, so no form and no approval wait. A justification paragraph is drafted in [store/STORE_LISTING.md](store/STORE_LISTING.md) in case a reviewer asks.
- **Advertising ID declaration.** The app declares no `AD_ID` permission and bundles no ads SDK.
- **32-bit ABI builds.** Play requires 64-bit support, not 32-bit support. arm64-only is compliant. It does narrow device reach, which is a product decision recorded in [app/build.gradle.kts](app/build.gradle.kts:44), not a submission gate.
- **APK expansion files.** For apps over the size limit. This one is 4.6 MB.
- **Account deletion requirement.** Apps that let users create an account must offer deletion both in-app and from a public web page. This app has no accounts and no sign-in, so there is nothing to delete. Reopens the day anything resembling a login appears.
- **Data deletion request URL in Data safety.** Same reason: no data is collected, so there is nothing for a request to act on.
- **Families policy / Designed for Families.** Not child-directed, provided the target audience declaration in section 5 is answered accordingly.
- **Target API extension request.** The extension to 1 November 2026 exists for apps that cannot meet API 36 in time. This app already targets 36.

---

## 8. Not a Play requirement, but found while checking one

This gates nothing and is deliberately outside this checklist's scope. It is
recorded here because the section 2 orientation check turned it up and it
should not be lost.

**On a large screen the viewfinder will very likely be cropped top and bottom.**
Both preview paths size themselves the same way — the ZSL surface at
[CameraScreen.kt:1031](app/src/main/java/dev/multiframe/camera/CameraScreen.kt:1031)
and the CameraX fallback at
[CameraScreen.kt:1060](app/src/main/java/dev/multiframe/camera/CameraScreen.kt:1060) —
as `Modifier.fillMaxWidth().aspectRatio(previewSize.portraitAspect())`. That is
exactly right on a phone, and it is what the commit that fixed the stretched and
centre-cropped viewfinder deliberately chose. In a window wider than it is tall
it inverts: taking the full width of a 1920×1600 window and then applying a
portrait ratio asks for a box about 2560dp tall inside 1600dp of height, so the
frame overflows and the middle band is what you see.

- [ ] **Craig: manual step** — decide whether that matters. Fixing it means choosing the constraining axis from the window's shape rather than always the width, which is a small change in one place; but it is viewfinder framing, which is the visual territory you kept for yourself, and it wants a real tablet or fold rather than an emulator that cannot start the camera. It is also worth weighing against simply not shipping to large screens at first.

---

## Deadlines

| Date | Requirement | Status here |
|---|---|---|
| Aug 2019 | Native code must include 64-bit | arm64-v8a, verified |
| Aug 2021 | New apps must publish as AAB, with Play App Signing | Bundle builds; enrolment is Craig's |
| 1 Nov 2025 | 16 KB page size, for apps targeting Android 15+ | Verified `align 2**14` |
| 31 Aug 2026 | New apps and updates must target API 36 | `targetSdk = 36`, verified |
| 30 Sep 2026 | Android developer verification enforced for new installs in BR, ID, SG, TH | Craig, not started |
| 1 Nov 2026 | Last date for a target-API extension | Not needed |
| 1 Feb 2027 | 16 KB enforcement widens to all app updates | Already compliant |

The binding constraint on shipping is not any of these. It is the **12 testers
for 14 continuous days** in section 4, if the developer account is personal and
new — that clock cannot start until the account exists and a build is on a
closed track.

---

## Sources

- [Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk)
- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Prepare your apps for Google Play's 16 KB page size compatibility requirement](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)
- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [Behavior changes: apps targeting Android 16 or higher](https://developer.android.com/about/versions/16/behavior-changes-16)
- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [Understanding Android developer verification](https://support.google.com/android-developer-console/answer/16561738?hl=en)
- [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en)
- [Manage target audience and app content settings](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en)
- [Add preview assets to showcase your app](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [Details on Google Play's Photo and Video Permissions policy](https://support.google.com/googleplay/android-developer/answer/14115180?hl=en)
- [Optimize your app's size and stay within Google Play app size limits](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en)
- [Provide information for the Financial features declaration](https://support.google.com/googleplay/android-developer/answer/13849271?hl=en)
- [Android App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq)
