package dev.novelinlay;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.util.Locale;
import java.util.regex.Pattern;

final class QishuxiaSource {
    record Download(NovelBook book, String title) {}
    record SearchResult(String title, String author, URI downloadUrl) {
        @Override public String toString() { return "《" + title + "》 · " + author; }
    }
    private record Response(byte[] bytes, String disposition) {}

    static byte[] searchBody(String query) {
        String keyword = query.strip();
        Charset gbk = Charset.forName("GBK");
        if (keyword.isEmpty() || keyword.codePointCount(0, keyword.length()) > 30
                || keyword.codePoints().anyMatch(Character::isISOControl) || !gbk.newEncoder().canEncode(keyword)) {
            throw new IllegalArgumentException("每项请输入 1–30 字；网站不支持表情等特殊字符。");
        }
        return ("searchkey=" + URLEncoder.encode(keyword, gbk)).getBytes(StandardCharsets.US_ASCII);
    }

    static List<SearchResult> search(String query, Runnable checkCanceled) throws IOException {
        return search(URI.create("https://www.qishuxia.com/modules/article/search.php"), query, checkCanceled);
    }

    static void validateSearch(String title, String author) {
        if (title.isBlank() && author.isBlank()) throw new IllegalArgumentException("书名和作者至少填写一项。");
        if (!title.isBlank()) searchBody(title);
        if (!author.isBlank()) searchBody(author);
    }

    static List<SearchResult> search(String title, String author, Runnable checkCanceled) throws IOException {
        validateSearch(title, author);
        // One request avoids the site's minimum interval between searches.
        var found = search(title.isBlank() ? author : title, checkCanceled);
        return matchingResults(found, title, author);
    }

    static List<SearchResult> matchingResults(List<SearchResult> found, String title, String author) {
        String name = normalize(title), writer = normalize(author);
        return found.stream()
                .filter(book -> name.isEmpty() || normalize(book.title()).contains(name))
                .filter(book -> writer.isEmpty() || normalize(book.author()).equals(writer))
                .sorted(Comparator.comparingInt(book -> normalize(book.title()).equals(name) ? 0 : 1))
                .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
    }

    static List<SearchResult> search(URI endpoint, String query, Runnable checkCanceled) throws IOException {
        var response = request(endpoint, searchBody(query), false, checkCanceled);
        checkCanceled.run();
        return parseSearch(NovelBook.decode(response.bytes()));
    }

    static List<SearchResult> parseSearch(String html) throws IOException {
        if (html.contains("两次搜索的间隔时间不得少于")) {
            throw new IOException("网站要求两次搜索至少间隔 30 秒，请稍后再试。");
        }
        var results = new LinkedHashMap<URI, SearchResult>();
        boolean[] recognized = {false};
        // shortcut: parses the site's current result list, update if its HTML layout changes.
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            int listDepth;
            String field = "", href;
            StringBuilder title = new StringBuilder(), author = new StringBuilder();
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.UL) {
                    if (listDepth > 0) listDepth++;
                    else if (String.valueOf(attrs.getAttribute(HTML.Attribute.CLASS)).matches(".*\\btxt-list-row5\\b.*")) {
                        listDepth = 1;
                        recognized[0] = true;
                    }
                }
                if (listDepth == 0) return;
                if (tag == HTML.Tag.LI) { title.setLength(0); author.setLength(0); href = null; field = ""; }
                if (tag == HTML.Tag.SPAN) field = String.valueOf(attrs.getAttribute(HTML.Attribute.CLASS));
                if (tag == HTML.Tag.A && field.equals("s2")) href = (String) attrs.getAttribute(HTML.Attribute.HREF);
            }
            @Override public void handleText(char[] text, int pos) {
                if (listDepth == 0) return;
                if (field.equals("s2")) title.append(text);
                if (field.equals("s4")) author.append(text);
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (listDepth == 0) return;
                if (tag == HTML.Tag.LI && href != null && !title.toString().isBlank()) {
                    try {
                        URI url = downloadUri(URI.create("https://www.qishuxia.com/").resolve(href).toString());
                        results.putIfAbsent(url, new SearchResult(title.toString().strip(),
                                author.toString().isBlank() ? "作者未知" : author.toString().strip(), url));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (tag == HTML.Tag.SPAN) field = "";
                if (tag == HTML.Tag.UL) listDepth--;
            }
        }, true);
        if (!recognized[0]) throw new IOException("未识别到搜索结果，网站可能正在验证访问或已改版，请稍后重试。");
        return List.copyOf(results.values());
    }

    static URI downloadUri(String input) {
        URI uri;
        try { uri = URI.create(input.strip()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("请输入完整的 HTTPS 书籍页或 TXT 下载链接。"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !(uri.getHost().equalsIgnoreCase("www.qishuxia.com") || uri.getHost().equalsIgnoreCase("qishuxia.com"))
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) {
            throw new IllegalArgumentException("目前支持 https://www.qishuxia.com 的书籍页和 TXT 下载链接。");
        }
        var book = Pattern.compile("/book/([1-9][0-9]{0,11})/?").matcher(uri.getPath());
        String id;
        if (book.matches() && uri.getRawQuery() == null) id = book.group(1);
        else {
            var query = Pattern.compile("id=([1-9][0-9]{0,11})").matcher(uri.getRawQuery() == null ? "" : uri.getRawQuery());
            if (!uri.getPath().equals("/modules/article/txtarticle.php") || !query.matches()) {
                throw new IllegalArgumentException("请粘贴书籍详情页或 TXT 下载链接，首页地址不能直接导入。");
            }
            id = query.group(1);
        }
        return URI.create("https://www.qishuxia.com/modules/article/txtarticle.php?id=" + id);
    }

    static Download download(String input, Runnable checkCanceled) throws IOException {
        return fetch(downloadUri(input), checkCanceled);
    }

    static Download fetch(URI original, Runnable checkCanceled) throws IOException {
        Response response = request(original, null, true, checkCanceled);
        String text = NovelBook.decode(response.bytes());
        String prefix = text.stripLeading().toLowerCase(Locale.ROOT);
        if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<script")) {
            throw new IOException("下载内容是网页，没有返回 TXT 文件。");
        }
        return new Download(NovelBook.fromText(text), title(response.disposition(), original));
    }

    private static Response request(URI original, byte[] postBody, boolean txt, Runnable checkCanceled) throws IOException {
        int limit = txt ? NovelBook.MAX_BYTES : 2 * 1024 * 1024;
        String oversized = txt ? "TXT 超过 20 MiB，请下载后拆分再导入。" : "搜索响应过大，请使用更完整的书名重试。";
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        URI url = original;
        boolean retriedSession = false;
        long deadline = System.nanoTime() + 60_000_000_000L;
        for (int redirects = 0; redirects <= 3;) {
            checkCanceled.run();
            if (System.nanoTime() > deadline) throw new IOException("下载超时，请稍后重试。");
            HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
            try {
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setRequestProperty("Accept", "text/plain,application/octet-stream;q=0.9,*/*;q=0.5");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Referer", original.resolve("/").toString());
                for (var header : cookies.get(url, java.util.Map.of()).entrySet()) {
                    connection.setRequestProperty(header.getKey(), String.join("; ", header.getValue()));
                }
                if (postBody != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=GBK");
                    try (OutputStream out = connection.getOutputStream()) { out.write(postBody); }
                }
                int status = connection.getResponseCode();
                cookies.put(url, connection.getHeaderFields());
                if (status == 403 && !retriedSession && connection.getHeaderField("Set-Cookie") != null) {
                    // The site sets a session cookie on its first response and asks the browser to reload.
                    retriedSession = true;
                    continue;
                }
                if (status >= 300 && status <= 399) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("下载跳转缺少目标地址。");
                    URI next;
                    try { next = url.resolve(location); }
                    catch (IllegalArgumentException e) { throw new IOException("下载跳转地址无效。", e); }
                    if (!original.getScheme().equalsIgnoreCase(next.getScheme())
                            || !original.getHost().equalsIgnoreCase(next.getHost())
                            || original.getPort() != next.getPort() || next.getUserInfo() != null) {
                        throw new IOException("下载跳转到了其他站点，请改用浏览器下载后导入本地 TXT。");
                    }
                    if (status == 301 || status == 302 || status == 303) postBody = null;
                    url = next;
                    redirects++;
                    continue;
                }
                if (status != 200) throw new IOException("网站返回 HTTP " + status + "，请稍后重试或用浏览器下载后导入。");
                String type = String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
                if (txt && (type.contains("html") || type.contains("json"))) throw new IOException("网站返回了网页或验证提示，没有返回 TXT 文件。");
                if (connection.getContentLengthLong() > limit) throw new IOException(oversized);
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                try (InputStream stream = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int size; (size = stream.read(buffer)) != -1;) {
                        checkCanceled.run();
                        if (System.nanoTime() > deadline) throw new IOException("下载超时，请稍后重试。");
                        if (data.size() + size > limit) throw new IOException(oversized);
                        data.write(buffer, 0, size);
                    }
                }
                checkCanceled.run();
                return new Response(data.toByteArray(), connection.getHeaderField("Content-Disposition"));
            } finally { connection.disconnect(); }
        }
        throw new IOException("下载跳转过多，请用浏览器下载后导入本地 TXT。");
    }

    static String title(String disposition, URI url) {
        if (disposition != null) {
            var match = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)").matcher(disposition);
            if (match.find()) {
                String name = match.group(1).strip();
                if (name.chars().allMatch(c -> c <= 255)) {
                    try { name = NovelBook.decode(name.getBytes(StandardCharsets.ISO_8859_1)); }
                    catch (IOException ignored) { }
                }
                name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
                if (!name.isBlank()) return name.substring(0, Math.min(name.length(), 120));
            }
        }
        return "奇书网-" + String.valueOf(url.getQuery()).replace("id=", "") + ".txt";
    }
}
