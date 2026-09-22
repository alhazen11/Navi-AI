package com.apps.naviai.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.ui.theme.NaviAITheme
import org.junit.Rule
import org.junit.Test

class RiskIndicatorInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun criticalRiskExposesAccessibleContentDescription() {
        composeRule.setContent {
            NaviAITheme {
                RiskIndicator(riskLevel = RiskLevel.CRITICAL)
            }
        }

        composeRule.onNodeWithContentDescription("Risk level: Critical risk")
            .assertContentDescriptionEquals("Risk level: Critical risk")
    }

    @Test
    fun safeRiskExposesAccessibleContentDescription() {
        composeRule.setContent {
            NaviAITheme {
                RiskIndicator(riskLevel = RiskLevel.SAFE)
            }
        }

        composeRule.onNodeWithContentDescription("Risk level: Safe")
            .assertContentDescriptionEquals("Risk level: Safe")
    }
}
