#!/bin/bash
# Runs inside the build container after `glibc`, `mesa`, `sdl` and `wm`: puts together the
# runtime the app unpacks into its files dir (/out/runtime/usr = $PREFIX) and
# packs it as /out/runtime.tar.xz.
set -euo pipefail

PREFIX=/data/data/app.pocketfoundry/files/usr
GLIBC=/out/glibc$PREFIX
MESA=/out/mesa$PREFIX
OUT=/out/runtime/usr
DEB=/usr/lib/aarch64-linux-gnu

apt-get update -q
apt-get install -y -q --no-install-recommends \
    libgl1 libglx0 libegl1 libglvnd0 libvulkan1 \
    libx11-6 libx11-xcb1 libxext6 libxrandr2 libxi6 libxcursor1 libxfixes3 libxss1 \
    libxrender1 libxkbcommon0 libxkbcommon-x11-0 libxxf86vm1 xkb-data > /dev/null

rm -rf /out/runtime && mkdir -p $OUT/lib $OUT/bin $OUT/share $OUT/etc $OUT/tmp

# Mesa as installed (drivers, dri/ links, ICD and glvnd JSON, drirc).
cp -a $MESA/lib/. $OUT/lib/
rm -rf $OUT/lib/pkgconfig
cp -a $MESA/share/. $OUT/share/

cp /out/wm/pocketfoundry-wm $OUT/bin/

# Our SDL with the app's audio and gamepad (SDL3_DYNAMIC_API points the game at it).
cp -a /out/sdl$PREFIX/lib/libSDL3.so* $OUT/lib/
cp /out/sdl$PREFIX/bin/pocketfoundry-padtest /out/sdl$PREFIX/bin/pocketfoundry-inputtest $OUT/bin/

# What the game dlopens (LD_DEBUG in Phase 1) and what Mesa and the WM link,
# with their dependencies, minus glibc, which comes from our build.
seeds="libGL.so.1 libGLX.so.0 libGLdispatch.so.0 libEGL.so.1 libvulkan.so.1
       libX11.so.6 libX11-xcb.so.1 libXext.so.6 libXrandr.so.2 libXi.so.6 libXcursor.so.1
       libXfixes.so.3 libXss.so.1 libXrender.so.1 libxkbcommon.so.0 libxkbcommon-x11.so.0"
targets="$(for s in $seeds; do echo $DEB/$s; done) $(find $OUT/lib -maxdepth 2 -name '*.so*' -type f) $OUT/bin/pocketfoundry-wm"
glibc_re='^(ld-linux-aarch64|libc|libm|libmvec|libresolv|libpthread|libdl|librt|libutil|libanl|libnss_[a-z]+)\.so'
for s in $seeds; do cp -L $DEB/$s $OUT/lib/; done
LD_LIBRARY_PATH=$OUT/lib ldd $targets 2>/dev/null | awk '/=> \// {print $1, $3}' | sort -u |
while read -r name path; do
    [[ $name =~ $glibc_re ]] && continue
    [ -e "$OUT/lib/$name" ] || cp -L "$path" "$OUT/lib/$name"
done
missing=$(LD_LIBRARY_PATH=$OUT/lib ldd $targets 2>/dev/null | grep "not found" | sort -u || true)
[ -z "$missing" ] || { echo "unresolved:"; echo "$missing"; exit 1; }

# glibc last: ldd above runs on the container's 2.41 and must not pick up our libc.
# Only what runs: no headers, static archives or tools.
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libmvec.so.1 libresolv.so.2 libpthread.so.0 \
         libdl.so.2 librt.so.1 libutil.so.1 libanl.so.1 libnss_files.so.2 libnss_dns.so.2; do
    cp -L $GLIBC/lib/$f $OUT/lib/
done
cp -r $GLIBC/lib/gconv $OUT/lib/

mkdir -p $OUT/share/X11
cp -r /usr/share/X11/xkb $OUT/share/X11/
printf '127.0.0.1 localhost\n::1 localhost\n' > $OUT/etc/hosts

find $OUT -name '*.so*' -type f -exec strip --strip-unneeded {} + 2>/dev/null || true
du -sh $OUT/lib $OUT/share
cd /out/runtime && tar -cJf /out/runtime.tar.xz usr
ls -la /out/runtime.tar.xz

# What the APK packs (app/build.gradle adds these dirs): the runtime with a
# version stamp, and the loader as a "library" so Android extracts it to the one
# place an app may exec from.
rm -rf /out/apk
mkdir -p /out/apk/assets /out/apk/jniLibs/arm64-v8a
cp /out/runtime.tar.xz /out/apk/assets/
sha256sum /out/runtime.tar.xz | cut -c1-16 > /out/apk/assets/runtime.version
cp $OUT/lib/ld-linux-aarch64.so.1 /out/apk/jniLibs/arm64-v8a/libld-linux.so
