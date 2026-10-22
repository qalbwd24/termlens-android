"""Build the native PROCESS_TEXT client using the existing portable Android tools."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
PACKAGE = "cn.weilin.termlens.probe"
VERSION = "0.5.1"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def run(*command):
    result = subprocess.run([str(part) for part in command], capture_output=True,
                            text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        # Signing passwords are passed only by environment variable, never by command line.
        raise RuntimeError(result.stdout + "\n" + result.stderr)
    return result.stdout + result.stderr


def first(base, pattern):
    candidates = sorted(base.glob(pattern))
    if not candidates:
        raise FileNotFoundError(f"Tool not found: {base} / {pattern}")
    return candidates[-1]


def validate_source():
    manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    assert manifest.attrib["package"] == PACKAGE
    assert manifest.attrib[ANDROID + "versionName"].startswith(VERSION + "-")
    permissions = manifest.findall("uses-permission")
    assert [item.attrib[ANDROID + "name"] for item in permissions] == ["android.permission.INTERNET", "android.permission.SYSTEM_ALERT_WINDOW"]
    service = manifest.find("application/service")
    assert service.attrib[ANDROID + "permission"] == "android.permission.BIND_ACCESSIBILITY_SERVICE"
    config = ET.parse(ROOT / "res/xml/selection_accessibility.xml").getroot()
    expected_packages = PACKAGE + ",mark.via.gp,com.twitter.android,com.google.android.youtube,com.openai.chatgpt,com.microsoft.emmx,com.android.chrome,com.ichi2.anki,io.legado.app.release"
    assert config.attrib[ANDROID + "packageNames"] == expected_packages
    assert config.attrib[ANDROID + "canRetrieveWindowContent"] == "true"
    sdk = manifest.find("uses-sdk")
    assert sdk.attrib[ANDROID + "targetSdkVersion"] == "36"
    application = manifest.find("application")
    assert application.attrib[ANDROID + "allowBackup"] == "false"
    activity = next(a for a in application.findall("activity")
                    if a.attrib[ANDROID + "name"] == ".ExplainActivity")
    assert activity.attrib[ANDROID + "exported"] == "true"
    assert activity.attrib[ANDROID + "label"] == "Explain"
    entry = activity.find("intent-filter")
    assert entry.find("action").attrib[ANDROID + "name"] == "android.intent.action.PROCESS_TEXT"
    assert entry.find("category").attrib[ANDROID + "name"] == "android.intent.category.DEFAULT"
    assert entry.find("data").attrib[ANDROID + "mimeType"] == "text/plain"
    themes = ET.parse(ROOT / "res/values/styles.xml").getroot()
    popup = next(s for s in themes.findall("style") if s.attrib["name"] == "ExplainTheme")
    items = {item.attrib["name"]: item.text for item in popup.findall("item")}
    assert items["android:windowIsFloating"] == "false"
    assert items["android:windowIsTranslucent"] == "false"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "APK")
    parser.add_argument("--work-root", type=Path, default=ROOT / "build/work")
    args = parser.parse_args()
    tools = args.tools.resolve()
    validate_source()
    javac = first(tools, "jdk/**/bin/javac.exe")
    java, keytool = javac.with_name("java.exe"), javac.with_name("keytool.exe")
    aapt2 = first(tools, "build-tools/**/aapt2.exe")
    build_tools = aapt2.parent
    android_jar = first(tools, "platform/**/android.jar")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
    work = args.work_root.resolve() / (stamp + "-" + uuid.uuid4().hex[:8])
    for folder in ("generated", "classes", "dex"):
        (work / folder).mkdir(parents=True, exist_ok=False)
    shutil.copytree(ROOT / "res", work / "res")
    shutil.copytree(ROOT / "src", work / "src")
    shutil.copyfile(ROOT / "AndroidManifest.xml", work / "AndroidManifest.xml")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    print("Compiling native UI and resources...", flush=True)
    run(aapt2, "compile", "--dir", work / "res", "-o", work / "resources.zip")
    run(aapt2, "link", "-o", work / "unsigned.apk", "--manifest", work / "AndroidManifest.xml",
        "-I", android_jar, "--java", work / "generated", work / "resources.zip")
    sources = sorted((work / "src").rglob("*.java")) + sorted((work / "generated").rglob("*.java"))
    bootclasspath = str(android_jar) + os.pathsep + str(build_tools / "core-lambda-stubs.jar")
    run(javac, "-J-Dfile.encoding=UTF-8", "-J-Duser.language=en", "-encoding", "UTF-8",
        "-source", "8", "-target", "8", "-bootclasspath", bootclasspath,
        "-d", work / "classes", *sources)
    run(java, "-cp", build_tools / "lib/d8.jar", "com.android.tools.r8.D8",
        "--release", "--min-api", "23", "--lib", android_jar,
        "--output", work / "dex", *sorted((work / "classes").rglob("*.class")))
    with zipfile.ZipFile(work / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(work / "dex/classes.dex", "classes.dex")
    run(build_tools / "zipalign.exe", "-f", "-p", "4", work / "unsigned.apk", work / "aligned.apk")

    print("Signing and checking the exact APK...", flush=True)
    keystore = ROOT / "signing/probe.p12"
    if not keystore.exists():
        run(keytool, "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
            "-storepass:env", "TERMLENS_PROBE_SIGN_PASSWORD", "-alias", "termlens-probe",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=TermLens Local Probe")
    staged_apk = work / f"TermLens-selection-probe-{VERSION}.apk"
    signer = build_tools / "lib/apksigner.jar"
    run(java, "-jar", signer, "sign", "--ks", keystore,
        "--ks-key-alias", "termlens-probe", "--ks-pass", "env:TERMLENS_PROBE_SIGN_PASSWORD",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
        "--out", staged_apk, work / "aligned.apk")
    signature = run(java, "-jar", signer, "verify", "--verbose", "--print-certs", staged_apk)
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    assert "Verified using v3 scheme (APK Signature Scheme v3): true" in signature
    alignment = run(build_tools / "zipalign.exe", "-c", "-p", "4", staged_apk)
    badging = run(build_tools / "aapt.exe", "dump", "badging", staged_apk)
    permissions = run(build_tools / "aapt.exe", "dump", "permissions", staged_apk)
    manifest_dump = run(build_tools / "aapt.exe", "dump", "xmltree", staged_apk, "AndroidManifest.xml")
    assert f"package: name='{PACKAGE}'" in badging
    assert "targetSdkVersion:'36'" in badging
    assert "versionCode='15'" in badging
    assert "launchable-activity: name='cn.weilin.termlens.probe.MainActivity'" in badging
    assert "application-debuggable" not in badging
    assert "android.permission.INTERNET" in permissions
    assert "android.intent.action.PROCESS_TEXT" in manifest_dump
    assert "cn.weilin.termlens.probe.ExplainActivity" in manifest_dump or ".ExplainActivity" in manifest_dump
    with zipfile.ZipFile(staged_apk) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert all(item in names for item in ("AndroidManifest.xml", "classes.dex", "resources.arsc"))
        assert not any(name.startswith(("lib/", "signing/", "assets/")) for name in names)
        dex_bytes = archive.read("classes.dex")
        assert dex_bytes.startswith(b"dex\n")
        # Windows dexdump cannot mmap a DEX extracted internally from a ZIP.
        # Inspect bytes extracted from the final signed APK as a regular file.
        checked_dex = work / "verified-classes.dex"
        checked_dex.write_bytes(dex_bytes)
    dex_dump = run(build_tools / "dexdump.exe", "-f", checked_dex)
    assert "Lcn/weilin/termlens/probe/ExplainActivity;" in dex_dump
    assert "Lcn/weilin/termlens/probe/DiagnosticsActivity;" in dex_dump
    assert "Lcn/weilin/termlens/probe/SettingsActivity;" in dex_dump
    assert "Lcn/weilin/termlens/probe/SelectionOverlayService;" in dex_dump
    assert "Lcn/weilin/termlens/probe/SelectionMenuBridge;" in dex_dump
    assert "Lcn/weilin/termlens/probe/LookupInput;" in dex_dump
    assert "Lcn/weilin/termlens/probe/DesktopPrompts;" in dex_dump
    for name in ["AppData", "DataArchive", "SceneProfiles", "EdgeSpeechProvider", "EdgeToken", "StreamAnswer", "HistoryActivity", "DataMaintenance"]:
        assert f"Lcn/weilin/termlens/probe/{name};" in dex_dump
    sha256 = hashlib.sha256(staged_apk.read_bytes()).hexdigest()
    delivered = output / staged_apk.name
    if delivered.exists() and hashlib.sha256(delivered.read_bytes()).hexdigest() != sha256:
        raise FileExistsError(f"Refusing to replace a different APK: {delivered}; choose a new --output directory")
    shutil.copyfile(staged_apk, delivered)
    assert hashlib.sha256(delivered.read_bytes()).hexdigest() == sha256
    report = {
        "apk": delivered.name,
        "package": PACKAGE,
        "version": VERSION,
        "min_sdk": 23,
        "target_sdk": 36,
        "compile_android_jar": str(android_jar),
        "bytes": delivered.stat().st_size,
        "sha256": sha256,
        "checked": ["Java/D8 build", "APK v1/v2/v3 signature", "ZIP alignment", "manifest",
                    "PROCESS_TEXT filter", "native Activity/service/selection bridge classes",
                    "INTERNET and SYSTEM_ALERT_WINDOW only; no screenshot capability", "ZIP/DEX resources"],
        "device_test": "Not performed: no connected Android runtime was used.",
        "built_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat()
    }
    (output / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "verification.txt").write_text(signature + "\n" + badging + "\n" + permissions
        + "\n" + manifest_dump + "\nSHA256: " + sha256
        + "\nDevice runtime and MagicOS menu compatibility are not verified.\n", encoding="utf-8")
    (output / (delivered.name + ".sha256")).write_text(sha256 + "  " + delivered.name + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
