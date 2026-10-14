package dev.novelinlay;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class QishuxiaSourceTest {
    public static void main(String[] args) throws Exception {
        String searchHtml = """
                <html><body><a href="/book/999/">广告</a><ul class="txt-list txt-list-row5">
                <li><span class="s2"><b>作品名称</b></span><span class="s4">作者</span></li>
                <li><span class="s2"><a href="/book/123/">原创 &amp; 测试</a></span>
                <span class="s3"><a href="/book/123/456.html">最新章节</a></span><span class="s4">测试作者</span></li>
                <li><span class="s2"><a href="https://www.qishuxia.com/book/123/">重复</a></span></li>
                <li><span class="s2"><a href="https://evil.test/book/456/">外站</a></span></li>
                <li><span class="s2"><a href="/book/456/">同名测试</a></span></li>
                </ul></body></html>
                """;
        var results = QishuxiaSource.parseSearch(searchHtml);
        NovelBookTest.check(results.size() == 2 && results.getFirst().title().equals("原创 & 测试")
                && results.getFirst().author().equals("测试作者") && results.get(1).author().equals("作者未知"),
                "search parses title/author/entities, deduplicates and excludes foreign/chapter/ad links");
        NovelBookTest.check(QishuxiaSource.parseSearch("<ul class='txt-list-row5'></ul>").isEmpty(), "empty search results");
        NovelBookTest.expectFailure(() -> QishuxiaSource.parseSearch("<html>验证访问</html>"));
        try {
            QishuxiaSource.parseSearch("<html>两次搜索的间隔时间不得少于 30 秒</html>");
            throw new AssertionError("Rate limit ignored");
        } catch (java.io.IOException expectedError) {
            NovelBookTest.check(expectedError.getMessage().contains("30 秒"), "rate limit explained separately");
        }
        for (String[] input : new String[][]{{" ", " "}, {"小说", "作者🌌"}, {"书".repeat(31), "作者"}}) {
            try { QishuxiaSource.validateSearch(input[0], input[1]); throw new AssertionError("Accepted invalid fields"); }
            catch (IllegalArgumentException correct) { }
        }
        QishuxiaSource.validateSearch("", "耳根");
        QishuxiaSource.validateSearch("我欲封天", "");
        var derivative = new QishuxiaSource.SearchResult("我欲封天外传", "耳根", URI.create("https://www.qishuxia.com/book/11/"));
        var unrelated = new QishuxiaSource.SearchResult("我欲封天", "折耳根", URI.create("https://www.qishuxia.com/book/12/"));
        var exact = new QishuxiaSource.SearchResult("我欲封天", "耳根", URI.create("https://www.qishuxia.com/book/13/"));
        var otherTitle = new QishuxiaSource.SearchResult("光阴之外", "耳根", URI.create("https://www.qishuxia.com/book/14/"));
        var mixed = java.util.List.of(derivative, unrelated, otherTitle, exact);
        NovelBookTest.check(QishuxiaSource.matchingResults(mixed, "我欲封天", "耳根").equals(java.util.List.of(exact, derivative)),
                "title and exact author combined; full title first; similar pen names excluded");
        NovelBookTest.check(QishuxiaSource.matchingResults(mixed, "", "耳根").equals(java.util.List.of(derivative, otherTitle, exact)),
                "author-only search returns matching author books");
        NovelBookTest.check(QishuxiaSource.matchingResults(mixed, "我欲封天", "").equals(java.util.List.of(unrelated, exact, derivative)),
                "title-only search excludes unrelated titles and ranks exact matches first");
        NovelBookTest.check(QishuxiaSource.matchingResults(mixed, "我欲封天", "不存在").isEmpty(), "wrong author never silently ignored");
        var formatted = new QishuxiaSource.SearchResult("ＡＢＣ 小说", "Test Writer", URI.create("https://www.qishuxia.com/book/15/"));
        NovelBookTest.check(QishuxiaSource.matchingResults(java.util.List.of(formatted), "abc小说", "test writer").size() == 1,
                "matching normalizes width case and whitespace");
        for (String invalid : new String[]{" ", "书".repeat(31), "书\n名", "小说🌌"}) {
            try { QishuxiaSource.searchBody(invalid); throw new AssertionError("Accepted invalid query"); }
            catch (IllegalArgumentException correct) { }
        }
        String form = new String(QishuxiaSource.searchBody(" 西游记 & "), StandardCharsets.US_ASCII);
        NovelBookTest.check(form.equals("searchkey=%CE%F7%D3%CE%BC%C7+%26"), "GBK query encoding and form escaping");
        var expected = URI.create("https://www.qishuxia.com/modules/article/txtarticle.php?id=123");
        NovelBookTest.check(QishuxiaSource.downloadUri(" https://qishuxia.com/book/123/ ").equals(expected), "book URL maps to download");
        NovelBookTest.check(QishuxiaSource.downloadUri(expected.toString()).equals(expected), "download URL accepted");
        for (String invalid : new String[]{"https://www.qishuxia.com/", "http://www.qishuxia.com/book/1/",
                "https://www.qishuxia.com.evil.test/book/1/", "https://user@www.qishuxia.com/book/1/",
                "https://www.qishuxia.com/book/1/?x=1", "https://www.qishuxia.com:8443/book/1/",
                "file:///tmp/a.txt", "https://www.qishuxia.com/modules/article/txtarticle.php?id=1&extra=2"}) {
            try { QishuxiaSource.downloadUri(invalid); throw new AssertionError("Accepted invalid URI"); }
            catch (IllegalArgumentException correct) { }
        }
        String story = "第一章 测试\n这是原创测试文本🌌。\n第二章 再见\n测试结束。";
        byte[] bytes = story.getBytes(StandardCharsets.UTF_8);
        String rawFilename = new String("原创样本.txt".getBytes("GB18030"), StandardCharsets.ISO_8859_1);
        NovelBookTest.check(QishuxiaSource.title("attachment; filename=" + rawFilename, expected).equals("原创样本.txt"), "site GBK filename decoded");
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger searchRequests = new AtomicInteger();
        server.createContext("/search", exchange -> {
            searchRequests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
            if (!exchange.getRequestMethod().equals("POST") || !body.equals(form)) {
                exchange.sendResponseHeaders(400, -1);
            } else if (exchange.getRequestHeaders().getFirst("Cookie") == null
                    || !exchange.getRequestHeaders().getFirst("Cookie").contains("session=ok")) {
                exchange.getResponseHeaders().add("Set-Cookie", "session=ok; Path=/; HttpOnly");
                exchange.sendResponseHeaders(403, -1);
            } else {
                byte[] response = searchHtml.getBytes("GBK");
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=gbk");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.createContext("/download", exchange -> {
            attempts.incrementAndGet();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie == null || !cookie.contains("session=ok")) {
                exchange.getResponseHeaders().add("Set-Cookie", "session=ok; Path=/; HttpOnly");
                exchange.sendResponseHeaders(403, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=test.txt");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.createContext("/html", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            byte[] html = "<html>not a novel</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, NovelBook.MAX_BYTES + 1L);
            exchange.close();
        });
        server.createContext("/denied", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/download");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/external", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.com/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var found = QishuxiaSource.search(base.resolve("/search"), "西游记 &", () -> {});
            NovelBookTest.check(found.equals(results) && searchRequests.get() == 2,
                    "background search posts encoded query, retries cookie session and decodes GBK results");
            NovelBookTest.check(found.getFirst().downloadUrl().equals(expected), "chosen search result maps to TXT download");
            var downloaded = QishuxiaSource.fetch(base.resolve("/download"), () -> {});
            NovelBookTest.check(attempts.get() == 2, "session cookie retry succeeds once");
            NovelBookTest.check(downloaded.book().text().equals(story) && downloaded.book().chapters().size() == 2,
                    "network download feeds original chapter parser");
            NovelBookTest.check(downloaded.title().equals("test.txt"), "download filename preserved");
            NovelBookTest.check(QishuxiaSource.fetch(base.resolve("/redirect"), () -> {}).book().id().equals(downloaded.book().id()),
                    "same-site redirect preserves content identity");
            for (String path : new String[]{"/html", "/large", "/denied", "/external"}) {
                NovelBookTest.expectFailure(() -> QishuxiaSource.fetch(base.resolve(path), () -> {}));
            }
            AtomicInteger checks = new AtomicInteger();
            try {
                QishuxiaSource.fetch(base.resolve("/download"), () -> {
                    if (checks.incrementAndGet() == 3) throw new java.util.concurrent.CancellationException();
                });
                throw new AssertionError("Cancellation ignored");
            } catch (java.util.concurrent.CancellationException correct) { }
        } finally { server.stop(0); }
        System.out.println("PASS: search GBK POST/session/result parsing/empty/error cases; download URL validation, cookies, redirect boundary, chapter parser, HTML/oversize/error rejection, cancellation");
    }
}
