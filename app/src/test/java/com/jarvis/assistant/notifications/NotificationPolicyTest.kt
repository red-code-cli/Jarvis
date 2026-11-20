package com.jarvis.assistant.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun `otp code is sensitive`() {
        assertTrue(NotificationPolicy.isSensitive("Your OTP is 482913"))
        assertTrue(NotificationPolicy.isSensitive("One time password: 4829"))
        assertTrue(NotificationPolicy.isSensitive("verification code 1234"))
        assertTrue(NotificationPolicy.isSensitive("Your passcode is 8844"))
    }

    @Test
    fun `plain digits without keywords are not sensitive`() {
        assertFalse(NotificationPolicy.isSensitive("Meet me at 5pm near gate 4"))
        assertFalse(NotificationPolicy.isSensitive("Call me on 9876543210"))
        assertFalse(NotificationPolicy.isSensitive("Your bill is Rs 1250"))
    }

    @Test
    fun `password mention is sensitive even without digits`() {
        assertTrue(NotificationPolicy.isSensitive("Your password was changed"))
    }

    @Test
    fun `bank and UPI codes are sensitive`() {
        assertTrue(NotificationPolicy.isSensitive("Bank alert: Rs 500 debited, OTP 8832"))
        assertTrue(NotificationPolicy.isSensitive("UPI payment of Rs 1200 from account 4567"))
        assertTrue(NotificationPolicy.isSensitive("Card ending 4321 used for Rs 900"))
    }

    @Test
    fun `normal chat messages are not sensitive`() {
        assertFalse(NotificationPolicy.isSensitive("Hey! Kal movie chalte hain?"))
        assertFalse(NotificationPolicy.isSensitive("Photos bhej diye, dekh lo"))
        assertFalse(NotificationPolicy.isSensitive("Mujhe 4:30 pe yaad dilana"))
    }
}
