#!/bin/bash
# The last piece (build.sh), after the others: packs the runtime the app unpacks, as
# $VERSION.
set -euo pipefail
. "$RUNTIME/toolchain.sh"

GLIBC=$OUT/glibc$PREFIX
MESA=$OUT/mesa$PREFIX
USR=$OUT/runtime/usr
DEB=/usr/lib/$T   # deps.sh installs what we copy from here
# Not libmvec: nothing links it (the game needs only libc, libm and libresolv).
GLIBC_LIBS="ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libresolv.so.2 libpthread.so.0 \
            libdl.so.2 librt.so.1 libutil.so.1 libanl.so.1 libnss_files.so.2 libnss_dns.so.2"

rm -rf "$OUT/runtime" && mkdir -p $USR/lib $USR/bin $USR/share $USR/etc $USR/tmp

cp -a $MESA/lib/. $USR/lib/
# What only building against them needs: pkg-config files and the unversioned names. Mesa's install also brings a
# libarchive that nothing here links (the check below would say otherwise).
rm -rf $USR/lib/pkgconfig $USR/lib/libarchive.so*
find $USR/lib -maxdepth 1 -type l -name '*.so' -delete
cp -a $MESA/share/. $USR/share/

cp "$OUT/programs$PREFIX"/bin/* $USR/bin/
cp -a "$OUT/hybris$PREFIX/lib/hybris" $USR/lib/

# Stand-ins for two libraries that Vulkan drivers link but never use here (see the .c files).
$CC -shared -fPIC -O2 -Wl,-soname,libwayland-client.so.0 -o $USR/lib/libwayland-client.so.0 "$RUNTIME/assemble/wayland-client.c"
$CC -shared -fPIC -O2 -Wl,-soname,libSPIRV-Tools.so -o $USR/lib/libSPIRV-Tools.so "$RUNTIME/assemble/spirv-tools.c"

cp -a "$OUT/sdl$PREFIX"/lib/libSDL3.so.* $USR/lib/
cp "$OUT/sdl$PREFIX/bin/pocketfoundry-padtest" "$OUT/sdl$PREFIX/bin/pocketfoundry-inputtest" $USR/bin/

# seeds: what the game dlopens.
seeds="libGL.so.1 libGLX.so.0 libGLdispatch.so.0 libEGL.so.1 libvulkan.so.1
       libX11.so.6 libX11-xcb.so.1 libXext.so.6 libXrandr.so.2 libXi.so.6 libXcursor.so.1
       libXfixes.so.3 libXss.so.1 libXrender.so.1 libxkbcommon.so.0 libxkbcommon-x11.so.0"
targets="$(for s in $seeds; do echo $DEB/$s; done) $(find $USR/lib -maxdepth 2 -name '*.so*' -type f)
         $(find $USR/bin -type f)"
# What the GPU drivers the app downloads link (JulioFerrero/pocket-foundry-drivers, whose CI checks them against this
# list): Turnip, freedreno and PanVK need them whether or not anything here does.
driver_seeds="libdrm.so.2 libexpat.so.1 libz.so.1 libzstd.so.1 libstdc++.so.6 libgcc_s.so.1
              libX11.so.6 libX11-xcb.so.1 libXext.so.6 libXxf86vm.so.1 libxshmfence.so.1
              libxcb.so.1 libxcb-dri3.so.0 libxcb-glx.so.0 libxcb-present.so.0 libxcb-randr.so.0
              libxcb-shm.so.0 libxcb-sync.so.1 libxcb-xfixes.so.0"
seeds="$seeds $driver_seeds"
for s in $seeds; do cp -L $DEB/$s $USR/lib/; done
# What they all need, found as the game's loader would: here first, then in Debian's libraries, which it copies.
needed=$(python3 "$RUNTIME/assemble/needed.py" "$USR/lib:$USR/lib/hybris:$DEB" $targets)
echo "$needed" | while read -r name path; do
    case " $GLIBC_LIBS " in *" $name "*) continue ;; esac
    case "$path" in $USR/*) continue ;; esac
    cp -L "$path" "$USR/lib/$name"
done

for f in $GLIBC_LIBS; do cp -L $GLIBC/lib/$f $USR/lib/; done
# iconv's charsets beyond UTF-8 and UCS-4, which glibc has built in: the Unicode and Latin ones SDL or the game could
# ask for. glibc has 250 more (20 MB unpacked) that nothing here uses.
mkdir -p $USR/lib/gconv
cp -r $GLIBC/lib/gconv/gconv-modules $GLIBC/lib/gconv/gconv-modules.d $USR/lib/gconv/
for m in UTF-16 UTF-32 UTF-7 UNICODE ISO8859-1 ISO8859-15 CP1252; do cp $GLIBC/lib/gconv/$m.so $USR/lib/gconv/; done
# The C.UTF-8 locale that LANG names, made with our glibc's localedef: without it setlocale() falls back to "C".
mkdir -p "$WORK"
gzip -dc $GLIBC/share/i18n/charmaps/UTF-8.gz > "$WORK/UTF-8"
mkdir -p $USR/lib/locale
I18NPATH=$GLIBC/share/i18n $RUN $GLIBC/lib/ld-linux-aarch64.so.1 --library-path $GLIBC/lib $GLIBC/bin/localedef \
    --no-archive -i C -f "$WORK/UTF-8" $USR/lib/locale/C.utf8

mkdir -p $USR/share/X11
cp -r /usr/share/X11/xkb $USR/share/X11/
# The X server compiles keymaps from the rest; the layouts' descriptions are for settings programs.
rm $USR/share/X11/xkb/rules/*.xml $USR/share/X11/xkb/rules/*.lst $USR/share/X11/xkb/rules/xkb.dtd
# Xlib's locale files: without them, or without C.UTF-8 above, SDL opens no input method and a key beyond ASCII
# (ñ, é, Cyrillic) gives the game no text. The input method needs the Compose file too.
mkdir -p $USR/share/X11/locale/en_US.UTF-8
cp /usr/share/X11/locale/{locale.alias,locale.dir,compose.dir} $USR/share/X11/locale/
cp /usr/share/X11/locale/en_US.UTF-8/{XLC_LOCALE,Compose} $USR/share/X11/locale/en_US.UTF-8/
printf '127.0.0.1 localhost\n::1 localhost\n' > $USR/etc/hosts

# Debian's copies and glibc's come with their symbols.
find $USR -name '*.so*' -type f -exec $STRIP --strip-unneeded {} +
du -sh $USR/lib $USR/share
# xz's ARM64 filter rewrites the code's relative branch targets as absolute ones, which repeat: with -9e the runtime
# packs about 11% smaller than tar -J. lp=2 and pb=2 model the 4-byte instructions: 1.4% smaller again.
cd "$OUT/runtime" && tar -cf - usr | xz --arm64 --lzma2=preset=9e,lc=2,lp=2,pb=2 -T1 > "$OUT/runtime.tar.xz"
ls -la "$OUT/runtime.tar.xz"

# The loader ships as a "library": Android extracts those to the one place an app may exec from.
APK=$OUT/apk
rm -rf "$APK"
mkdir -p "$APK/assets" "$APK/jniLibs/arm64-v8a"
cp "$OUT/runtime.tar.xz" "$APK/assets/"
echo "$VERSION" > "$APK/assets/runtime.version"
echo "$PREFIX" > "$APK/assets/runtime.prefix"
cp $USR/lib/ld-linux-aarch64.so.1 "$APK/jniLibs/arm64-v8a/libld-linux.so"
