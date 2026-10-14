"""Fail the build if the delivered APK ever regains broad storage/media permissions."""
import pathlib
import re
import subprocess
import sys

forbidden = {
    'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.MANAGE_MEDIA',
    'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_MEDIA_VISUAL_USER_SELECTED', 'android.permission.ACCESS_MEDIA_LOCATION',
}
apks = sorted(pathlib.Path('app/build/outputs/apk/foss/debug').glob('*.apk'))
assert apks, 'No APK produced'
for apk in apks:
    output = subprocess.check_output([sys.argv[1], 'dump', 'permissions', str(apk)], text=True)
    declared = set(re.findall(r"uses-permission: name='([^']+)'", output))
    assert not declared.intersection(forbidden), f'Broad access permissions found: {declared.intersection(forbidden)}'
    print(f'{apk.name}: no broad file or media permissions.')
