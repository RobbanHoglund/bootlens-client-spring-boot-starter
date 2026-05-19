package com.bootlens.client.profiling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProfilerEventTest {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
        "cpu,          CPU",
        "CPU,          CPU",
        "Cpu,          CPU",
        "wall,         WALL",
        "WALL,         WALL",
        "ctimer,       CTIMER",
        "alloc,        ALLOC",
        "ALLOC,        ALLOC",
        "lock,         LOCK",
        "nativemem,    NATIVE_MEM",
        "NATIVE_MEM,   NATIVE_MEM",
        "native-mem,   NATIVE_MEM",
        "native_mem,   NATIVE_MEM",
        "nativememleak,    NATIVE_MEM_LEAK",
        "NATIVE_MEM_LEAK,  NATIVE_MEM_LEAK",
        "native-mem-leak,  NATIVE_MEM_LEAK",
    })
    void fromStringResolvesKnownEvents(String input, String expectedName) {
        assertThat(ProfilerEvent.fromString(input))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.name()).isEqualTo(expectedName));
    }

    @Test
    void fromStringReturnsEmptyForUnknownEvent() {
        assertThat(ProfilerEvent.fromString("cache-misses")).isEmpty();
        assertThat(ProfilerEvent.fromString("cycles")).isEmpty();
        assertThat(ProfilerEvent.fromString("bogus")).isEmpty();
    }

    @Test
    void fromStringReturnsEmptyForNullAndBlank() {
        assertThat(ProfilerEvent.fromString(null)).isEmpty();
        assertThat(ProfilerEvent.fromString("")).isEmpty();
        assertThat(ProfilerEvent.fromString("   ")).isEmpty();
    }

    @Test
    void externalNamesAreCorrect() {
        assertThat(ProfilerEvent.CPU.externalName()).isEqualTo("cpu");
        assertThat(ProfilerEvent.WALL.externalName()).isEqualTo("wall");
        assertThat(ProfilerEvent.CTIMER.externalName()).isEqualTo("ctimer");
        assertThat(ProfilerEvent.ALLOC.externalName()).isEqualTo("alloc");
        assertThat(ProfilerEvent.LOCK.externalName()).isEqualTo("lock");
        assertThat(ProfilerEvent.NATIVE_MEM.externalName()).isEqualTo("nativemem");
        assertThat(ProfilerEvent.NATIVE_MEM_LEAK.externalName()).isEqualTo("nativememleak");
    }
}
