# Production Release 1.0 (86)

Package: `com.madeinhk.cantonesevoicekeyboard`  
Artifact: `../../app/build/outputs/bundle/release/app-release.aab` (signed, R8 minified, resource-shrunk, `versionCode = 86`, `INTERNAL_TOOLS = false`, `INTERNAL_TRACK = false`).

## What's Prepared
- **In-app Privacy Notice (`PrivacyNoticeDialog.kt`, `SettingsScreen.kt`, `MainScreen.kt`)**: Prominent notice in onboarding and Settings (localized in English, `zh-Hant`, and `zh-Hans`) stating: *"We never send what you type to any server."* (and never logging typed/handwritten/spoken text), plus standalone bilingual `PRIVACY_POLICY.md` ready to publish at an HTTPS URL for Play Console.
- **Open-Source ASR Model Attribution (`OpenSourceLicensesDialog.kt`, `assets/licenses/`)**: Highlighted in Settings and at the top of Open Source Notices crediting `joe2643/Qwen3-ASR-1.7B-Cantonese-HK` (Apache-2.0, fine-tuned from Alibaba Cloud / Qwen Team's `Qwen/Qwen3-ASR-1.7B`), alongside `QwenASR` (MIT), `Silero VAD` (MIT), `KleidiAI` (Apache-2.0), `OpenBLAS` (BSD-3-Clause), `SCOWL`, LibreOffice `en_GB`, Unicode Emoji Data, and AndroidX.
- **Release Telemetry Gate Fixed (`app/build.gradle.kts`, `AutocorrectTelemetryStore.kt`)**: Defaulted `internalTrack` to `false` on `release` builds so typed autocorrect episodes are never logged or synced in release builds, and moved the LAN debug `network_security_config.xml` to `app/src/debug/`.
- **Store Metadata & Release Notes (`play-release/v86/`)**: Validated `en-US` and `zh-HK` titles (`烏龜鍵盤 － 廣東話`), updated full descriptions, 512×512 icons, 1024×500 feature graphics, 6 full-res (1080×1920) phone screenshots per locale, and `release-notes.json`.

## `gplay` Blocking Steps Before Public Production Rollout
1. **App is still in `draft` state on Google Play Console** (`Only releases with status draft may be created on draft app` on `edits.validate` when setting `status="completed"`). Until initial Console setup is completed and the first release is submitted via the Play Console UI, API uploads to `production` must use `--status draft`.
2. **Production Country Availability is empty (`{}`)** (`gplay availability get --track production`): Add target countries/regions (e.g., Hong Kong / global) on the **Production → Countries / regions** tab in Play Console.
3. **Missing Support Contact Email (`gplay details get` -> `{"defaultLanguage": "zh-HK"}`)**: Set `contactEmail` via Play Console (**Grow → Store presence → Store settings**) or `gplay details patch`.
4. **Play Console App Content Declarations (Console-only gates)**:
   - **Privacy Policy HTTPS URL**: Host `PRIVACY_POLICY.md` on a public HTTPS URL and enter it in **Policy → App content → Privacy policy**.
   - **Data Safety form**: Complete **App content → Data safety** (disclosing `RECORD_AUDIO` processed ephemerally on-device with no typed/audio data collected or shared off-device).
   - **Remaining App Content items**: Confirm Ads (`No`), App access (`All functionality available without special access`), Content rating (IARC), Target audience (`13+` / `18+`, not targeting children), News / Health / Financial / Government declarations.
   - **Closed Testing Gate (if personal developer account created after Nov 2023)**: Currently `alpha` and `beta` tracks have `releases: null`. Personal Play Console accounts require 12+ opted-in testers on a closed testing track for 14 continuous days before **Apply for production** unlocks.
5. **Stale Live Store Listing (`粵語鍵盤` / `Cantonese Keyboard`)**: The earlier `v80` edit (`05855903671216722838`) expired uncommitted, so live Play metadata still has the old app name and icon; stage `play-release/v86/metadata` when uploading `v86`.
6. **Non-blocking Advisories**:
   - **On-demand PAD size headroom**: Base module is **17.0 MB** (< 200 MB limit) and each of the 5 `model_pack_a..e` shards is **275–451 MB** (< 512 MB per-pack limit), but cumulative compressed on-demand packs total **1.99 GiB (`2,136,612,152` bytes)** — leaving ~10.4 MiB below Play's 2 GiB cumulative on-demand limit. (`gplay validate`'s default 200 MB check counts all 5 on-demand packs together; `preflight-pad.json` verifies with `--max-size 2200M` with 0 errors.)
   - **Internal repo notes from `RELEASE_78.md`**: `config/language_model/selected_model.json` still marks `experimental=true, production_approved=false`, and handwriting weights originate from converted ML Kit weights (confirm redistribution terms before public launch).

## Stage Draft Production Release Command
Once ready to upload `v86` and update the store listings + turtle icon to `production` as a draft release (ready to submit in Play Console UI):

```bash
gplay release \
  --package com.madeinhk.cantonesevoicekeyboard \
  --track production \
  --status draft \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --listings-dir play-release/v86/metadata \
  --release-notes @play-release/v86/release-notes.json
```
