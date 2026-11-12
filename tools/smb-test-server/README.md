# Throwaway SMB server for testing

Runs a real Samba server inside WSL (Ubuntu) as your normal user, on port 4450, with guest
access and one share called `test`. Nothing is installed system-wide and no sudo is needed:
the Samba packages are downloaded and unpacked into `~/smbsync-test`.

From a WSL shell, in this folder:

```bash
bash setup.sh
```

```bash
bash run.sh
```

Leave `run.sh` running. Shared files live in `~/smbsync-test/share`.

## Run the real-network tests (PowerShell, from the project root)

```powershell
$env:SMB_TEST_HOST = "127.0.0.1:4450"; .\gradlew.bat testDebugUnitTest
```

Without `SMB_TEST_HOST` those tests are skipped and only the sync-rule tests run.
Set `SMB_TEST_PERF_MB=300` as well to measure upload/download speed.

## Use it from the Android emulator

In the app, set the PC address to `10.0.2.2:4450`, share `test`, and leave username and
password empty.

## Remove it

Stop `run.sh`, then in WSL: `rm -rf ~/smbsync-test`
