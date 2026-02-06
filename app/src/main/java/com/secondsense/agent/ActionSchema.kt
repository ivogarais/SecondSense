package com.secondsense.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentResponse(
    val status: Status,
    val actions: List<Action> = emptyList(),
    val question: String? = null,
    val result: String? = null,
    val needsUserConfirmation: Boolean = false,
    val confirmationPrompt: String? = null
)

@Serializable
enum class Status {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("needs_clarification") NEEDS_CLARIFICATION,
    @SerialName("done") DONE,
    @SerialName("error") ERROR
}

@Serializable
sealed class Action {

    @Serializable
    @SerialName("open_app")
    data class OpenApp(val app: String) : Action()

    @Serializable
    @SerialName("click")
    data class Click(val selector: Selector) : Action()

    @Serializable
    @SerialName("type_text")
    data class TypeText(val text: String) : Action()

    @Serializable
    @SerialName("scroll")
    data class Scroll(val direction: ScrollDirection, val amount: Int = 1) : Action()

    @Serializable
    @SerialName("back")
    data object Back : Action()

    @Serializable
    @SerialName("home")
    data object Home : Action()

    @Serializable
    @SerialName("wait")
    data class Wait(val ms: Int) : Action()
}

@Serializable
data class Selector(
    val textContains: String? = null,
    val contentDescContains: String? = null,
    val viewIdContains: String? = null,
    val nodeId: String? = null
)

@Serializable
enum class ScrollDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN
}
