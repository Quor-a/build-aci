package javax.lang.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 兼容 Android 的 javax.lang.model.SourceVersion 替身。
 *
 * ecj 自带的 JDK9 版 SourceVersion 在 <clinit> 里写死调用 Runtime.version()
 * （Java 9+ 才有的 API），而 Android 设备的 java.lang.Runtime 没有该方法，
 * 导致端侧 in-process 编译时抛 NoSuchMethodError。
 *
 * 本文件按 OpenJDK 的公开 API 形状重建，覆盖 ecj 外部实际引用的所有成员
 * （latest() / valueOf / values / compareTo / RELEASE_0..17 常量 /
 *  isIdentifier / isName(CharSequence,SourceVersion) /
 *  isKeyword(CharSequence,SourceVersion)），但 <clinit> 不再触碰任何
 * Java 9+ API，因此可在 Android (min-api 26) 上正常运行。
 *
 * 注意：本类由 tools/regen_tool_dex.py 在重建 ecj_dex.jar 时编译并覆盖
 * ecj.jar 里那份坏的 javax/lang/model/SourceVersion.class。
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11,
    RELEASE_12,
    RELEASE_13,
    RELEASE_14,
    RELEASE_15,
    RELEASE_16,
    RELEASE_17;

    // 原 JDK9 版用 Runtime.version().feature() 计算，Android 无该方法。
    // 这里固定为 RELEASE_17（等价于在 JDK17 上运行），保持 ecj 行为一致。
    private static final SourceVersion latestSupported = RELEASE_17;

    public static SourceVersion latest() {
        return latestSupported;
    }

    public static SourceVersion latestSupported() {
        return latestSupported;
    }

    private static SourceVersion getLatestSupported() {
        return latestSupported;
    }

    public static boolean isIdentifier(CharSequence cs) {
        if (cs == null || cs.length() < 1) {
            return false;
        }
        int cp = Character.codePointAt(cs, 0);
        if (!Character.isJavaIdentifierStart(cp)) {
            return false;
        }
        int i = Character.charCount(cp);
        while (i < cs.length()) {
            cp = Character.codePointAt(cs, i);
            if (!Character.isJavaIdentifierPart(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isName(CharSequence cs) {
        return isName(cs, latestSupported);
    }

    public static boolean isName(CharSequence cs, SourceVersion sv) {
        if (sv == null || sv.compareTo(RELEASE_6) < 0) {
            throw new IllegalArgumentException("must be at least RELEASE_6");
        }
        String s = (cs == null) ? null : cs.toString();
        if (s == null || s.isEmpty()) {
            return false;
        }
        int idx = s.indexOf('.');
        if (idx < 0) {
            return isIdentifier(s);
        }
        String first = s.substring(0, idx);
        if (!isIdentifier(first)) {
            return false;
        }
        int next = idx + 1;
        while (next < s.length()) {
            int nidx = s.indexOf('.', next);
            String rest = (nidx < 0) ? s.substring(next) : s.substring(next, nidx);
            if (!isIdentifier(rest)) {
                return false;
            }
            if (nidx < 0) {
                return true;
            }
            next = nidx + 1;
        }
        return false;
    }

    private static Set<String> immutableSet(String... words) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(words)));
    }

    // Java 8 关键字集合（含 assert/enum/default/strictfp，不含 var/yield/record/sealed 等受限标识）
    private static final Set<String> BASE_KW = immutableSet(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while");

    private static final Set<String> KW_10 = immutableSet(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var");

    private static final Set<String> KW_14 = immutableSet(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "yield");

    private static Set<String> keywords(SourceVersion sv) {
        if (sv.compareTo(RELEASE_14) >= 0) {
            return KW_14;
        }
        if (sv.compareTo(RELEASE_10) >= 0) {
            return KW_10;
        }
        return BASE_KW;
    }

    public static boolean isKeyword(CharSequence cs) {
        return isKeyword(cs, latestSupported);
    }

    public static boolean isKeyword(CharSequence cs, SourceVersion sv) {
        if (sv == null || sv.compareTo(RELEASE_6) < 0) {
            throw new IllegalArgumentException("must be at least RELEASE_6");
        }
        return keywords(sv).contains(cs == null ? null : cs.toString());
    }
}
