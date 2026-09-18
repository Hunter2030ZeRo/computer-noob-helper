package com.example.commaengdoughme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HudSurfaceTest {
    @get:Rule val compose = createComposeRule()

    private fun show(text: String, compact: Boolean = false, onSpeak: () -> Unit = {}, onCamera: () -> Unit = {}, onReset: () -> Unit = {}, mvp: Boolean = false) {
        compose.setContent {
            Box(Modifier.requiredSize(if (compact) 426.dp else 320.dp, if (compact) 280.dp else 426.dp)) {
                HudSurface(null, text, emptyList(), SpatialCalibration(),
                    calibrated = false, spatialEnabled = true, cameraReady = true, cameraRequested = true,
                    cameraStatus = "시야 인식 중", agentBusy = false, agentConfigured = true,
                    continuous = false, canSpeak = true, stepIndex = 0, stepCount = 1,
                    onSpeak = onSpeak, onPrevious = {}, onNext = {}, onSettings = {}, onCameraToggle = onCamera, onShare = {}, onRecovered = onReset, mvp = mvp)
            }
        }
    }

    @Test fun compactHudHasNoFormAndMenuControlsCamera() {
        var spoken = 0
        var toggled = 0
        show("문제를 말씀해 주세요.", onSpeak = { spoken++ }, onCamera = { toggled++ })
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("말하기").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("메뉴").performClick()
        compose.onNodeWithContentDescription("시야 일시 정지").performClick()
        compose.runOnIdle { assertEquals(1, spoken); assertEquals(1, toggled) }
        compose.onNodeWithText("빠른 조작").assertDoesNotExist()
    }

    @Test fun shortViewportPaginatesAllLinesWithoutShrinkingOrDroppingText() {
        show("첫 번째 안내\n두 번째 안내\n세 번째 안내\n마지막 안내", compact = true)
        compose.onNodeWithContentDescription("마지막 안내", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("다음 안내").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("마지막 안내", substring = true).assertExists()
        compose.onNodeWithContentDescription("이전 안내").performClick()
        compose.onNodeWithContentDescription("첫 번째 안내", substring = true).assertExists()
    }

    @Test fun resetIsAvailableWithoutOpeningMenuOrHavingSensorPose() {
        var resets = 0
        show("안내", onReset = { resets++ })
        compose.onNodeWithContentDescription("UI 리셋").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
        compose.onNodeWithContentDescription("시야 일시 정지").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun modalMenuOwnsFocusAndDisablesBackgroundUntilDismissed() {
        var spoken = 0
        show("안내", onSpeak = { spoken++ })
        compose.onNodeWithContentDescription("메뉴").performClick()
        val inDialog = hasAnyAncestor(isDialog())
        compose.onNode(hasContentDescription("말하기") and !inDialog).assertIsNotEnabled()
        compose.onNode(hasContentDescription("UI 리셋") and !inDialog).assertIsNotEnabled()
        compose.onNode(hasContentDescription("말하기") and inDialog).assertIsFocused()
        compose.onNode(isDialog()).performKeyInput { repeat(12) { pressKey(Key.Tab) } }
        compose.onAllNodes(isFocused() and !inDialog and !isDialog()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, spoken) }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("메뉴").assertIsFocused()
        compose.onNodeWithContentDescription("말하기").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, spoken) }
    }

    @Test fun mvpOffersVisualAdviceWithoutSpeechOrResetControls() {
        var sends = 0
        show("현재 시야를 보내세요", onSpeak = { sends++ }, mvp = true)
        compose.onNodeWithContentDescription("말하기").assertDoesNotExist()
        compose.onNodeWithContentDescription("UI 리셋").assertDoesNotExist()
        compose.onNodeWithContentDescription("시야 보내기").performClick()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithContentDescription("메뉴").performClick()
        compose.onNodeWithContentDescription("시야 보내기").assertIsNotEnabled()
        compose.onNodeWithContentDescription("시야 공유 시작").assertIsDisplayed()
        compose.onNodeWithContentDescription("Provider 설정").assertIsDisplayed()
    }
}
