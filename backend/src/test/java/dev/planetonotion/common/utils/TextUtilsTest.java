package dev.planetonotion.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextUtilsTest {

    @Test
    void hasText_rejectsNullAndWhitespace() {
        assertThat(TextUtils.hasText(null)).isFalse();
        assertThat(TextUtils.hasText(" \t\n")).isFalse();
    }

    @Test
    void hasText_acceptsNonBlankText() {
        assertThat(TextUtils.hasText(" Plane ")).isTrue();
    }
}
