package com.bootlens.client.diagnostics;

import java.util.List;

public record BootLensThreadInfo(
    String name,
    long threadId,
    String state,
    boolean virtualThread,
    boolean daemon,
    boolean deadlocked,
    String header,
    List<String> stackLines
) {
}
