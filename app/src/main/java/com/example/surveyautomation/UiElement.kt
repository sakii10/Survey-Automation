package com.example.surveyautomation

import android.graphics.Rect
import org.json.JSONObject

data class UiElement(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val boundsInScreen: Rect,
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("className", className ?: JSONObject.NULL)
            put("text", text ?: JSONObject.NULL)
            put("contentDescription", contentDescription ?: JSONObject.NULL)
            put("viewIdResourceName", viewIdResourceName ?: JSONObject.NULL)
            put("isClickable", isClickable)
            put("isEditable", isEditable)
            put("isCheckable", isCheckable)
            put("isChecked", isChecked)
            put("isEnabled", isEnabled)
            put("boundsInScreen", JSONObject().apply {
                put("left", boundsInScreen.left)
                put("top", boundsInScreen.top)
                put("right", boundsInScreen.right)
                put("bottom", boundsInScreen.bottom)
            })
        }
    }
}
