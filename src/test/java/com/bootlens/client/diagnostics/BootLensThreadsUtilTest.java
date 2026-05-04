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
}
