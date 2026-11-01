package dev.alicebridge.app

/** Shared command router: every service button has an explicit operation. */
internal object AgentActions {
    interface Target {
        fun install(adoptHome: Boolean)
        fun check()
        fun remove()
        fun rollback()
    }
    fun execute(command: String, adoptHome: Boolean, target: Target) {
        when(command) {
            "install" -> target.install(adoptHome)
            "update" -> target.install(false)
            "check" -> target.check()
            "remove" -> target.remove()
            "rollback" -> target.rollback()
            else -> error("Неизвестное действие службы: $command")
        }
    }
}
