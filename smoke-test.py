#!/usr/bin/env python3
"""Run two real IDE processes with isolated config, cache and plugin directories."""
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent
studio = Path(os.environ.get("ANDROID_STUDIO_HOME", "/Applications/Android Studio.app/Contents"))
sandbox = Path(os.environ.get("NOVEL_TEST_SANDBOX", str(root / "build/smoke"))).resolve()
subprocess.run(["bash", str(root / "build.sh")], check=True)
plugin = sandbox / "plugins/novel-inlay/lib/novel-inlay.jar"
plugin.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(root / "build/package/novel-inlay/lib/novel-inlay.jar") as source, zipfile.ZipFile(plugin, "w") as target:
    for entry in source.infolist():
        content = source.read(entry.filename)
        if entry.filename == "META-INF/plugin.xml":
            content = content.replace(b"</extensions>", b'<appStarter id="novel-inlay-smoke" implementation="dev.novelinlay.IdeSmokeTest"/></extensions>')
        target.writestr(entry, content)
    test = root / "build/test-classes/dev/novelinlay/IdeSmokeTest.class"
    target.write(test, "dev/novelinlay/IdeSmokeTest.class")
info = json.loads((studio / "Resources/product-info.json").read_text())
launch = info["launch"][0]
jdk = studio / "jbr/Contents/Home"
if not jdk.exists():
    jdk = studio / "jbr"
command = [str(jdk / "bin/java"), "-Xmx1536m", "-ea"]
command += [x.replace("$APP_PACKAGE", str(studio.parent)) for x in launch["additionalJvmArguments"]]
gui = "--gui" in sys.argv
if gui:
    print("GUI 测试需保持测试窗口前台，测试期间不要使用键鼠；桌面焦点变化会使测试失败。", flush=True)
command += [f"-Djava.awt.headless={str(not gui).lower()}", f"-Dnovel.test.gui={str(gui).lower()}",
            f"-Dnovel.test.preview={root / 'preview.png'}", "-Didea.is.unit.test=false", "-Didea.initially.ask.config=false",
            "-Didea.trust.all.projects=true", "-Didea.config.import.enabled=false", "-Dide.no.platform.update=true",
            f"-Didea.home.path={studio}", f"-Dnovel.test.source={root / 'sample.txt'}"]
for name in ("config", "system", "plugins", "log"):
    (sandbox / name).mkdir(exist_ok=True)
    command.append(f"-Didea.{name}.path={sandbox / name}")
command += ["-cp", os.pathsep.join(str(studio / "lib" / name) for name in launch["bootClassPathJarNames"]),
            launch["mainClass"], "novel-inlay-smoke"]
for phase in (("gui",) if gui else ("import", "restore")):
    log = sandbox / f"{phase}.log"
    with log.open("w") as output:
        result = subprocess.run(command + [phase], stdout=output, stderr=subprocess.STDOUT, timeout=150)
    text = log.read_text(errors="replace")
    print("\n".join(line for line in text.splitlines() if line.startswith(("PASS:", "IDE_SMOKE"))))
    if result.returncode or "IDE_SMOKE_PASS" not in text:
        print(text[-10000:])
        raise SystemExit(f"IDE smoke test failed; see {log}")
print("PASS: " + ("GUI rendering, real timer, pause, focus behavior" if gui else
                  "import, real editor inlay, source integrity, IDE persistence, restart restore"))
