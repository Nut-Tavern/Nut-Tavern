package com.nuttavern.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPolicyTest {

    @Test
    fun toolRequiredApproval_cannotBeDisabledByUserSetting() {
        assertTrue(
            shouldRequireToolApproval(
                toolRequiresApproval = true,
                userRequiresApproval = false,
            ),
        )
    }

    @Test
    fun userSetting_canAddApprovalToLowRiskTool() {
        assertTrue(
            shouldRequireToolApproval(
                toolRequiresApproval = false,
                userRequiresApproval = true,
            ),
        )
    }

    @Test
    fun noApprovalWhenNeitherToolNorUserRequiresIt() {
        assertFalse(
            shouldRequireToolApproval(
                toolRequiresApproval = false,
                userRequiresApproval = false,
            ),
        )
    }
}
