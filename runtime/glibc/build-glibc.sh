#!/bin/bash
# Runs inside the build container: glibc with Termux's Android patches, mirroring termux-patches/build.sh for aarch64.
set -euo pipefail

VERSION=2.44
SHA256=37f600f2bef3c5e8300147059568b2a2e40a7ad6ccc65ce942556d49429cc667
PREFIX=/data/data/app.pocketfoundry/files/usr
PATCHES=$(dirname "$(realpath "$0")")/termux-patches
SRC=/work/glibc-$VERSION   # container-local: a Mac bind mount is slow for builds

mkdir -p /build && cd /build
[ -f glibc-$VERSION.tar.xz ] || curl -fLO https://ftp.gnu.org/gnu/libc/glibc-$VERSION.tar.xz
echo "$SHA256  glibc-$VERSION.tar.xz" | sha256sum -c
rm -rf "$SRC" && mkdir -p /work && tar -xf glibc-$VERSION.tar.xz -C /work
cd "$SRC"

# system()/popen() use Android's shell: apps can't exec files from their data dir.
for p in "$PATCHES"/*.patch; do
    sed -e "s#@TERMUX_PREFIX@/bin/sh#/system/bin/sh#g" \
        -e "s#@TERMUX_PREFIX_CLASSICAL@#$PREFIX#g" \
        -e "s#@TERMUX_PREFIX@#$PREFIX#g" "$p" | patch --silent -p1
done
# Then Pocket Foundry's own.
for p in "$(dirname "$PATCHES")"/patches/*.patch; do
    patch --silent -p1 < "$p"
done

# termux_step_pre_configure, aarch64 only
rm sysdeps/unix/sysv/linux/*/clone3.S
cp "$PATCHES"/{shm{at,ctl,dt,get}.c,mprotect.c,syscall.c,fakesyscall*.h,fake_epoll_pwait2.c,setfs{u,g}id.c} \
    sysdeps/unix/sysv/linux/
cp "$PATCHES"/{android_passwd_group.*,android_system_user_ids.h} nss/
bash "$PATCHES"/gen-android-ids.sh /data/data/app.pocketfoundry/files nss/android_ids.h \
    "$PATCHES"/android_system_user_ids.h
cp "$PATCHES"/syslog.c misc/
cp "$PATCHES"/shmem-android.* sysvipc/

# Syscalls Android's seccomp filter traps go to fake implementations.
A=sysdeps/unix/sysv/linux/aarch64
J="$PATCHES"/fakesyscall.json
mv $A/syscall.S $A/syscallS.S
H=$A/disabled-syscall.h
for j in $(jq -r '.[] | .[]' "$J"); do
    grep "#define __NR_${j} " $A/arch-syscall.h || true
    sed -i "/#define __NR_${j} /d" $A/arch-syscall.h
done >> $H
{
    echo -e "\n#define DISABLED_SYSCALL_WITH_FAKESYSCALL \\"
    IFS=$'\n'
    for j in $(jq -r '. | keys | .[]' "$J"); do
        need_return=false
        for z in $(jq -r '."'"${j}"'" | .[]' "$J"); do
            if grep -q "^#define __NR_${z} " $H; then
                echo -e "\tcase __NR_${z}: \\"
                need_return=true
            elif [[ ${z} =~ ^[0-9]+$ ]]; then
                echo -e "\tcase ${z}: \\"
                need_return=true
            fi
        done
        [ "$need_return" = true ] && echo -e "\t\treturn ${j}; \\"
    done
    unset IFS
} >> $H
sed -i '$ s| \\||' $H

for i in /dev/stderr:/proc/self/fd/2 /dev/stdin:/proc/self/fd/0 /dev/stdout:/proc/self/fd/1; do
    for j in $(grep -s -r -l "${i%%:*}" .); do
        sed -i "s|${i%%:*}|${i//*:}|g" "$j"
    done
done
sed -i "s/\"$VERSION\"/\"$VERSION-pocketfoundry\"/" version.h

# termux_step_configure, without systemtap
mkdir -p build && cd build
{
    echo "slibdir=$PREFIX/lib"
    echo "rtlddir=$PREFIX/lib"
    echo "sbindir=$PREFIX/bin"
    echo "rootsbindir=$PREFIX/bin"
} > configparms
../configure \
    --prefix=$PREFIX --libdir=$PREFIX/lib --libexecdir=$PREFIX/lib --includedir=$PREFIX/include \
    --host=aarch64-linux-gnu --build=aarch64-linux-gnu \
    --with-pkgversion="GNU libc for Android/app.pocketfoundry" \
    --enable-bind-now --enable-fortify-source --disable-multi-arch \
    --enable-stack-protector=strong --disable-nscd --disable-profile --disable-werror \
    --disable-default-pie --enable-memory-tagging

make -O -j"$(nproc)"
rm -rf /out/glibc && make install DESTDIR=/out/glibc
rm -f /out/glibc$PREFIX/etc/ld.so.cache
echo "built: $(/out/glibc$PREFIX/lib/ld-linux-aarch64.so.1 --version | head -1)"
