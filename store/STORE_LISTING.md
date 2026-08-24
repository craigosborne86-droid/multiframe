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

## Still outstanding before submission

- [ ] Decide the final app name and change `applicationId` accordingly. The
      current `dev.multiframe.camera` is a placeholder and **cannot be changed
      after first publication**.
- [ ] Fill in the privacy policy placeholders and host it.
- [ ] Create a release keystore and back it up. Losing it means never being able
      to update the listing again.
- [ ] Complete the Data safety form.
- [ ] Trademark and Play Store search on the chosen name.
- [ ] Store listing assets: feature graphic, phone screenshots, short and full
      description.
- [ ] Content rating questionnaire.
- [ ] Developer account verification, which now applies to all Play apps.
