#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
studio="${ANDROID_STUDIO_HOME:-/Applications/Android Studio.app/Contents}"
jdk="$studio/jbr/Contents/Home"
if [[ ! -x "$jdk/bin/javac" ]]; then jdk="$studio/jbr"; fi
if [[ ! -x "$jdk/bin/javac" ]]; then
  echo '找不到 Android Studio JDK，请设置 ANDROID_STUDIO_HOME 为 IDE Contents/安装目录。' >&2
  exit 1
fi
rm -rf build/classes build/test-classes build/package
mkdir -p build/classes build/test-classes build/package/novel-inlay/lib dist
find src/main/java -name '*.java' > build/sources.txt
"$jdk/bin/javac" --release 21 -encoding UTF-8 -cp "$studio/lib/*" -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
find src/test/java -name '*.java' > build/tests.txt
"$jdk/bin/javac" --release 21 -encoding UTF-8 -cp "build/classes:$studio/lib/*" -d build/test-classes @build/tests.txt
"$jdk/bin/java" -ea -Djava.awt.headless=true -cp "build/test-classes:build/classes:$studio/lib/*" dev.novelinlay.NovelBookTest
"$jdk/bin/java" -ea -cp "build/test-classes:build/classes:$studio/lib/*" dev.novelinlay.NovelShelfTest
"$jdk/bin/java" -ea --add-modules jdk.httpserver -cp "build/test-classes:build/classes:$studio/lib/*" dev.novelinlay.QishuxiaSourceTest
"$jdk/bin/jar" --create --file build/package/novel-inlay/lib/novel-inlay.jar -C build/classes .
(cd build/package && zip -q -r ../../dist/novel-inlay-0.8.2.zip novel-inlay)
echo "已生成：$PWD/dist/novel-inlay-0.8.2.zip"
