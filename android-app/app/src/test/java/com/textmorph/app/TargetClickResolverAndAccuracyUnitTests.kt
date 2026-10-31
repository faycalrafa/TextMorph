package com.textmorph.app

import android.graphics.Rect
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for agent click accuracy, magnetic target snapping calculations,
 * and element-index click action parsing.
 */
class TargetClickResolverAndAccuracyUnitTests {

    @Test
    fun testParseClickElementCodeCommand() {
        val parsed = CodeActionParser.parse("click_element(5)")
        assertNotNull(parsed)
        assertEquals(AgentAction.ACTION_CLICK_ELEMENT, parsed?.action)
        assertEquals(5, parsed?.getIntParamWithAliases("element_id", "id"))
    }

    @Test
    fun testParseClickIdCodeCommand() {
        val parsed = CodeActionParser.parse("click_id(12)")
        assertNotNull(parsed)
        assertEquals(AgentAction.ACTION_CLICK_ELEMENT, parsed?.action)
        assertEquals(12, parsed?.getIntParamWithAliases("element_id", "id"))
    }

    @Test
    fun testParseClickIndexShorthand() {
        val parsed = CodeActionParser.parse("click(7)")
        assertNotNull(parsed)
        assertEquals(AgentAction.ACTION_CLICK_ELEMENT, parsed?.action)
        assertEquals(7, parsed?.getIntParamWithAliases("element_id", "id"))
    }

    @Test
    fun testParseClickElementAction() {
        val cmd = "click_element(8)"
        val parsed = CodeActionParser.parseAction(cmd)
        assertNotNull(parsed)
        assertEquals(AgentAction.ACTION_CLICK_ELEMENT, parsed?.action)
        assertEquals(8, parsed?.getIntParamWithAliases("element_id", "id"))
    }

    @Test
    fun testMagneticSnapDistanceMath() {
        // Target button bounds: left=400, top=600, right=680, bottom=680 (width=280, height=80)
        val left = 400
        val top = 600
        val right = 680
        val bottom = 680
        val centerX = ((left + right) / 2).toFloat() // 540
        val centerY = ((top + bottom) / 2).toFloat() // 640

        // Tap lands 35px below the button: x=540, y=715 (715 - 680 = 35px drift)
        val tapX = 540f
        val tapY = 715f

        val dy = tapY - bottom // 35
        val dx = abs(tapX - centerX) // 0
        val withinHorizontal = tapX >= (left - 30) && tapX <= (right + 30)

        val maxDrift = 110f
        val shouldSnap = dy in 0f..maxDrift && withinHorizontal

        assertEquals(true, shouldSnap)
        assertEquals(540f, centerX, 0.01f)
        assertEquals(640f, centerY, 0.01f)
    }

    @Test
    fun testMagneticSnapRejectsTooFarBelow() {
        val bottom = 680
        // Tap lands 200px below the button
        val tapY = 880f
        val dy = tapY - bottom // 200 > 110f
        val maxDrift = 110f
        val shouldSnap = dy in 0f..maxDrift

        assertEquals(false, shouldSnap)
    }
}
