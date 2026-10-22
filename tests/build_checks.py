from pathlib import Path
import argparse,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--classes',type=Path,required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];tools=Path(r'D:\codex-tools\android-build');jdk=tools/'jdk/jdk-17.0.20.1+1/bin';bt=tools/'build-tools/android-15';jar=tools/'platform/android-35/android.jar'
def run(*args):
    r=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',errors='replace')
    if r.returncode:raise RuntimeError(r.stdout+'\n'+r.stderr)
    return r.stdout
work=a.work;work.mkdir(parents=True,exist_ok=True)
for name in ['classes','dex']:(work/name).mkdir(exist_ok=True)
(work/'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.weilin.termlens.tests" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/><application android:label="TermLens compatibility checks"/><instrumentation android:name="cn.weilin.termlens.probe.CompatChecks" android:targetPackage="cn.weilin.termlens.probe" android:functionalTest="true"/></manifest>''',encoding='utf-8')
run(bt/'aapt2.exe','link','-o',work/'unsigned.apk','--manifest',work/'AndroidManifest.xml','-I',jar)
run(jdk/'javac.exe','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',str(jar)+';'+str(bt/'core-lambda-stubs.jar'),'-cp',a.classes,'-d',work/'classes',root/'tests/CompatChecks.java')
run(jdk/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','23','--lib',jar,'--classpath',a.classes,'--output',work/'dex',*sorted((work/'classes').rglob('*.class')))
with zipfile.ZipFile(work/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:z.write(work/'dex/classes.dex','classes.dex')
run(bt/'zipalign.exe','-f','-p','4',work/'unsigned.apk',work/'aligned.apk')
a.output.parent.mkdir(parents=True,exist_ok=True)
run(jdk/'java.exe','-jar',bt/'lib/apksigner.jar','sign','--ks',root/'signing/probe.p12','--ks-key-alias','termlens-probe','--ks-pass','env:TERMLENS_PROBE_SIGN_PASSWORD','--v4-signing-enabled','false','--out',a.output,work/'aligned.apk')
run(jdk/'java.exe','-jar',bt/'lib/apksigner.jar','verify',a.output)
print('Built isolated instrumentation checks:',a.output)
