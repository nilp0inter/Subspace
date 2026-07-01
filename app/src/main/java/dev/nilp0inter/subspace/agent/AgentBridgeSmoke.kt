package dev.nilp0inter.subspace.agent

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

data class AgentBridgeSmokeResult(
    val status: String,
    val message: String,
)

class AgentBridgeSmoke(
    private val context: Context,
) {
    fun run(text: String): AgentBridgeSmokeResult {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        val response = Python.getInstance()
            .getModule("agent_bridge")
            .callAttr("run_agent", mapOf("text" to text))
            .asStringMap()
        return AgentBridgeSmokeResult(
            status = response["status"] ?: "error",
            message = response["message"] ?: "missing message",
        )
    }
}

private fun PyObject.asStringMap(): Map<String, String> =
    asMap().entries.associate { (key, value) ->
        key.toString() to value.toString()
    }
