package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end of a rail as `TV Search` 4a and `TV Home` 3d draw it: a focused poster with its badge and revealed title, then
 * the "see all" card at rest and focused. One case, because the three are read against each other: the badge's 4dp inset
 * and small corner, the reveal's scrim block behind the title, and the see-all's darker ground and dashed edge beside a
 * card.
 */
@RunWith(AndroidJUnit4::class)
class CardScreenshotDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun screenshotRailEndPosterAndSeeAll() {
        rule.setContent {
            CloudStreamTheme {
                Box(
                    Modifier
                        .size(width = SHOT_WIDTH.dp, height = SHOT_HEIGHT.dp)
                        .background(CloudStreamTheme.colors.bg)
                        .testTag(SHOT)
                        .padding(CloudStreamDimens.FocusBandInset),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ChipRowSpacing)) {
                        PosterCard(
                            rung = PosterRung.Medium,
                            state = MediaCardState(focused = true, title = "Salt Road Crossing the Bay", meta = "2018 · 2H 36M"),
                            slots = MediaCardSlots(leadingBadge = { Badge(text = "4K") }),
                        ) { Box(Modifier.fillMaxSize().background(ART)) }
                        SeeAllCard(size = CloudStreamCardSizes.PortraitMedium, label = "See all", focused = false)
                        SeeAllCard(size = CloudStreamCardSizes.PortraitMedium, label = "See all", focused = true)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.assertScreenshot("cards-rail-end-poster-and-see-all", rule.onNodeWithTag(SHOT))
    }

    private companion object {
        const val SHOT = "shot"
        const val SHOT_WIDTH = 400
        const val SHOT_HEIGHT = 200
        val ART = Color(0xFF5A6B7C)
    }
}
