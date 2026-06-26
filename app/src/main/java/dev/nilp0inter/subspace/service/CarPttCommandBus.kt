package dev.nilp0inter.subspace.service

internal object CarPttCommandBus {
    private var listener: CarPttCommandListener? = null

    fun setListener(listener: CarPttCommandListener?) {
        this.listener = listener
    }

    fun startTelecomCapture() {
        listener?.onCarPttStart()
    }

    fun release() {
        listener?.onCarPttRelease()
    }
}

internal interface CarPttCommandListener {
    fun onCarPttStart()
    fun onCarPttRelease()
}
