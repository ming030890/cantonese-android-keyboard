# Production release 1.0 (80)

Package: `com.madeinhk.cantonesevoicekeyboard`

Prepared with gplay CLI 1.0.2. Target: **production**, release status: **draft**.

Changes:
- App and keyboard display name: 烏龜鍵盤 － 廣東話.
- New turtle launcher artwork, adaptive icon layers, themed monochrome icon, and legacy sizes.
- Updated en-US and zh-HK store titles, opening description, icons, and release notes.
- Six existing phone screenshots per locale retained.
- Production build uses `internalTrack=false`; internal diagnostics are disabled.

Build:

```sh
ORG_GRADLE_PROJECT_internalTrack=false ./deploy.sh --release
```

The first gate detected four expected setup screenshot changes caused by the app-name update. Those four renders were visually reviewed and their baselines updated. The gate was rerun separately, then packaging resumed:

```sh
ORG_GRADLE_PROJECT_internalTrack=false ./gradlew :app:verifyRelease --offline --console=plain
ORG_GRADLE_PROJECT_internalTrack=false ./gradlew :app:bundleRelease --offline --console=plain
```

`verification.json` records check results and build flags. `edit.json` identifies the staging transaction. The final upload, track, validation and commit responses are saved alongside this file when completed.

Artifact: `../../app/build/outputs/bundle/release/app-release.aab`.

Qualification limitation: the existing language-model asset manifest reports `experimental=true`, `production_approved=false`. Passing asset integrity and build checks does not qualify model quality for a public rollout. This preparation does not start a rollout or submit changes for review.


## CLI readiness findings

`preflight-pad.json` passes with a 2 GB archive budget. `asset-delivery-size-check.json` separately checks the base module below 200 MiB and each of the five on-demand packs below 512 MiB. The canonical default CLI size check counts all on-demand packs toward its 200 MB archive limit; that finding remains visible in `readiness-offline.json`. Asset packs have separate limits: https://support.google.com/googleplay/android-developer/answer/9859372 .

The CLI media-pull operation downloaded 512px thumbnails. The full-resolution screenshot originals from v78 were restored locally after verifying all twelve SHA-256 hashes match the current Play inventory exactly. No screenshots were uploaded or replaced on Play.

Remaining scanner warnings include the Google Play Asset Delivery extraction service, standard Gradle bundle metadata, and total archive size approaching the local 2 GB budget. The model asset qualification limitation above remains applicable.


## Subsequent source change

The Settings → Keyboard setup shortcut was removed, voice copy was revised, and
initial layout defaults were changed to detected Quick or Handwriting, with English
disabled, after the version 80 bundle was uploaded into the uncommitted edit. The
existing uploaded bundle and its artifact hash predate these changes. Rebuild for
a future release. No additional upload, edit
commit, review submission or rollout was performed for this change.
