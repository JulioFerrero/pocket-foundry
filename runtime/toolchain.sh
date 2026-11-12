# Sourced by every piece: the tools that build for arm64, the same on an arm64 machine and on an x86-64 one that
# cross-compiles (deps.sh installs them). Debian names arm64's tools aarch64-linux-gnu-* on both.
T=aarch64-linux-gnu
export CC=$T-gcc CXX=$T-g++ AR=$T-ar STRIP=$T-strip PKG_CONFIG=$T-pkg-config
# Runs one of the arm64 programs built here.
case "$(uname -m)" in aarch64|arm64) RUN= ;; *) RUN=qemu-aarch64 ;; esac
MESON_CROSS="--cross-file $RUNTIME/aarch64.ini"
CMAKE_CROSS="-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64
             -DCMAKE_C_COMPILER=$CC -DCMAKE_CXX_COMPILER=$CXX -DPKG_CONFIG_EXECUTABLE=$PKG_CONFIG"
