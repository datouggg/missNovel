package dev.novelinlay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

public record NovelBook(String id, String text, List<String> pages, List<Chapter> chapters) {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    // shortcut: infer chapters from short standalone headings; add custom rules if a real book needs them.
    private static final Pattern HEADING = Pattern.compile(
            "(?i)(?:正文\\h*)?(?:第\\h*[0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+\\h*[章回节卷部篇].*"
                    + "|chapter\\h+[0-9]+.*|(?:序章|序言|前言|楔子|引子|尾声|终章|后记|番外)(?:\\h.*|[:：].*|$))");

    public record Chapter(String title, int page) {
        @Override public String toString() { return title; }
    }

    public static NovelBook read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("请选择本地 TXT 文件。");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("首版支持最大 20 MiB 的 TXT 文件。");
        return fromText(decode(bytes));
    }

    static String decode(byte[] bytes) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            skip = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        try {
            return charset.newDecoder().decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        } catch (CharacterCodingException e) {
            if (skip != 0) throw new IOException("TXT 编码损坏，请另存为 UTF-8 后重试。", e);
            try {
                return Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException invalid) {
                throw new IOException("无法识别编码，请将 TXT 另存为 UTF-8。", invalid);
            }
        }
    }

    static NovelBook fromText(String source) throws IOException {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("转换为 UTF-8 后超过 20 MiB，请先拆分 TXT。");
        }
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IOException("文件含非文本控制字符，请使用纯文本 TXT。");
        }
        List<String> pages = new ArrayList<>();
        List<Chapter> chapters = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            paragraph = paragraph.strip().replace('\t', ' ');
            if (paragraph.length() <= 80 && HEADING.matcher(paragraph).matches()) {
                chapters.add(new Chapter(paragraph, pages.size()));
            }
            for (int start = 0; start < paragraph.length();) {
                int end = start;
                for (int count = 0; count < 48 && end < paragraph.length(); count++) {
                    end += Character.charCount(paragraph.codePointAt(end));
                }
                if (end < paragraph.length()) {
                    for (int i = end; i > start + (end - start) / 2; i--) {
                        if ("。！？；，.!?; ".indexOf(paragraph.charAt(i - 1)) >= 0) {
                            end = i;
                            break;
                        }
                    }
                }
                pages.add(paragraph.substring(start, end));
                start = end;
            }
        }
        if (pages.isEmpty()) throw new IOException("TXT 中没有可阅读的文字。");
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
            return new NovelBook(id, text, List.copyOf(pages), List.copyOf(chapters));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void cache(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "import-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, directory.resolve(id + ".txt"), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, directory.resolve(id + ".txt"), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public int clamp(int page) { return Math.max(0, Math.min(page, pages.size() - 1)); }
}
