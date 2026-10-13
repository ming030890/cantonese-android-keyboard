# Keyboard state and sequence testing

Run the regression gate without a phone or model download:

```sh
./gradlew :app:verifyKeyboard
```

It runs all debug unit tests, all Compose screenshot comparisons, and debug assembly.
`deploy.sh` runs the same gate before building the deployment bundle. A failure stops
deployment. The gate never rewrites reference screenshots.

## Expected behavior

[KEYBOARD_QA_SPEC.md](KEYBOARD_QA_SPEC.md) is the behavioral contract and decision record.
Each contract row has a stable ID, expected transition, and test or integration-gap reference.
`KeyboardQaContractTest.kt` names start with those IDs. Confirm uncertain product policies
before treating current implementation behavior as an acceptance requirement.

## Structure

`KeyboardSessionController` orchestrates production behavior. Its Android constructor
wires real coordinators; its injectable constructor accepts editor, settings, and session
ports. Tests replace the OS and expensive model boundaries, while using the real controller,
Quick coordinator, key receiver, clipboard visibility policy, and toolbar precedence rules. Rich copied text uses its
plain-text version, and newly copied text restores the clipboard chip after typing.

`KeyboardEditor` replaces reflective InputConnection mocks with a small in-memory editor.
`QuickCandidateEngine` supplies programmable candidates and predictions. A coroutine scope
and lookup dispatcher can be injected to exercise pending/cancelled work with virtual time.
Voice and handwriting session ports expose state flows so tests can supply recording,
loading, recognition, candidates, or errors without loading models or using a microphone.
`ClipboardSession` owns visibility transitions independently of Android's ClipboardManager.

`KeyboardRenderState` is an immutable UI snapshot. The existing flow-based `KeyboardScreen`
collects state and forwards it to the value-based renderer. Screenshot tests render that
same renderer from scenario results. Toolbar assertions use the precedence function that
also drives the real toolbar, including clipboard versus idle predictions.

## Add a sequence

The shared driver is in `app/src/scenarioTest/java/.../KeyboardScenario.kt`. This directory
is compiled into unit tests and debug screenshot tests, and is excluded from production.
It drives key down/up through the production key receiver and session controller.

```kotlin
KeyboardScenario(clipboardText = "hello").use { s ->
    s.start()
    assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
    s.type("of")
    assertNull(s.snapshot().clipboard)
    assertEquals(listOf("你", "係"), s.snapshot().quickCandidates.map { it.text })
    s.choose("你")
    assertEquals("你", s.editor.text)
    assertEquals("", s.editor.composing)
    assertEquals(listOf("好", "哋"), s.snapshot().quickCandidates.map { it.text })
}
```

Set `engine.lookup` / `engine.predict`, initial editor context, right-of-cursor text,
clipboard sensitivity, enabled layouts, or model state before dispatching events. Use
`keyDown`, `keyUp`, and `cancel` for ordering defects. Use `hide` / `start` for session
boundaries. For asynchronous tests, inject `StandardTestDispatcher(testScheduler)` into
both the scope and lookup dispatcher, then call `advanceUntilIdle()` before asserting.
The scope should have its own SupervisorJob because destroying the controller closes it.

The sequence screenshots use `KeyboardScenarios`, which is also exercised by
unit tests. Add shared scenario functions for new visible transitions and render their
result in `KeyboardScenarioScreenshotTest.kt`. Existing static previews remain useful for
visual variants such as width, theme, symbols, emoji, and voice status.

To create an intentional new reference image:

```sh
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*ScenarioYourNewCase*'
# Inspect the PNGs under app/src/screenshotTestDefaultDebug/reference first.
./gradlew :app:verifyKeyboard
```

Keep baseline creation and validation as separate invocations. Review failures in
`app/build/reports/tests/testDebugScreenshotTestDefaultTestSuite/index.html`.

## What these tests establish

The initial sequences cover clipboard precedence over idle predictions, typing, cancelled
keys, non-text controls, layout switches, paste down/up, new clipboard contents restoring the chip after input, reopening,
empty/sensitive clips, handwriting recognition state, toolbar status/autofill priority,
delete/space/enter/emoji commits, Quick composition/selection, next-word context, and async
cancellation at session end, literal Quick commitment on close, and cancel-plus-delete voice Backspace. Existing coordinator and pointer tests remain in the gate.

The fake model outputs test orchestration rather than model quality. Voice/handwriting
fakes establish controller behavior, while their real coordinator/native behavior needs
its own integration tests. Inline provider view inflation and Android permissions, IME
insets, microphone capture, and asset delivery still need device tests. Local presentation
controls such as shift/emoji/paging retain their existing Compose state and static previews;
they can also be exercised by instrumented touch tests. This framework does not claim every
possible keyboard transition is already covered.
