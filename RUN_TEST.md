# How to Run `QAsrEngineE2ETest` Instrumented Integration Test

This guide details how to prepare your connected Android device and execute the self-contained E2E instrumented integration test `QAsrEngineE2ETest`.

---

## Step 1: Pre-Deploy Model & Audio Assets to the Device

Since Android applications run inside restricted sandboxes and the model folder is **1.9 GB**, we cannot bundle it inside the test APK. Instead, we pre-deploy the model and test audio directly to the device's shared storage.

1. **Verify Connected Device**:
   Make sure your physical device or emulator is active and connected:
   ```bash
   adb devices
   ```

2. **Push the Model Assets**:
   Create the download directory on the device and push the INT8 quantized model folder:
   ```bash
   adb shell "mkdir -p /sdcard/Download/qwen-asr-m4-lmhead-int8"
   # Push files (decoder-int8-cache.v1.bin, merges.txt, model.safetensors, vocab.json)
   adb push QwenASR/bench/android-assets/model/. /sdcard/Download/qwen-asr-m4-lmhead-int8/
   ```

3. **Push the Test Audio**:
   Push the standard 3.8-second Cantonese audio test file to `/sdcard/Download/yue.wav`:
   ```bash
   adb push QwenASR/bench/android-assets/ptt_guardrail.wav /sdcard/Download/yue.wav
   ```

4. **Change File Permissions**:
   Make sure all files are globally readable on the shared storage:
   ```bash
   adb shell "chmod -R 777 /sdcard/Download/qwen-asr-m4-lmhead-int8"
   adb shell "chmod 777 /sdcard/Download/yue.wav"
   ```

---

## Step 2: Run the Instrumented Test

We execute our self-contained instrumented test class specifically, skipping unrelated Compose UI tests.

1. Navigate to the keyboard project directory:
   ```bash
   cd cantonese-voice-keyboard
   ```

2. Run the Gradle task targeting only `QAsrEngineE2ETest`:
   ```bash
   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.qwenasr.QAsrEngineE2ETest
   ```

---

## How it works (Programmatic Permission Granting)

To achieve **100% self-containment**, you do not need to manually grant storage access or run external helper loops. 

The test utilizes the elevated `UiAutomation` shell interface inside its `@Before setUp()` method to automatically grant the required `MANAGE_EXTERNAL_STORAGE` appop permission to itself instantly upon installation:

```kotlin
@Before
fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val packageName = context.packageName
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    
    val cmd = "appops set $packageName MANAGE_EXTERNAL_STORAGE allow"
    val pfd = uiAutomation.executeShellCommand(cmd)
    pfd.close() // Close descriptor to ensure command completes execution
    
    Thread.sleep(500) // Allow propagation delay
}
```

---

## Expected Output Logs

You can view the test execution logs and transcribing outputs in real-time or after completion via `logcat`:

```bash
adb logcat -d -s QAsrEngineE2ETest QAsrEngine
```

**Successful Output Signature**:
```
I QAsrEngineE2ETest: Starting QAsrEngine E2E Integration Test
I QAsrEngine: Successfully loaded qwen_asr native library
I QAsrEngineE2ETest: Engine instance created, loading model...
I QAsrEngine: Successfully loaded model from /sdcard/Download/qwen-asr-m4-lmhead-int8. Handle: 496989881776
I QAsrEngineE2ETest: Parsing WAV file: /sdcard/Download/yue.wav
I QAsrEngineE2ETest: First 20 audio samples: 0.611603 0.619324 0.650665 0.658386 0.000397 0.000000 0.760132 0.800476 0.392273 0.399841 ...
I QAsrEngineE2ETest: Audio loaded: 61742 samples (duration: 3.858875s)
I QAsrEngineE2ETest: Running transcription...
I QAsrEngineE2ETest: ==================================================
I QAsrEngineE2ETest: TRANSCRIPTION RESULT:
I QAsrEngineE2ETest: Hello，你好嗎？已經坐低咗啦。
I QAsrEngineE2ETest: Transcription completed in 6.67 seconds
I QAsrEngineE2ETest: ==================================================
```
