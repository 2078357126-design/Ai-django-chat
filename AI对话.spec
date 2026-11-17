# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_submodules

hiddenimports = ['django.core.management.commands.runserver', 'django.core.management.commands.migrate']
hiddenimports += collect_submodules('Chat')
hiddenimports += collect_submodules('ai_django')
hiddenimports += collect_submodules('whitenoise')


a = Analysis(
    ['desktop.py'],
    pathex=[],
    binaries=[],
    datas=[('Chat/templates', 'Chat/templates'), ('Chat/migrations', 'Chat/migrations')],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='AI对话',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='AI对话',
)
app = BUNDLE(
    coll,
    name='AI对话.app',
    icon=None,
    bundle_identifier=None,
)
