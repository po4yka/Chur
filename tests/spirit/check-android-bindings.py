#!/usr/bin/env python3
"""Partial native API signature check. Does NOT compile Compose or make an APK.

Usage: KOTLIN_COMPILER_LIB=... python3 tests/spirit/check-android-bindings.py LIB_DIR
LIB_DIR contains actual filament-android.jar, gltfio-android.jar,
lifecycle-common.jar and android-legacy-api.jar. Artifacts are not vendored here.
The legacy API jar is Android4.1.1.4 from Maven, used only for stable type signatures.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[2]
libs = pathlib.Path(sys.argv[1]).resolve()
compiler = pathlib.Path(os.environ['KOTLIN_COMPILER_LIB'])
stdlib = next(compiler.glob('kotlin-stdlib-[0-9]*.jar'))
source = root / 'shared/app/src/androidMain/kotlin/dev/po4yka/chur/app/spirit/SpiritSurface.android.kt'
text = source.read_text()
imports = '\n'.join(line for line in text.splitlines() if line.startswith('import ') and
                    not any(value in line for value in ['androidx.compose', 'kotlinx.coroutines']))
text = 'package dev.po4yka.chur.app.spirit\n' + imports + '\n' + text[text.index('private class SpiritTexture'):]
# Current targetAPI29 uses isAttachedToWindow; old signature stub has windowToken.
text = text.replace('isAttachedToWindow', '(windowToken != null)')
with tempfile.TemporaryDirectory() as temp:
    temp = pathlib.Path(temp)
    (temp / 'BindingCheck.kt').write_text(text)
    paths = [stdlib] + [libs / filename for filename in
                       ['android-legacy-api.jar', 'filament-android.jar', 'gltfio-android.jar', 'lifecycle-common.jar']]
    for path in paths:
        assert path.is_file(), path
    common = root / 'shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/spirit'
    sources = [common / (name + '.kt') for name in ['SpiritInteraction', 'SpiritAnimation', 'SpiritPose', 'SpiritTail']]
    subprocess.run(['java', '-Xmx256m', '-cp', str(compiler / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, paths)),
                    *map(str, sources), str(temp / 'BindingCheck.kt'), '-d', str(temp / 'check.jar')], check=True)
print('PASS: partial Android/Filament binding signature check (not Compose/APK/device)')
