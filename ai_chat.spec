# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置，供三平台自动构建使用。"""

import os
import sys

from PyInstaller.utils.hooks import collect_submodules

datas = [
    ('Chat/templates', 'Chat/templates'),
    ('Chat/migrations', 'Chat/migrations'),
]

# 构建前生成的预置配置（含 API Key），有则打进包内
if os.path.exists('preset.env'):
    datas.append(('preset.env', '.'))

hiddenimports = (
    collect_submodules('Chat')
    + collect_submodules('ai_django')
    + collect_submodules('whitenoise')
    + [
        'django.core.management.commands.runserver',
        'django.core.management.commands.migrate',
    ]
)

a = Analysis(
    ['desktop.py'],
    pathex=[],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter'],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='AIChat',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
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
    upx=False,
    upx_exclude=[],
    name='AIChat',
)

if sys.platform == 'darwin':
    app = BUNDLE(
        coll,
        name='AIChat.app',
        icon='assets/app.icns',
        bundle_identifier='com.aichat.desktop',
        info_plist={
            'CFBundleDisplayName': 'AI云',
            'CFBundleName': 'AI云',
        },
    )
