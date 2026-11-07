"""Exercise the installer only inside an explicitly opted-in disposable container."""
import os
import pathlib
import shutil
import subprocess
import sys

assert pathlib.Path('/.dockerenv').exists() and os.environ.get('GEEKOC_ISOLATED_TEST') == '1'
assert os.geteuid() == 0
bundle = pathlib.Path(sys.argv[1]).resolve()
installer = pathlib.Path(__file__).resolve().parents[1] / 'Install-Linux.sh'

def install(*args, ok=True, user=None):
    command = ['bash', str(installer), *args, str(bundle)]
    if user:
        command = ['runuser', '-u', user, '--', *command]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
    if ok:
        assert result.returncode == 0, result.stdout
    else:
        assert result.returncode != 0, 'expected rejection'
    return result.stdout

assert 'sudo' in install(ok=False, user='builder')
status = subprocess.run(['dpkg-query', '-W', '-f=${db:Status-Status}', 'libxml2'], capture_output=True, text=True)
assert status.stdout != 'installed', 'fixture must start without libxml2'
assert 'libxml2' in install('--check', ok=False)
output = install()
assert 'Runtime libraries and secret-tool are ready' in output
assert pathlib.Path('/usr/bin/secret-tool').is_file()
for relative in ['geekoctunnel', 'native/geekoctunnel-core', 'native/geekoctunnel-helper', 'native/libopenconnect.so.5']:
    installed = pathlib.Path('/opt/geekoctunnel') / relative
    assert installed.stat().st_uid == 0 and installed.stat().st_mode & 0o022 == 0
    assert installed.read_bytes() == (bundle / relative).read_bytes()
    assert 'not found' not in subprocess.check_output(['ldd', str(installed)], text=True)
policy = pathlib.Path('/usr/share/polkit-1/actions/io.github.def-richard.geekoctunnel.connect.policy')
assert policy.is_file() and 'auth_admin_keep' not in policy.read_text()

# Reinstall is safe; an active executable prevents replacement.
install()
probe = pathlib.Path('/opt/geekoctunnel/native/installer-busy-probe')
shutil.copy2('/bin/sleep', probe)
process = subprocess.Popen([str(probe), '30'])
try:
    assert 'Close GeekOCTunnel' in install(ok=False)
finally:
    process.terminate()
    process.wait(timeout=5)
    probe.unlink()
helper = pathlib.Path('/opt/geekoctunnel/native/geekoctunnel-helper')
helper.unlink()
helper.symlink_to('/bin/true')
assert 'Refusing symlink destination' in install(ok=False)
assert helper.is_symlink()
print('INSTALLER: missing libraries repaired, ordinary-user check, ownership, reinstall, active-process and symlink guards passed.')
