# Debian 13, like the libraries we ship from it: everything links against glibc <= 2.41 and runs on our 2.44.
FROM --platform=linux/arm64 debian:trixie

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential gawk bison python3 texinfo gettext jq curl ca-certificates \
        xz-utils patch file linux-libc-dev \
    && rm -rf /var/lib/apt/lists/*

RUN apt-get update && apt-get install -y --no-install-recommends \
        meson ninja-build pkg-config python3-mako python3-yaml python3-packaging \
        bison flex glslang-tools libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev \
        libxshmfence-dev libxcb1-dev libxcb-glx0-dev libxcb-dri2-0-dev libxcb-dri3-dev \
        libxcb-present-dev libxcb-sync-dev libxcb-shm0-dev libxcb-randr0-dev \
        libxcb-xfixes0-dev libglvnd-dev \
    && rm -rf /var/lib/apt/lists/*

RUN apt-get update && apt-get install -y --no-install-recommends \
        cmake libxcursor-dev libxi-dev libxss-dev \
    && rm -rf /var/lib/apt/lists/*

# llvmpipe, the CPU renderer: Mesa links LLVM statically (llvm-config asks for Polly too).
RUN apt-get update && apt-get install -y --no-install-recommends \
        llvm-19-dev libpolly-19-dev \
    && rm -rf /var/lib/apt/lists/*
