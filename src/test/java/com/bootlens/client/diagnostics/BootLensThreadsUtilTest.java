package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class BootLensThreadsUtilTest {

    @Test
    void parsesSimpleThreadDump() {
        String threadDump = """
            "main" #1 prio=5 os_prio=0 tid=0x0000000000000001 nid=0x1 waiting on condition
               java.lang.Thread.State: RUNNABLE
            \tat java.base/java.lang.Thread.sleep(Thread.java:500)

            "worker-1" #22 daemon prio=5 os_prio=0 tid=0x0000000000000002 nid=0x2 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(Native Method)
            """;

        Map<String, BootLensThreadInfo> threadMap = BootLensThreadsUtil.createThreadMap(threadDump);

        assertThat(threadMap).containsKeys("main", "worker-1");
        assertThat(threadMap.get("main").state()).isEqualTo("RUNNABLE");
        assertThat(threadMap.get("worker-1").daemon()).isTrue();
    }

    @Test
    void keepsRicherDeadlockThreadBlockWhenSummaryStubRepeatsSameName() {
        String threadDump = """
            "bootlens-demo-deadlock-b" #66 [25328] daemon prio=5 os_prio=0 cpu=0.00ms elapsed=10.26s tid=0x0001 nid=25328 waiting for monitor entry  [0x0001]
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat example.Deadlock.b(Deadlock.java:20)
            \t- waiting to lock <0x0000000703b30a40> (a java.lang.Object)

            Java stack information for the threads listed above:
            ===================================
            "bootlens-demo-deadlock-b":
            \tat example.Deadlock.b(Deadlock.java:20)
            \t- waiting to lock <0x0000000703b30a40> (a java.lang.Object)
            """;

        Map<String, BootLensThreadInfo> threadMap = BootLensThreadsUtil.createThreadMap(threadDump);

        assertThat(threadMap).containsKey("bootlens-demo-deadlock-b");
        assertThat(threadMap.get("bootlens-demo-deadlock-b").threadId()).isEqualTo(66L);
        assertThat(threadMap.get("bootlens-demo-deadlock-b").daemon()).isTrue();
    }
}
