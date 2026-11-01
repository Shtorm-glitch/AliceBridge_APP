package dev.alicebridge.app
import org.junit.Assert.*
import org.junit.Test
class AgentActionsTest {
    private class Fake : AgentActions.Target {
        val calls = mutableListOf<String>()
        override fun install(adoptHome:Boolean){calls += "install:$adoptHome"}
        override fun check(){calls += "check"}
        override fun remove(){calls += "remove"}
        override fun rollback(){calls += "rollback"}
    }
    @Test fun everyServiceCommandReachesAnOperation() {
        val target=Fake()
        listOf("install","update","check","rollback","remove").forEach { AgentActions.execute(it,true,target) }
        assertEquals(listOf("install:true","install:false","check","rollback","remove"),target.calls)
    }
    @Test fun unknownCommandFailsWithoutChangingStation() {
        val target=Fake()
        assertThrows(IllegalStateException::class.java){AgentActions.execute("typo",true,target)}
        assertTrue(target.calls.isEmpty())
    }
}
