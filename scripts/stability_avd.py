"""Create a new workspace-local disposable TV AVD from an already verified/extracted image."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--ram', type=int, choices=[2048, 3072, 4096], required=True)
    args = parser.parse_args()
    image = args.image.resolve()
    assert (image/'system.img').is_file() and (image/'kernel-ranchu-64').is_file()
    name = f'stability-tv-{args.ram}'
    root = args.root.resolve()
    avd = root/(name+'.avd')
    avd.mkdir(parents=True, exist_ok=False)
    settings = {
        'AvdId': name, 'avd.ini.displayname': name, 'abi.type': 'x86', 'hw.cpu.arch': 'x86',
        'hw.cpu.ncore': '2', 'hw.ramSize': str(args.ram), 'vm.heapSize': '384',
        'hw.device.name': 'tv_1080p', 'hw.device.manufacturer': 'Google', 'hw.keyboard': 'yes',
        'hw.dPad': 'yes', 'hw.mainKeys': 'yes', 'hw.lcd.width': '1920', 'hw.lcd.height': '1080',
        'hw.lcd.density': '320', 'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader_indirect',
        'hw.audioInput': 'no', 'hw.audioOutput': 'no', 'disk.dataPartition.size': '4G',
        'image.sysdir.1': image.as_posix()+'/', 'tag.id': 'android-tv', 'tag.display': 'Android TV',
        'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no', 'PlayStore.enabled': 'false',
    }
    (avd/'config.ini').write_text(''.join(f'{k}={v}\n' for k, v in settings.items()), encoding='utf-8')
    (root/(name+'.ini')).write_text(f'avd.ini.encoding=UTF-8\npath={avd.as_posix()}\ntarget=android-34\n', encoding='utf-8')
    print(f'Created {name}; set ANDROID_AVD_HOME={root} and launch with -no-snapshot -no-audio')


if __name__ == '__main__':
    main()
