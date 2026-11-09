#!/bin/bash
# Runs inside the build container after the other pieces (runtime/build.sh): packs the runtime the app unpacks, as
# $VERSION.
set -euo pipefail

GLIBC=/out/glibc$PREFIX
MESA=/out/mesa$PREFIX
OUT=/out/runtime/usr
DEB=/usr/lib/aarch64-linux-gnu   # the Dockerfile installs what we copy from here
GLIBC_LIBS="ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libmvec.so.1 libresolv.so.2 libpthread.so.0 \
            libdl.so.2 librt.so.1 libutil.so.1 libanl.so.1 libnss_files.so.2 libnss_dns.so.2"

rm -rf /out/runtime && mkdir -p $OUT/lib $OUT/bin $OUT/share $OUT/etc $OUT/tmp

cp -a $MESA/lib/. $OUT/lib/
# What only building against them needs: pkg-config files and the unversioned names. Mesa's install also brings a
# libarchive that nothing here links (the ldd check below would say otherwise).
rm -rf $OUT/lib/pkgconfig $OUT/lib/libarchive.so*
find $OUT/lib -maxdepth 1 -type l -name '*.so' -delete
cp -a $MESA/share/. $OUT/share/

cp /out/programs$PREFIX/bin/* $OUT/bin/
cp -a /out/hybris$PREFIX/lib/hybris $OUT/lib/

# Stand-ins for two libraries that Vulkan drivers link but never use here (see the .c files).
gcc -shared -fPIC -O2 -Wl,-soname,libwayland-client.so.0 -o $OUT/lib/libwayland-client.so.0 /runtime/assemble/wayland-client.c
gcc -shared -fPIC -O2 -Wl,-soname,libSPIRV-Tools.so -o $OUT/lib/libSPIRV-Tools.so /runtime/assemble/spirv-tools.c

cp -a /out/sdl$PREFIX/lib/libSDL3.so.* $OUT/lib/
cp /out/sdl$PREFIX/bin/pocketfoundry-padtest /out/sdl$PREFIX/bin/pocketfoundry-inputtest $OUT/bin/

# seeds: what the game dlopens.
seeds="libGL.so.1 libGLX.so.0 libGLdispatch.so.0 libEGL.so.1 libvulkan.so.1
       libX11.so.6 libX11-xcb.so.1 libXext.so.6 libXrandr.so.2 libXi.so.6 libXcursor.so.1
       libXfixes.so.3 libXss.so.1 libXrender.so.1 libxkbcommon.so.0 libxkbcommon-x11.so.0"
targets="$(for s in $seeds; do echo $DEB/$s; done) $(find $OUT/lib -maxdepth 2 -name '*.so*' -type f)
         $(find $OUT/bin -type f)"
for s in $seeds; do cp -L $DEB/$s $OUT/lib/; done
LD_LIBRARY_PATH=$OUT/lib:$OUT/lib/hybris ldd $targets 2>/dev/null | awk '/=> \// {print $1, $3}' | sort -u |
while read -r name path; do
    case " $GLIBC_LIBS " in *" $name "*) continue ;; esac
    case "$path" in $OUT/*) continue ;; esac
    cp -L "$path" "$OUT/lib/$name"
done
missing=$(LD_LIBRARY_PATH=$OUT/lib:$OUT/lib/hybris ldd $targets 2>/dev/null | grep "not found" | sort -u || true)
[ -z "$missing" ] || { echo "unresolved:"; echo "$missing"; exit 1; }

# glibc last: ldd above runs on the container's 2.41 and must not pick up our libc.
for f in $GLIBC_LIBS; do cp -L $GLIBC/lib/$f $OUT/lib/; done
# iconv's charsets beyond UTF-8 and UCS-4, which glibc has built in: the Unicode and Latin ones SDL or the game could
# ask for. glibc has 250 more (20 MB unpacked) that nothing here uses.
mkdir -p $OUT/lib/gconv
cp -r $GLIBC/lib/gconv/gconv-modules $GLIBC/lib/gconv/gconv-modules.d $OUT/lib/gconv/
for m in UTF-16 UTF-32 UTF-7 UNICODE ISO8859-1 ISO8859-15 CP1252; do cp $GLIBC/lib/gconv/$m.so $OUT/lib/gconv/; done
# The C.UTF-8 locale that LANG names, made with our glibc's localedef: without it setlocale() falls back to "C".
gzip -dc $GLIBC/share/i18n/charmaps/UTF-8.gz > /tmp/UTF-8
mkdir -p $OUT/lib/locale
I18NPATH=$GLIBC/share/i18n $GLIBC/lib/ld-linux-aarch64.so.1 --library-path $GLIBC/lib $GLIBC/bin/localedef \
    --no-archive -i C -f /tmp/UTF-8 $OUT/lib/locale/C.utf8

mkdir -p $OUT/share/X11
cp -r /usr/share/X11/xkb $OUT/share/X11/
# Xlib's locale files: without them, or without C.UTF-8 above, SDL opens no input method and a key beyond ASCII
# (ñ, é, Cyrillic) gives the game no text. The input method needs the Compose file too.
mkdir -p $OUT/share/X11/locale/en_US.UTF-8
cp /usr/share/X11/locale/{locale.alias,locale.dir,compose.dir} $OUT/share/X11/locale/
cp /usr/share/X11/locale/en_US.UTF-8/{XLC_LOCALE,Compose} $OUT/share/X11/locale/en_US.UTF-8/
printf '127.0.0.1 localhost\n::1 localhost\n' > $OUT/etc/hosts

# Debian's copies and glibc's come with their symbols.
find $OUT -name '*.so*' -type f -exec strip --strip-unneeded {} +
du -sh $OUT/lib $OUT/share
# xz's ARM64 filter rewrites the code's relative branch targets as absolute ones, which repeat: with -9e the runtime
# packs about 11% smaller than tar -J.
cd /out/runtime && tar -cf - usr | xz --arm64 --lzma2=preset=9e -T1 > /out/runtime.tar.xz
ls -la /out/runtime.tar.xz

# The loader ships as a "library": Android extracts those to the one place an app may exec from.
rm -rf /out/apk
mkdir -p /out/apk/assets /out/apk/jniLibs/arm64-v8a
cp /out/runtime.tar.xz /out/apk/assets/
echo "$VERSION" > /out/apk/assets/runtime.version
echo "$PREFIX" > /out/apk/assets/runtime.prefix
cp $OUT/lib/ld-linux-aarch64.so.1 /out/apk/jniLibs/arm64-v8a/libld-linux.so
