package com.course.vsearch.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 租户 id 与票据用的小工具：只要求「随机且 URL 安全」，不承担任何密码学保密职责 */
public final class RandomIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomIds() {
    }

    public static String hex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}