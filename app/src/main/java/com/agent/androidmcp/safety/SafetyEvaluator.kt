package com.agent.androidmcp.safety

import com.agent.androidmcp.action.AgentAction
import com.agent.androidmcp.model.ScreenState
import com.agent.androidmcp.model.UiElement

object SafetyEvaluator {

    private val SEND_KEYWORDS = listOf(
        "send", "post", "tweet", "submit", "publish", "share", "dispatch"
    )

    private val DELETE_KEYWORDS = listOf(
        "delete", "remove", "erase", "uninstall", "discard", "trash", "wipe", "clear all"
    )

    private val FINANCIAL_KEYWORDS = listOf(
        "pay", "buy", "purchase", "checkout", "transfer", "subscribe", "order now",
        "place order", "confirm payment", "upi", "recharge"
    )

    private val FINANCIAL_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "net.one97.paytm", // Paytm
        "com.phonepe.app", // PhonePe
        "com.whatsapp.w4b"  // WhatsApp Business (payments)
    )

    private val SENSITIVE_SETTINGS_KEYWORDS = listOf(
        "factory reset", "erase all data", "format", "developer options",
        "install unknown apps", "device admin", "reset network settings"
    )

    fun evaluate(action: AgentAction, screenState: ScreenState? = null): SafetyEvaluation {
        return when (action) {
            is AgentAction.Click -> evaluateClick(action.selector?.text, action.selector?.contentDescription, action.selector?.viewId, action.x, action.y, screenState)
            is AgentAction.LongClick -> evaluateClick(action.selector?.text, action.selector?.contentDescription, action.selector?.viewId, action.x, action.y, screenState)
            is AgentAction.Type -> evaluateType(action.text, action.selector?.text, action.selector?.contentDescription, screenState)
            is AgentAction.OpenApp -> evaluateOpenApp(action.packageName)
            is AgentAction.KeyEvent,
            is AgentAction.Scroll,
            is AgentAction.Swipe,
            is AgentAction.Wait,
            is AgentAction.ClearText,
            is AgentAction.GetUi,
            is AgentAction.Screenshot -> SafetyEvaluation(SafetyLevel.SAFE)
        }
    }

    private fun evaluateClick(
        text: String?,
        desc: String?,
        viewId: String?,
        x: Int?,
        y: Int?,
        screenState: ScreenState?
    ): SafetyEvaluation {
        val candidateStrings = mutableListOf<String>()
        if (!text.isNullOrBlank()) candidateStrings.add(text)
        if (!desc.isNullOrBlank()) candidateStrings.add(desc)
        if (!viewId.isNullOrBlank()) candidateStrings.add(viewId)

        // If coordinates provided without text, check if an element exists under coordinates
        if (candidateStrings.isEmpty() && x != null && y != null && screenState != null) {
            val element = screenState.elements.firstOrNull {
                x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom
            }
            if (element != null) {
                if (element.text.isNotBlank()) candidateStrings.add(element.text)
                if (element.contentDescription.isNotBlank()) candidateStrings.add(element.contentDescription)
                if (!element.viewIdResourceName.isNullOrBlank()) candidateStrings.add(element.viewIdResourceName)
            }
        }

        val combined = candidateStrings.joinToString(" ").lowercase()
        val packageName = screenState?.packageName?.lowercase() ?: ""

        // 1. Check Financial / Payments
        if (FINANCIAL_KEYWORDS.any { combined.containsWord(it) } || FINANCIAL_PACKAGES.contains(packageName)) {
            return SafetyEvaluation(
                level = SafetyLevel.CONFIRMATION_REQUIRED,
                riskCategory = RiskCategory.FINANCIAL_PAYMENT,
                reason = "Action triggers a financial transaction, payment, or purchase flow.",
                preview = "Clicking on '$combined' in ${screenState?.packageName ?: "app"}"
            )
        }

        // 2. Check Delete
        if (DELETE_KEYWORDS.any { combined.containsWord(it) }) {
            return SafetyEvaluation(
                level = SafetyLevel.CONFIRMATION_REQUIRED,
                riskCategory = RiskCategory.DELETE_DATA,
                reason = "Action may delete, remove, or erase data or content.",
                preview = "Clicking on '$combined'"
            )
        }

        // 3. Check Send Message
        if (SEND_KEYWORDS.any { combined.containsWord(it) }) {
            return SafetyEvaluation(
                level = SafetyLevel.CONFIRMATION_REQUIRED,
                riskCategory = RiskCategory.SEND_MESSAGE,
                reason = "Action may send a message, post content, or dispatch data.",
                preview = "Clicking send button '$combined'"
            )
        }

        // 4. Check Critical System Settings
        if (packageName.contains("settings") && SENSITIVE_SETTINGS_KEYWORDS.any { combined.contains(it) }) {
            return SafetyEvaluation(
                level = SafetyLevel.CONFIRMATION_REQUIRED,
                riskCategory = RiskCategory.SYSTEM_SETTINGS,
                reason = "Action modifies critical system or security settings.",
                preview = "Clicking '$combined' in System Settings"
            )
        }

        return SafetyEvaluation(SafetyLevel.SAFE)
    }

    private fun evaluateType(
        typedText: String,
        targetText: String?,
        targetDesc: String?,
        screenState: ScreenState?
    ): SafetyEvaluation {
        val target = ((targetText ?: "") + " " + (targetDesc ?: "")).lowercase()
        // If typing password or financial info
        if (target.contains("password") || target.contains("pin") || target.contains("cvv") || target.contains("otp")) {
            return SafetyEvaluation(
                level = SafetyLevel.CONFIRMATION_REQUIRED,
                riskCategory = RiskCategory.FINANCIAL_PAYMENT,
                reason = "Action types sensitive authentication or payment details.",
                preview = "Typing into secure field: '$target'"
            )
        }

        return SafetyEvaluation(SafetyLevel.SAFE)
    }

    private fun evaluateOpenApp(packageName: String): SafetyEvaluation {
        if (FINANCIAL_PACKAGES.contains(packageName)) {
            return SafetyEvaluation(
                level = SafetyLevel.CONFIRMATION_REQUIRED,
                riskCategory = RiskCategory.FINANCIAL_PAYMENT,
                reason = "Opening payment or banking application ($packageName).",
                preview = "Open app '$packageName'"
            )
        }
        return SafetyEvaluation(SafetyLevel.SAFE)
    }

    private fun String.containsWord(keyword: String): Boolean {
        return "\\b${Regex.escape(keyword)}\\b".toRegex().containsMatchIn(this)
    }
}
