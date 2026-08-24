# Play Console submission notes

Prepared 2026-08-24. Re-check every item against current policy before you
actually submit; these requirements change roughly annually.

## Target API level

Confirmed against Play Console Help on 2026-08-24: from **31 August 2026**, new
apps and updates must target **Android 16 (API level 36)** or higher. An
extension to 1 November 2026 can be requested.

The app sets `targetSdk = 36`, so it complies. `compileSdk` is 37 because
AndroidX 1.19.x requires it; that is independent of the target level.

Moving `targetSdk` to 37 would opt the app into Android 17 behaviour changes
that have not been tested. It is not required and should be a deliberate,
tested step.

## Permissions

The app declares exactly one permission.

### `android.permission.CAMERA`

**Justification for the Play Console form:**

> The app is a camera. Camera access is required for its single core function:
> showing a live viewfinder and capturing photographs when the user presses the
> shutter. The app captures a short burst of frames per shutter press and
> merges them on the device to reduce image noise. Frames exist only in memory
> during that processing and are discarded afterwards. No camera data leaves
> the device.

### Permissions deliberately not requested

- **`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`** — not requested. Google Play's
  Photo and Video Permissions policy restricts these to apps whose core
  function needs broad library access. This app only writes the files it
  creates, via `MediaStore`, which needs no permission on API 29 and above. The
  policy therefore does not apply and no declaration form is needed.
- **`WRITE_EXTERNAL_STORAGE`** — not needed at `minSdk` 33 under scoped storage.
- **`INTERNET`** — not requested. This is what makes the "no data leaves the
  device" claim in the privacy policy technically enforceable rather than a
  promise.

## Data safety section

Must be completed even though nothing is collected.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | No |
| Is all user data encrypted in transit? | N/A — no data is transmitted |
| Do you provide a way for users to request data deletion? | N/A — no data is collected |
| Photos and videos collected? | No. Photos are written to the user's own device storage and never transmitted |

The Data safety answers must match the privacy policy exactly. Inconsistency
between the two is a common rejection cause.

## Privacy policy

Required as a live, publicly accessible HTTPS URL — not a PDF — in both:

1. The Play Console app content section, and
2. Inside the app itself.

The in-app requirement is met by the About screen, reachable from the `i`
control on the camera screen.

Draft policy: `store/PRIVACY.md`. It still contains bracketed placeholders.

## Name and identity — settled

The app is **Multiframe**. `applicationId` is `dev.multiframe.camera` and is
final; it cannot be changed after first publication.

Checked on Play 2026-08-24: no app is named "Multiframe". The nearest matches
are *MultiFrames* (plural, G Solutions Kuwait, unrelated category) and a
developer account called *Multiframes*. Neither is an impersonation concern,
but the similarity may cost some search discoverability.

Note the name is descriptive rather than arbitrary, which makes it weak as a
trademark: it is unlikely to be defensible against others using the same word
for a multi-frame camera. That is a commercial trade-off, not a blocker, and a
formal trademark search is still recommended before any brand investment.

## Store assets

| Asset | File | Spec |
|---|---|---|
| App icon | `store/assets/play_icon_512.png` | 512x512 32-bit PNG |
| Feature graphic | `store/assets/play_feature_1024x500.png` | 1024x500 |

Both are generated from the same geometry as the in-app adaptive icon
(`ic_launcher_foreground.xml`). The store icon is scaled to fill more of the
square, because it is shown standalone rather than through an adaptive mask.

## Listing copy

**Short description** (80 char limit):

> Every shot is many shots: multi-frame capture for cleaner low-light photos.

**Full description:**

> Multiframe is a camera that takes a burst of photographs every time you press
> the shutter, instead of a single one.
>
> It aligns those frames to cancel out the small movements of your hand, then
> merges them into one image. Because sensor noise is random and the scene is
> not, combining frames cancels the noise while keeping the detail. The effect
> is strongest exactly where phone cameras usually struggle: shadows, interiors
> and low light.
>
> Multiframe deliberately exposes a little darker than most cameras, so bright
> areas keep their detail instead of clipping to white, and recovers the
> shadows during merging. Tone mapping is kept restrained. The goal is a
> photograph that looks like the scene, rather than one that has been sharpened
> and saturated until it looks processed.
>
> Manual controls are available for ISO, shutter speed, focus distance, white
> balance and exposure compensation, along with control over how many frames go
> into each shot. Controls the hardware does not support are simply not shown.
>
> Everything happens on your device. Multiframe has no internet permission, so
> it cannot send your photographs anywhere. There are no accounts, no adverts,
> no analytics and no tracking.

## Still outstanding before submission

- [ ] Fill in the privacy policy date, developer name and contact, then host it.
- [ ] Create a release keystore and back it up. Losing it means never being able
      to update the listing again.
- [ ] Complete the Data safety form.
- [ ] Trademark and Play Store search on the chosen name.
- [ ] Store listing assets: feature graphic, phone screenshots, short and full
      description.
- [ ] Content rating questionnaire.
- [ ] Developer account verification, which now applies to all Play apps.
